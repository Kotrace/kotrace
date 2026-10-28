package dev.kotrace

import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent
import dev.kotrace.report.reportTrace
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The outcome of the work performed within a [Span]. */
enum class SpanStatus { OK, ERROR }

/**
 * One unit of work in a trace. A tree of these, linked by [parentId], is the call path:
 * caller → callee → callee → throwable.
 *
 * [name], every [traits] value and every [events] message are **static symbols only**: a span reaches broad,
 * searchable sinks (a JSON log store via [dev.kotrace.event.toJson], a backend via `traceparent`) that are the
 * wrong place for user data. The raw [Throwable] on an [dev.kotrace.event.ExceptionEvent] is the sole exception,
 * since its message is not under our control; it belongs only in a crash reporter, never on the
 * [dev.kotrace.event.toJson] or backend path.
 *
 * [traits] are the span's **fixed filter dimensions**, set at construction (a `layer`, say): the only per-span
 * input a [dev.kotrace.adapter.TracePolicy.acceptsSpan] gate reads, so live and report filter alike (ADR-001). They
 * never reach a record: what a sink should see goes into [attributes] instead.
 *
 * [links] are the span's cross-trace edges ([TraceLink]), set at construction; [parentId] is the in-tree edge.
 *
 * [scopeId] is the [dev.kotrace.withScope] id active when the span opened, else the installed fallback's
 * (`Kotrace.install(fallbackScopeId = …)`, ADR-038), else null (ADR-010). Every record lifted off the span carries
 * it, so work inside a scope correlates above its [traceId]. It is never a filter dimension.
 *
 * [events] is copy-on-write, since parallel children and off-coroutine bridges (OkHttp, Room) append concurrently,
 * and bounded at [maxEvents]: past that the oldest breadcrumbs are discarded ([droppedEvents]), an exception only
 * once nothing else is left. [maxEvents] is fixed when the span opens, from [TraceConfig.maxEventsPerSpan]
 * (ADR-033), and is at least 1.
 */
class Span(
    val traceId: String,
    val spanId: String,
    val parentId: String?,
    val name: String,
    val startNanos: Long,
    traits: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    val scopeId: String? = null,
    val maxEvents: Int = MAX_EVENTS,
) {
    init {
        require(maxEvents >= 1) { "maxEvents must be at least 1, was $maxEvents" }
    }

    /**
     * Copied, so a caller mutating its map cannot desync live and report filtering, and read-only even cast back
     * to [MutableMap].
     */
    val traits: Map<String, String> = Collections.unmodifiableMap(traits.toMap())

    /** Copied and read-only like [traits], each link's own attribute map included. */
    val links: List<TraceLink> = Collections.unmodifiableList(
        links.map { it.copy(attributes = Collections.unmodifiableMap(it.attributes.toMap())) },
    )

    private val _attributes = ConcurrentHashMap<String, String>()

    /**
     * The span's **attributes**: values a sink should see, often known only after the span opens (`http.status` once
     * the response returns). They ride every emitted [dev.kotrace.event.TraceRecord] as
     * [dev.kotrace.event.TraceRecord.spanAttributes], each one only to an adapter whose
     * [TracePolicy.acceptsAttribute] admits it (ADR-039). No [TracePolicy] gate decides a record's delivery on them,
     * so a gate never depends on a value whose presence varies with timing (ADR-001); filter on [traits] instead. The
     * span itself keeps every attribute, whatever an adapter admits. Static symbols only;
     * thread-safe. A live read-only view: it reflects every later [putAttribute], and rejects a write even cast
     * back to [MutableMap].
     */
    val attributes: Map<String, String> = Collections.unmodifiableMap(_attributes)

    /** Stamps one [attributes] value: a symbol, never user data. */
    fun putAttribute(key: String, value: String) {
        _attributes[key] = value
    }

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
        updateState { State(newStatus, it.endNanos) }
    }

    internal fun markEnd(nanos: Long) {
        updateState { State(it.status, nanos) }
    }

    /** `AtomicReference.updateAndGet` by hand: Android has it only from API 24. */
    private inline fun updateState(transform: (State) -> State) {
        while (true) {
            val current = state.get()
            if (state.compareAndSet(current, transform(current))) return
        }
    }

    /**
     * Ends the span only if it is still open ([dev.kotrace.bridge.end]): the first end wins, and a later one returns
     * `false`, so an ERROR can never be flipped to OK.
     */
    internal fun tryComplete(newStatus: SpanStatus, nanos: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.endNanos != null) return false
            if (state.compareAndSet(current, State(newStatus, nanos))) return true
        }
    }

    /** The sole append target, reached only through the event verbs (and thus fan-out); consumers read [events]. */
    internal val eventBuffer = EventTimeline()

    /**
     * The span's timeline, read-only (even cast back to [MutableList]).
     *
     * **Unredacted** ([UnredactedTraceRead], ADR-028): it holds `sensitive` messages and raw throwables, unfiltered
     * by any [TracePolicy]. It is for a person or a test inspecting a trace; machine egress goes through
     * [reportTrace] or a [LiveAdapter], which apply the policies.
     */
    @UnredactedTraceRead
    val events: List<SpanEvent> get() = Collections.unmodifiableList(eventBuffer)

    private val dropped = AtomicInteger()

    /**
     * How many events this span has discarded to stay within [maxEvents] (ADR-029), also stamped as an attribute
     * under [EVENTS_DROPPED_ATTRIBUTE] so a record shows the timeline is incomplete.
     */
    val droppedEvents: Int get() = dropped.get()

    /**
     * Appends [event], keeping the timeline within [maxEvents] (ADR-029): past it [EventTimeline] discards the oldest
     * [NamedEvent], else the oldest log, and an [ExceptionEvent] only when nothing else is left, always keeping the
     * newest one, the escaping one.
     */
    internal fun appendEvent(event: SpanEvent) {
        if (!eventBuffer.append(event, maxEvents)) return
        // Serialized, so the attribute never steps back to a smaller count than a racing drop already wrote.
        synchronized(dropped) { putAttribute(EVENTS_DROPPED_ATTRIBUTE, dropped.incrementAndGet().toString()) }
    }

    companion object {
        /**
         * The default [maxEvents] (ADR-029), bounding memory and the copy-on-write append cost of a span that logs in
         * a loop.
         */
        const val MAX_EVENTS: Int = 1_000

        /**
         * The [attributes] key carrying [droppedEvents]. Read live when a record is built, so a report can show a
         * count slightly newer than its frozen timeline (ADR-029). An adapter receives it only when its
         * [TracePolicy.acceptsAttribute] admits this key (ADR-039).
         */
        const val EVENTS_DROPPED_ATTRIBUTE: String = "kotrace.events_dropped"
    }
}
