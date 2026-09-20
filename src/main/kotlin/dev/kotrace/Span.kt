package dev.kotrace

import dev.kotrace.event.SpanEvent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
 * confined to a crash reporter: a sink purpose-built to hold PII (isolated, purpose-limited, retention-bound
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
 * negligible — the same trade [SpanCollector] makes for its span list.
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

    /** Birth-set cross-trace edges — defensively copied for the same reason as [attributes]. */
    val links: List<TraceLink> = links.toList()

    /**
     * The mutable copy-on-write backing buffer — the **sole** append target ([dev.kotrace.event.emit], reached
     * through the log / named / exception verbs). Kept `internal` so an event only ever enters the timeline
     * through a verb (and thus through fan-out); consumers read [events] but cannot append.
     */
    internal val eventBuffer: MutableList<SpanEvent> = CopyOnWriteArrayList()

    /**
     * The span's timeline as a **read-only** view: a [TracePolicy] and the report/format path read and
     * pattern-match events here, but cannot append — nor cast back to a [MutableList] to force one in
     * ([Collections.unmodifiableList] throws on any mutator). It wraps the live [eventBuffer], so iteration
     * still delegates to the copy-on-write snapshot. Read off the report/consumer path only (never hot
     * [dev.kotrace.event.emit], which appends to [eventBuffer] directly), so the per-access wrapper is free.
     */
    val events: List<SpanEvent> get() = Collections.unmodifiableList(eventBuffer)

    /**
     * The span's terminal state — [status] and [endNanos] — as one immutable pair published through a single
     * [AtomicReference] (D1 / ADR-006). Two guarantees fall out. **Consistency:** a reader gets a coherent
     * pair, never `status == ERROR` with a torn `endNanos`. **Visibility:** each publish is a happens-before
     * edge, so a read is correct even off an unsynchronized thread — not only after the structured-concurrency
     * join the report already crosses; that keeps a future pre-join / mid-flight reader (e.g. a live
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

    /** Publishes both fields in one atomic write — the bridge completion path ([dev.kotrace.end]). */
    internal fun markCompleted(newStatus: SpanStatus, nanos: Long) {
        state.set(State(newStatus, nanos))
    }

    /**
     * The throwable this span's own `failureDetector` returned on a normal return (ADR-018), or null. Written
     * only by the detection path (`detectReturnedFailure` in `Trace.kt`) once, on the span's own return, and
     * read only by the auto-root to derive the trace verdict — after the structured-concurrency join, so a plain
     * `var` is safe (no cross-thread race; the writer and reader are ordered by the coroutine return). It is
     * **internal**: never lifted onto a [dev.kotrace.event.TraceRecord]; the recorded failure still lives in
     * [events] as an [dev.kotrace.event.ExceptionEvent] like a thrown one.
     */
    internal var detectedFailure: Throwable? = null

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
}
