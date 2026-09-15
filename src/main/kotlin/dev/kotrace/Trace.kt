package dev.kotrace

import dev.kotrace.event.recordPropagatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Opens a span, closes it on return, and marks it ERROR (recording the throwable) on the way out —
 * **rethrowing unchanged**. Named for the node it opens (a span), not the tree (ADR-003); the non-suspend
 * counterpart is [startSpan].
 *
 * **Auto-root (ADR-013).** A `span` opened with *neither* a current span *nor* a [SpanCollector] in context
 * owns the whole trace: it installs a collector, opens the root, runs [block], and calls [reportTrace] once
 * at the outcome (return → [TraceStatus.OK], an escaping [CancellationException] → [TraceStatus.CANCELLED],
 * any other escaping throwable → [TraceStatus.ERROR]) — the application throwable always rethrown. Any other
 * context state is the instrumentation-only path below: with a collector present the span is a child (or a
 * manual-boundary root the consumer reports itself); with a span present but no collector it is a child for
 * identity/live only. The consumer therefore only ever writes `span { }`; the outermost one is the boundary.
 *
 * This is instrumentation, not error handling: [span] only observes and rethrows, so a caller may wrap
 * a body in it and still handle failures inside however it likes. ERROR propagates up the tree naturally
 * — the rethrown throwable passes through every enclosing [span], marking each ancestor.
 *
 * @sample dev.kotrace.samples.SpanSamples.spanUsage
 */
suspend fun <T> span(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()
    // Auto-root iff BOTH are absent (ADR-013). Keying on the collector alone would let an
    // identified-but-uncollected span (a SpanContext with no collector) mint a *child* into a fresh
    // collector whose walk then finds no root — silent loss. Read the coroutine context, not the mirrors.
    val spanCollector = context[SpanCollector]
    val spanContext = context[SpanContext]
    if (spanContext == null && spanCollector == null) {
        return autoRootSpan(name, attributes, links, block)
    }
    val opened = createSpan(
        spanContext?.span,
        name, attributes, links,
        context[ScopeContext]?.scopeId,
    )
    spanCollector?.add(opened)
    return try {
        // Overlay only the element — withContext already inherits the current context. Passing the
        // whole currentCoroutineContext() would re-inject its Job and break structured concurrency.
        withContext(SpanContext(opened)) { block() }
    } catch (t: Throwable) {
        // ERROR marks the whole failing path as the throwable rethrows through each enclosing span.
        // Which span is the *birthplace* is decided at read time by lineage key (ADR-015, see
        // TraceTreeIndex.birthplaceExceptionsOf): every enclosing span re-records the climbing throwable, and the copies
        // share one canonical key even when coroutine stacktrace recovery copies `t` across each `withContext`
        // boundary, so the climb collapses to its deepest span.
        opened.markStatus(SpanStatus.ERROR)
        // Recording the throwable must never replace it: under strict-uninstalled (ADR-011)
        // resolvedThreadConfig can throw here, *before* the rethrow. Preserve the application throwable and
        // attach the failure as suppressed (ADR-013); a JVM-fatal failure still propagates. Record via the
        // propagation path (ADR-015): it stamps the canonical lineage key so this climb collapses to its
        // birthplace even when coroutine stacktrace recovery copies `t` at each boundary.
        try {
            opened.recordPropagatedException(t)
        } catch (recordFailure: Throwable) {
            if (recordFailure.isFatalFault()) throw recordFailure
            t.alsoSuppress(recordFailure)
        }
        throw t
    } finally {
        opened.markEnd(System.nanoTime())
    }
}

/**
 * Return-aware [span] (ADR-016): identical to [span] except that, **when this call auto-roots** (neither a
 * [SpanContext] nor a [SpanCollector] in context — ADR-013's null/null state), the trace's [TraceStatus] on a
 * **normal return** is chosen by [returnedOutcome] from the returned value rather than fixed to
 * [TraceStatus.OK]. This is the entry point for a **failure-as-value** consumer: a domain failure that is
 * *returned* (not thrown) can still report `ERROR` and carry trace-level orphan failures via
 * [TraceOutcome.attached] (ADR-012).
 *
 * Core keeps the escaping outcomes: an escaping [CancellationException] is [TraceStatus.CANCELLED], any other
 * escaping throwable is [TraceStatus.ERROR] — the mapper is **not** consulted for either, and the application
 * throwable is rethrown unchanged. [returnedOutcome] runs **exactly once**, on a normal return, **only** when
 * this call auto-roots; in every other context state (child, manual-boundary root, identified-but-uncollected)
 * it is **never invoked** and the enclosing root or a manual [reportTrace] owns the single report. Make it
 * **pure, fast and non-suspending**: it is report configuration evaluated on the traced coroutine's critical
 * path, not application logic — do not use it for side effects.
 *
 * A **non-fatal** throw from [returnedOutcome] is contained (it never alters the returned value or control
 * flow): the trace falls back to [TraceOutcome] `(OK, emptyList())` and the fault is surfaced through the
 * configured [AdapterFaultHook] with [FaultPhase.RETURNED_OUTCOME] and `adapter = null`. A mapper-thrown
 * [CancellationException] is contained the same way (it is not trace cancellation — the block already returned
 * normally). A **JVM-fatal** mapper fault is rethrown and no report is attempted.
 *
 * Marking the root span for a returned failure (and recording its birthplace throwable so the crash sink
 * receives it) stays the block's job, via the suspend-safe [dev.kotrace.event.addException] on
 * [currentSpan] — a returned `ERROR` verdict does not itself flip the root [SpanStatus], and it need not: a
 * crash adapter self-gates on the trace [TraceStatus] the mapper returns.
 *
 * @sample dev.kotrace.samples.SpanSamples.spanReturnedOutcomeUsage
 */
suspend fun <T> span(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    returnedOutcome: (T) -> TraceOutcome,
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()
    val spanCollector = context[SpanCollector]
    val spanContext = context[SpanContext]
    if (spanContext == null && spanCollector == null) {
        return autoRootSpanReturning(name, attributes, links, returnedOutcome, block)
    }
    // Non-auto-root: the mapper is inert (ADR-016). Delegate to the plain span — a child, a manual-boundary
    // root, or identified-but-uncollected, exactly as the zero-config overload would resolve it; the enclosing
    // root or a manual reportTrace owns the single report.
    return span(name, attributes, links, block)
}

/**
 * The auto-root boundary (ADR-013): install a fresh [SpanCollector], let the recursion open+register the
 * root and run [block] (the collector is now in context, so the nested [span] takes the instrumentation
 * path), then [reportTrace] the outcome **before** the collector context exits — so the collector and any
 * ambient [TraceConfig] are still resolved. The collector is overlaid alone; config is inherited, never
 * frozen here (ADR-010).
 */
private suspend fun <T> autoRootSpan(
    name: String,
    attributes: Map<String, String>,
    links: List<TraceLink>,
    block: suspend () -> T,
): T {
    val collector = SpanCollector()
    return withContext(collector) {
        var status = TraceStatus.OK
        var escaped: Throwable? = null
        try {
            span(name, attributes, links, block)
        } catch (t: Throwable) {
            escaped = t
            status = if (t is CancellationException) TraceStatus.CANCELLED else TraceStatus.ERROR
            throw t
        } finally {
            reportAutoRoot(collector, status, escaped)
        }
    }
}

/**
 * Reports the auto-root trace with strict-mode precedence (ADR-013): if reporting throws — e.g. strict
 * [resolvedThreadConfig] with nothing installed (ADR-011) — and an application throwable already [escaped],
 * preserve it and attach the failure as suppressed (never let a `finally` throw replace the app throwable).
 * On a normally-completing block a report failure propagates as itself; a JVM-fatal failure always does.
 */
private fun reportAutoRoot(
    collector: SpanCollector,
    status: TraceStatus,
    escaped: Throwable?,
    attached: List<Throwable> = emptyList(),
) {
    try {
        collector.reportTrace(status, attached)
    } catch (reportFailure: Throwable) {
        if (reportFailure.isFatalFault()) throw reportFailure
        if (escaped != null) escaped.alsoSuppress(reportFailure) else throw reportFailure
    }
}

/**
 * The value-aware auto-root boundary (ADR-016): same lifecycle as [autoRootSpan] — install the collector, let
 * the recursion open+register+run+end the root — but derive the report from the **returned value** via
 * [returnedOutcome] on a normal return, and from the escaping throwable otherwise. Reports **after** the root
 * has ended (the inner [span]'s `finally`) and **before** the collector context exits, mirroring ADR-013's
 * ordering and strict/report precedence (via [reportAutoRoot]).
 */
private suspend fun <T> autoRootSpanReturning(
    name: String,
    attributes: Map<String, String>,
    links: List<TraceLink>,
    returnedOutcome: (T) -> TraceOutcome,
    block: suspend () -> T,
): T {
    val collector = SpanCollector()
    return withContext(collector) {
        val value = try {
            span(name, attributes, links, block)
        } catch (t: Throwable) {
            // Escaping outcome is core's, not the mapper's: CancellationException → CANCELLED, else ERROR;
            // report with the escaped throwable so a strict-mode report failure rides it as suppressed.
            reportAutoRoot(collector, if (t is CancellationException) TraceStatus.CANCELLED else TraceStatus.ERROR, t)
            throw t
        }
        // Normal return: the root has already ended (span's finally), so the mapper never runs inside the
        // inner span's try/catch and a mapper fault can neither mark the root ERROR nor become its birthplace.
        val outcome = runReturnedOutcome(returnedOutcome, value)
        reportAutoRoot(collector, outcome.status, escaped = null, attached = outcome.attached)
        value
    }
}

/**
 * Runs the return-value [returnedOutcome] mapper under fault isolation (ADR-016): a **non-fatal** throw is
 * contained ([guardReturnedOutcome] routes it to the [AdapterFaultHook] with [FaultPhase.RETURNED_OUTCOME] and
 * `adapter = null`) and the trace falls back to [TraceOutcome] `(OK, emptyList())` — `OK` because that is the
 * verdict the zero-config overload would report for this same normal return, so a broken mapper never invents
 * a failure. A JVM-fatal mapper fault is rethrown by [guardReturnedOutcome] (no report attempted). The hook is
 * resolved lazily (only on a fault), so a successful mapper never resolves config ahead of the report.
 */
private fun <T> runReturnedOutcome(returnedOutcome: (T) -> TraceOutcome, value: T): TraceOutcome =
    guardReturnedOutcome(::quietFaultHook) { returnedOutcome(value) } ?: TraceOutcome(TraceStatus.OK)

/**
 * The [AdapterFaultHook] for mapper-fault routing, resolved defensively and **only on a fault** (via
 * [guardReturnedOutcome]'s lazy supplier), so a successful mapper never resolves config ahead of the report.
 * Under strict-uninstalled mode [resolvedThreadConfig] throws (ADR-011), but that is the report path's
 * concern (surfaced there), not the mapper's — so a non-fatal resolution failure yields a null hook (the
 * fault is then swallowed, as it would be with no hook installed). A JVM-fatal resolution failure still
 * propagates.
 */
private fun quietFaultHook(): AdapterFaultHook? =
    try {
        resolvedThreadConfig()?.faultHook
    } catch (t: Throwable) {
        if (t.isFatalFault()) throw t
        null
    }

/**
 * Opens a span in the current context and registers it into [currentThreadCollector], for **non-suspend** code
 * running on a coroutine's thread that has no `coroutineContext` handle to call [span] — the OkHttp
 * `Call.Factory` (a raw call built on the coroutine thread) is the case this exists for. The caller must
 * close it with [end].
 *
 * Opt-in ([NonSuspendTracingBridge]): unlike [span] this does **not** install its span into the coroutine
 * context, so a nested suspend [span] misparents. Use it only where no coroutine frame exists.
 *
 * Parented to [currentThreadSpan] (the thread-local mirror, since this is off the coroutine frame): a
 * child when one is active, else a **root** (fresh `traceId`) — an untraced request still gets a span, so
 * a live sink can log it. Whether it rooted is on the returned span (`parentId == null`); the caller need
 * not decide. With no active collector the span simply isn't collected (report needs one), which is the
 * correct no-op for a truly untraced background call.
 */
@NonSuspendTracingBridge
fun startSpan(name: String, attributes: Map<String, String> = emptyMap(), links: List<TraceLink> = emptyList()): Span {
    val opened = createSpan(currentThreadSpan(), name, attributes, links, currentThreadScopeId())
    currentThreadCollector()?.add(opened)
    return opened
}

/** Closes a span opened by [startSpan], stamping its end, [status] and optional [error]. Mirrors OTel `span.end()`. */
@NonSuspendTracingBridge
fun Span.end(status: SpanStatus = SpanStatus.OK, error: Throwable? = null) {
    markCompleted(status, System.nanoTime()) // both fields in one atomic publish
    if (error != null) this.recordPropagatedException(error) // ADR-015: canonical lineage key, collapses the climb
}

/**
 * Builds a span under [parent] — the one place the `Span(…)` shape lives, shared by the suspend [span]
 * and the non-suspend [startSpan]. A null [parent] roots a fresh trace (new `traceId`). The caller
 * resolves [parent] from its own source — [span] from the coroutine context (authoritative), [startSpan]
 * from the [currentThreadSpan] mirror — so this never reaches for ambient state.
 *
 * [links] are the span's birth-set cross-trace edges ([TraceLink]) — carried as passed, no inheritance from
 * [parent]: a link is a per-span statement, and its natural carrier is a trace root a caller opens with
 * links in hand.
 */
private fun createSpan(
    parent: Span?,
    name: String,
    attributes: Map<String, String>,
    links: List<TraceLink> = emptyList(),
    scopeId: String? = null,
): Span = Span(
    traceId = parent?.traceId ?: hex(16),
    spanId = hex(8),
    parentId = parent?.spanId,
    name = name,
    startNanos = System.nanoTime(),
    attributes = attributes,
    links = links,
    scopeId = scopeId,
)

private val random = SecureRandom()

/**
 * Lowercase hex of [bytes] random bytes — 16 for a trace id (W3C 32 chars), 8 for a span id (16). The
 * all-zero value is regenerated: W3C Trace Context declares an all-zero trace-id/span-id invalid, so a
 * downstream tracer would reject the `traceparent`. (Astronomically rare from `SecureRandom`, but a minted
 * id must never be one a peer discards.)
 */
internal fun hex(bytes: Int): String {
    val b = ByteArray(bytes)
    do { random.nextBytes(b) } while (b.all { it == 0.toByte() })
    return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
