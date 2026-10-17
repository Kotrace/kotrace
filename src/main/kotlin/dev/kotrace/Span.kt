package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Represents the outcome of the work performed within a [Span].
 *
 * A status indicates whether the operation completed successfully or encountered an error.
 */
enum class SpanStatus { OK, ERROR }

/**
 * One unit of work in a trace. A tree of these, linked by [parentId], is the call path:
 * caller → callee → callee → throwable.
 *
 * [name], every [attributes] value and every [events] message are **static symbols only** — a Span fans
 * out to general-purpose, broad-access sinks (a JSON log store via [dev.kotrace.event.toJson], a backend
 * via `traceparent`) that are the wrong place for user data: searchable by a wide audience, multipurpose,
 * and shipped onward past easy recall. Keeping every field a symbol is what makes those sinks PII-safe by
 * construction. The raw [Throwable] on an [dev.kotrace.event.ExceptionEvent] is the **sole exception** —
 * its `message` is not fully in our control (stdlib/third-party interpolate user data) — and it is therefore
 * confined to a crash reporter: a sink purpose-built to hold PII (isolated, purpose-limited, retention-bound,
 * and deletable). It must never be rendered onto the [dev.kotrace.event.toJson] / backend path.
 *
 * [attributes] are the span's **fixed, birth-set filter dimensions** — an immutable [Map] settled at
 * construction (a `layer`, say). They are the only per-span input a [dev.kotrace.TracePolicy.acceptsSpan]
 * gate reads, and because they never change they filter identically at live emit time and report (ADR-001). A
 * value known only *late* (a result like `http.status`) is **not** an attribute — it is emitted [info]
 * ([putInfo]); it must never become a filter key.
 *
 * [links] are the span's birth-set **cross-trace edges** ([TraceLink]) — an immutable list of references to
 * other traces, settled at construction like [attributes]. Distinct from [parentId] (the in-tree edge); see
 * [TraceLink]. The natural carrier is a trace's root span.
 *
 * [scopeId] is the ambient [dev.kotrace.withScope] id active when this span opened, or null (ADR-010).
 * Stamped onto every [dev.kotrace.event.TraceRecord] lifted off the span ([dev.kotrace.event.recordOf]) so
 * an operation inside a scope correlates under it, above its own [traceId]. A plain correlation key: never a
 * filter dimension, never read by a [TracePolicy] or the report path.
 *
 * [events] is copy-on-write: a traced fan-out (parallel `async` children) or a non-suspend callback on
 * an off-coroutine thread (OkHttp/Room) may append concurrently. Traces hold few events, so the cost is
 * negligible — the same trade [SpanCollector] makes for its span list. It is bounded at [MAX_EVENTS]: past that,
 * the oldest breadcrumbs are discarded ([droppedEvents]); an exception only once nothing else is left.
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
    /** Birth-set filter dimensions — defensively copied so a caller mutating its map after construction can't desync live vs. report. */
    val attributes: Map<String, String> = attributes.toMap()

    /**
     * Birth-set cross-trace edges — defensively copied for the same reason as [attributes], each link's own
     * attribute map included (a [TraceLink] keeps whatever map it was given).
     */
    val links: List<TraceLink> = links.map { it.copy(attributes = it.attributes.toMap()) }

    /**
     * The mutable copy-on-write backing buffer — the **sole** append target ([dev.kotrace.event.emit], reached
     * through the log / named / exception verbs). Kept `internal` so an event only ever enters the timeline
     * through a verb (and thus through fan-out); consumers read [events] but cannot append.
     */
    internal val eventBuffer: MutableList<SpanEvent> = CopyOnWriteArrayList()

    private val dropped = AtomicInteger()

    /**
     * How many events this span has discarded to stay within [MAX_EVENTS] (ADR-029). The count is also
     * stamped as [info] under [EVENTS_DROPPED_INFO], so a report or live record shows its span's timeline is
     * incomplete. An exception is dropped only when the timeline holds nothing else (see [appendEvent]).
     */
    val droppedEvents: Int get() = dropped.get()

    /**
     * Appends [event], keeping the timeline bounded (ADR-029): past [MAX_EVENTS], the **oldest** event is
     * discarded, in this order of preference — a [NamedEvent] (never part of a report, and already fanned out live
     * when it was appended), then a log, since the breadcrumbs just before a failure are the ones worth keeping. An
     * [ExceptionEvent] survives any volume of other events; only a timeline holding nothing but exceptions (a long-lived
     * span recording handled failures in a loop) sheds its oldest exception, so the cap bounds every span. The
     * newest exception — the one escaping, if any — is always the last appended, so it is kept. Under concurrent
     * appends each racing writer can add one event over the cap, until the next append trims it back.
     */
    internal fun appendEvent(event: SpanEvent) {
        if (eventBuffer.size >= MAX_EVENTS) {
            synchronized(eventBuffer) {
                // `while`, not `if`: lock-free appends racing past the size check can overshoot the cap by a few
                // events; trimming back under the lock keeps the overshoot transient instead of permanent.
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
     * How many [NamedEvent]s the timeline holds, so eviction on a span with none — the common, log-only case —
     * never scans the whole buffer looking for one. Counted before the append and after the removal, so under a
     * race it can only over-count, which costs a longer scan, never a wrong victim.
     */
    private val namedCount = AtomicInteger()

    /**
     * The event to discard, in one pass over the copy-on-write snapshot: the oldest [NamedEvent] if the timeline
     * has any, else the oldest log, else — a timeline of nothing but exceptions — the oldest exception.
     */
    private fun victimIndex(): Int {
        val lookForNamed = namedCount.get() > 0
        var oldestLog = -1
        var index = 0
        for (candidate in eventBuffer) {
            if (candidate is NamedEvent) return index
            if (oldestLog < 0 && candidate !is ExceptionEvent) {
                if (!lookForNamed) return index
                oldestLog = index
            }
            index++
        }
        return if (oldestLog >= 0) oldestLog else 0
    }

    /**
     * The span's timeline as a **read-only** view: a [TracePolicy] and the report/format path read and
     * pattern-match events here, but cannot append — nor cast back to a [MutableList] to force one in
     * ([Collections.unmodifiableList] throws on any mutator). It wraps the live [eventBuffer], so iteration
     * still delegates to the copy-on-write snapshot. Read off the report/consumer path only (never hot
     * [dev.kotrace.event.emit], which appends to [eventBuffer] directly), so the per-access wrapper is free.
     *
     * **Unredacted** ([UnredactedTraceRead], ADR-028): the timeline holds `sensitive` log messages and raw
     * throwables, unfiltered by any [TracePolicy]. It is for a person or a test inspecting a trace; machine
     * egress goes through [reportTrace] or a [LiveAdapter], which apply the policies.
     */
    @UnredactedTraceRead
    val events: List<SpanEvent> get() = Collections.unmodifiableList(eventBuffer)

    /**
     * The span's terminal state — [status] and [endNanos] — as one immutable pair published through a single
     * [AtomicReference] (D1 / ADR-006). Two guarantees fall out. **Consistency:** a reader gets a coherent
     * pair, never `status == ERROR` with a torn `endNanos`. **Visibility:** each publish is a happens-before
     * edge, so a read is correct even off an unsynchronized thread — not only after the structured-concurrency
     * join the report already crosses; that keeps a future pre-join / mid-flight reader (e.g., a live
     * dashboard) safe without leaning on the join.
     *
     * The birthplace throwable is deliberately **not** here — it lives in [events] (copy-on-write), its single
     * source of truth ([dev.kotrace.event.exception]); folding it in would duplicate an event. The pair is
     * written by exactly one owner completing a span (the coroutine's catch/finally, or a bridge's
     * [dev.kotrace.end]); the `updateAndGet` mutators are safe even so.
     */
    private class State(val status: SpanStatus, val endNanos: Long?)
    private val state = AtomicReference(State(SpanStatus.OK, null))

    /** The trace node's status — [SpanStatus.OK] until a failure marks it [SpanStatus.ERROR]. */
    val status: SpanStatus get() = state.get().status

    /** When the span closed (`System.nanoTime()`), or `null` while still open. */
    val endNanos: Long? get() = state.get().endNanos

    /** Marks the [status], preserving [endNanos] — the coroutine catch and the throwable climb. */
    internal fun markStatus(newStatus: SpanStatus) {
        state.updateAndGet { State(newStatus, it.endNanos) }
    }

    /** Closes the span at [nanos], preserving [status] — the coroutine finally. */
    internal fun markEnd(nanos: Long) {
        state.updateAndGet { State(it.status, nanos) }
    }

    /**
     * Publishes both fields in one atomic write **iff the span is still open** — the bridge completion path
     * ([dev.kotrace.end]). The first terminal transition wins; a later one (a second `end`, a cloned call reusing
     * the span, a racing callback) returns `false` and changes nothing, so an ERROR can never be flipped to OK.
     */
    internal fun tryComplete(newStatus: SpanStatus, nanos: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.endNanos != null) return false
            if (state.compareAndSet(current, State(newStatus, nanos))) return true
        }
    }

    /**
     * Late-known **emitted info** — a result value stamped after the span opens (`http.status` once the
     * response returns). Distinct from [attributes] on both axes: it is *dynamic* (written mid-span, final
     * at the end) and it is **info, never a filter key** — it flows onto every emitted [dev.kotrace.event.TraceRecord] as
     * payload but is **never** read by a [TracePolicy] gate. That split is what keeps filtering
     * race-free: a gate sees only the immutable [attributes], so it cannot depend on a value whose presence
     * varies with emit order (ADR-001).
     *
     * A static symbol only, same as [attributes] — no user data (it can reach a crash report / backend).
     * Thread-safe: a bridge (OkHttp) writes it off the coroutine thread, as [events] is appended.
     */
    private val _info = ConcurrentHashMap<String, String>()
    val info: Map<String, String> get() = _info

    /** Stamps one [info] value. A symbol only (no user data); never a filter key — see [info]. */
    fun putInfo(key: String, value: String) {
        _info[key] = value
    }

    companion object {
        /**
         * The most events a span keeps (ADR-029). A trace normally holds a handful; the cap bounds memory and
         * the copy-on-write append cost for a span that logs in a loop (a long-lived session span, a SQL-heavy
         * flow). Approximate under concurrent appends: each racing writer can add one event over it, until the
         * next append trims the span back.
         */
        const val MAX_EVENTS: Int = 1_000

        /**
         * The [info] key carrying [droppedEvents] once a span has discarded any events. Like all [info], it is read
         * live when a record is built, so a report can show a count a little newer than the timeline it froze
         * (ADR-029) — an upper bound on what that report is missing.
         */
        const val EVENTS_DROPPED_INFO: String = "kotrace.events_dropped"
    }
}
