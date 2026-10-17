package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The outcome of the work performed within a [Span]. */
enum class SpanStatus { OK, ERROR }

/**
 * One unit of work in a trace. A tree of these, linked by [parentId], is the call path:
 * caller → callee → callee → throwable.
 *
 * [name], every [attributes] value and every [events] message are **static symbols only**: a span reaches broad,
 * searchable sinks (a JSON log store via [dev.kotrace.event.toJson], a backend via `traceparent`) that are the
 * wrong place for user data. The raw [Throwable] on an [dev.kotrace.event.ExceptionEvent] is the sole exception,
 * since its message is not under our control; it belongs only in a crash reporter, never on the
 * [dev.kotrace.event.toJson] or backend path.
 *
 * [attributes] are the span's **fixed filter dimensions**, set at construction (a `layer`, say): the only per-span
 * input a [dev.kotrace.TracePolicy.acceptsSpan] gate reads, so live and report filter alike (ADR-001). A value
 * known only later (`http.status`) is [info], never a filter key.
 *
 * [links] are the span's cross-trace edges ([TraceLink]), set at construction; [parentId] is the in-tree edge.
 *
 * [scopeId] is the [dev.kotrace.withScope] id active when the span opened, or null (ADR-010). Every record lifted
 * off the span carries it, so work inside a scope correlates above its [traceId]. It is never a filter dimension.
 *
 * [events] is copy-on-write, since parallel children and off-coroutine bridges (OkHttp, Room) append concurrently,
 * and bounded at [MAX_EVENTS]: past that the oldest breadcrumbs are discarded ([droppedEvents]), an exception only
 * once nothing else is left.
 */
class Span(
    val traceId: String,
    val spanId: String,
    val parentId: String?,
    val name: String,
    val startNanos: Long,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    val scopeId: String? = null,
) {
    /** Copied, so a caller mutating its map cannot desync live and report filtering. */
    val attributes: Map<String, String> = attributes.toMap()

    /** Copied like [attributes], each link's own attribute map included. */
    val links: List<TraceLink> = links.map { it.copy(attributes = it.attributes.toMap()) }

    /** The sole append target, reached only through the event verbs (and thus fan-out); consumers read [events]. */
    internal val eventBuffer: MutableList<SpanEvent> = CopyOnWriteArrayList()

    private val dropped = AtomicInteger()

    /**
     * How many events this span has discarded to stay within [MAX_EVENTS] (ADR-029), also stamped as [info] under
     * [EVENTS_DROPPED_INFO] so a record shows the timeline is incomplete.
     */
    val droppedEvents: Int get() = dropped.get()

    /**
     * Appends [event], keeping the timeline bounded (ADR-029). Past [MAX_EVENTS] the oldest event is discarded: a
     * [NamedEvent] first (already fanned out live, never reported), then a log. An [ExceptionEvent] is shed only
     * when nothing else is left, and the newest one, the escaping one, is always kept. Racing appends can overshoot
     * the cap by one each, until the next append trims it.
     */
    internal fun appendEvent(event: SpanEvent) {
        if (eventBuffer.size >= MAX_EVENTS) {
            synchronized(eventBuffer) {
                // `while`: lock-free appends racing past the size check can overshoot; trim them back too.
                while (eventBuffer.size >= MAX_EVENTS) {
                    val removed = eventBuffer.removeAt(victimIndex())
                    if (removed is NamedEvent) namedCount.decrementAndGet()
                    putInfo(EVENTS_DROPPED_INFO, dropped.incrementAndGet().toString())
                }
            }
        }
        if (event is NamedEvent) namedCount.incrementAndGet()
        eventBuffer += event
    }

    /**
     * How many [NamedEvent]s the timeline holds, so a log-only span never scans for one. Under a race it can only
     * over-count, which costs a longer scan, never a wrong victim.
     */
    private val namedCount = AtomicInteger()

    /** The oldest [NamedEvent], else the oldest log, else the oldest exception, in one pass. */
    private fun victimIndex(): Int {
        val lookForNamed = namedCount.get() > 0
        var oldestLog = -1
        for ((index, candidate) in eventBuffer.withIndex()) {
            if (candidate is NamedEvent) return index
            if (oldestLog < 0 && candidate !is ExceptionEvent) {
                if (!lookForNamed) return index
                oldestLog = index
            }
        }
        return if (oldestLog >= 0) oldestLog else 0
    }

    /**
     * The span's timeline, read-only (even cast back to [MutableList]).
     *
     * **Unredacted** ([UnredactedTraceRead], ADR-028): it holds `sensitive` messages and raw throwables, unfiltered
     * by any [TracePolicy]. It is for a person or a test inspecting a trace; machine egress goes through
     * [reportTrace] or a [LiveAdapter], which apply the policies.
     */
    @UnredactedTraceRead
    val events: List<SpanEvent> get() = Collections.unmodifiableList(eventBuffer)

    /**
     * [status] and [endNanos] published as one immutable pair (ADR-006), so a reader on any thread sees a coherent
     * pair. The birthplace throwable is not here: it lives in [events].
     */
    private class State(val status: SpanStatus, val endNanos: Long?)
    private val state = AtomicReference(State(SpanStatus.OK, null))

    /** [SpanStatus.OK] until a failure marks it [SpanStatus.ERROR]. */
    val status: SpanStatus get() = state.get().status

    /** When the span closed (`System.nanoTime()`), or `null` while still open. */
    val endNanos: Long? get() = state.get().endNanos

    internal fun markStatus(newStatus: SpanStatus) {
        state.updateAndGet { State(newStatus, it.endNanos) }
    }

    internal fun markEnd(nanos: Long) {
        state.updateAndGet { State(it.status, nanos) }
    }

    /**
     * Ends the span only if it is still open ([dev.kotrace.end]): the first end wins, and a later one returns
     * `false`, so an ERROR can never be flipped to OK.
     */
    internal fun tryComplete(newStatus: SpanStatus, nanos: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.endNanos != null) return false
            if (state.compareAndSet(current, State(newStatus, nanos))) return true
        }
    }

    /**
     * Late-known **emitted info**, stamped after the span opens (`http.status` once the response returns). It rides
     * every emitted [dev.kotrace.event.TraceRecord] but no [TracePolicy] reads it, so a gate never depends on a
     * value whose presence varies with timing (ADR-001). Static symbols only; thread-safe.
     */
    private val _info = ConcurrentHashMap<String, String>()
    val info: Map<String, String> get() = _info

    /** Stamps one [info] value: a symbol, never user data. */
    fun putInfo(key: String, value: String) {
        _info[key] = value
    }

    companion object {
        /**
         * The most events a span keeps (ADR-029), bounding memory and the copy-on-write append cost of a span that
         * logs in a loop. Approximate under concurrent appends.
         */
        const val MAX_EVENTS: Int = 1_000

        /**
         * The [info] key carrying [droppedEvents]. Read live when a record is built, so a report can show a count
         * slightly newer than its frozen timeline (ADR-029).
         */
        const val EVENTS_DROPPED_INFO: String = "kotrace.events_dropped"
    }
}
