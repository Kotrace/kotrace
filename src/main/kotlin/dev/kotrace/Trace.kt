package dev.kotrace

import dev.kotrace.event.recordPropagatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Opens a span, closes it on return, and marks it ERROR (recording the throwable) on the way out —
 * **rethrowing unchanged**. Named for the node it opens (a span), not the tree (ADR-003); the non-suspend
 * counterpart is [startSpan]. One function serves every use: [returnedOutcome] defaults to [alwaysOkOutcome]
 * and is consulted **only when this call auto-roots** (ADR-016) — pass it for failure-as-value, omit it
 * otherwise; [failureDetector] defaults to the process-wide detector ([Kotrace.failureDetector]) and, on
 * **every** span, turns a *returned* failure value into the same span-level ERROR + birthplace a thrown one
 * gets (ADR-018) — pass it to override or opt a span out (`{ null }`), omit it to inherit the ambient rule.
 *
 * **Auto-root (ADR-013).** A `span` opened with *neither* a current span *nor* a [SpanCollector] in context
 * owns the whole trace: it installs a collector, opens the root, runs [block], and calls [reportTrace] once
 * at the outcome. The **escaping** outcomes are core's: an escaping [CancellationException] →
 * [TraceStatus.CANCELLED], any other escaping throwable → [TraceStatus.ERROR] — the application throwable
 * always rethrown. On a **normal return** the trace's [TraceStatus] comes from the precedence chain below (an
 * explicit [returnedOutcome] wins; else the root's detected failure defaults the verdict; else [TraceStatus.OK]).
 * An explicit [returnedOutcome] can report a *returned* (not thrown) domain failure as `ERROR` and carry
 * trace-level orphan failures via [TraceOutcome.attached] (ADR-012). [returnedOutcome] runs **exactly once**,
 * on a normal return, and only when this call auto-roots; in every other context state it is **never invoked**.
 *
 * **Verdict precedence when auto-rooting on a normal return (ADR-018).** An explicit [returnedOutcome] (i.e.
 * not the default) wins; otherwise the trace verdict comes from the **root's own detected failure**: a returned
 * [CancellationException] → [TraceStatus.CANCELLED] (the same as a thrown escaping one), any other detected
 * throwable → [TraceStatus.ERROR], else [TraceStatus.OK]. So a [failureDetector] alone (no [returnedOutcome])
 * already reports a root-returned failure as `ERROR` — closing the silent-drop where it would otherwise be
 * recorded on the span yet reported `OK`.
 *
 * **`failureDetector` sees `T` — do not discard the returned value.** The detector is run on what [block]
 * returns. If the `span` call sits in a **`Unit`-expected position** and its result is discarded (e.g. it is
 * the last expression of a `() -> Unit` lambda), Kotlin infers `T = Unit` and the detector receives `Unit`,
 * not the `Result` — so nothing is detected. A failure-as-value caller *uses* the returned value (returns it
 * up), so `T` is the real type; bind it (`val r: Result<X> = span(name) { … }`) rather than calling `span`
 * as a value-discarding statement.
 *
 * Any other context state is the instrumentation-only path below: with a collector present the span is a child
 * (or a manual-boundary root the consumer reports itself); with a span present but no collector it is a child
 * for identity/live only. The consumer therefore only ever writes `span { }`; the outermost one is the
 * boundary, and [returnedOutcome] is inert on every non-auto-root span.
 *
 * This is instrumentation, not error handling: [span] only observes and rethrows, so a caller may wrap
 * a body in it and still handle failures inside however it likes. ERROR propagates up the tree naturally
 * — the rethrown throwable passes through every enclosing [span], marking each ancestor.
 *
 * Make [returnedOutcome] **pure, fast and non-suspending**: it is report configuration on the traced
 * coroutine's critical path, not application logic — no side effects. A **non-fatal** throw from it is
 * contained (the trace falls back to [TraceOutcome] `(OK, emptyList())` and the fault is surfaced through the
 * configured [AdapterFaultHook] with [FaultPhase.RETURNED_OUTCOME] and `adapter = null`); a mapper-thrown
 * [CancellationException] is contained the same way; a **JVM-fatal** mapper fault is rethrown and no report is
 * attempted. Because the mapper runs after the root has ended (the inner [span]'s `finally`), a mapper fault
 * can neither flip the root [SpanStatus] nor become its birthplace. Marking the root span for a returned
 * failure is now [failureDetector]'s job (ADR-018), not the block's — a [failureDetector] throw is contained
 * the same way ([FaultPhase.FAILURE_DETECTOR]); a detector returning the *stable* throwable a value carries is
 * what lets a returned failure's climb dedup to one birthplace, so it must not synthesize one per call.
 *
 * @sample dev.kotrace.samples.SpanSamples.spanUsage
 * @sample dev.kotrace.samples.SpanSamples.spanReturnedOutcomeUsage
 */
suspend fun <T> span(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    returnedOutcome: (T) -> TraceOutcome = alwaysOkOutcome,
    failureDetector: (T) -> Throwable? = InheritAmbient,
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()
    // Auto-root iff BOTH are absent (ADR-013). Keying on the collector alone would let an
    // identified-but-uncollected span (a SpanContext with no collector) mint a *child* into a fresh
    // collector whose walk then finds no root — silent loss. Read the coroutine context, not the mirrors.
    val spanCollector = context[SpanCollector]
    val spanContext = context[SpanContext]
    if (spanContext == null && spanCollector == null) {
        return autoRootSpan(name, attributes, links, returnedOutcome, failureDetector, block)
    }
    // Non-auto-root: returnedOutcome is inert (only the auto-root consults it, ADR-016). This is a child, a
    // manual-boundary root, or an identified-but-uncollected span; the enclosing root or a manual reportTrace
    // owns the single report.
    val opened = createSpan(
        spanContext?.span,
        name, attributes, links,
        context[ScopeContext]?.scopeId,
    )
    spanCollector?.add(opened)
    // Resolve the returned-failure detector once (ADR-018): the per-call override if given, else the
    // process-wide detector — a direct read that never forces config resolution or the strict latch. null
    // (nothing installed, no override) ⇒ no detection on the normal-return path below.
    val detector: ((T) -> Throwable?)? =
        if (failureDetector === InheritAmbient) Kotrace.failureDetector() else failureDetector
    return try {
        // Overlay only the element — withContext already inherits the current context. Passing the
        // whole currentCoroutineContext() would re-inject its Job and break structured concurrency.
        val value = withContext(SpanContext(opened)) { block() }
        // NEW (ADR-018): a returned failure is treated like a thrown one — mark ERROR + record the throwable,
        // the value-shaped analog of the catch below. Runs only on a *normal* return; on a throw the catch owns it.
        if (detector != null) detectReturnedFailure(opened, detector, value)
        value
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
 * The always-OK return mapper the zero-config [span] supplies to [autoRootSpan] (ADR-013): a normal return is
 * fixed to [TraceStatus.OK], while the escaping outcomes stay core's (decided in [autoRootSpan], not here).
 * Non-capturing, so the compiler emits it as a singleton — the zero-config auto-root path allocates no mapper.
 * Contravariance lets this `(Any?) -> …` stand in for the `(T) -> …` mapper parameter at any `T`.
 */
private val alwaysOkOutcome: (Any?) -> TraceOutcome = { TraceOutcome(TraceStatus.OK) }

/**
 * A consumer's rule for "what returned value counts as a failure" (ADR-018): a value → the throwable that
 * makes it a failure, or `null` when it is not. Maps 1:1 onto `Result.exceptionOrNull()`. The returned
 * throwable must be a **stable object carried by the value** (not synthesized per call), so the same failure
 * returned up the tree dedups to one birthplace (ADR-015). Installed process-wide on [Kotrace]; overridable
 * per-call on [span].
 */
typealias FailureDetector = (Any?) -> Throwable?

/**
 * Sentinel default for [span]'s `failureDetector` parameter, meaning "inherit the process detector"
 * ([Kotrace.failureDetector]). Non-capturing → JVM singleton, identity-compared (`===`); contravariance lets
 * this `(Any?) -> …` stand in for the `(T) -> …` parameter at every `T`. Resolution yields a *nullable*
 * detector (`null` ⇒ no-op), so no second no-op sentinel is needed.
 */
private val InheritAmbient: (Any?) -> Throwable? = { null }

/**
 * Records a returned failure like a thrown one (ADR-018). Runs [detector] on [value] under fault isolation
 * ([guardDetector] — a non-fatal detector throw, including a detector-*thrown* [CancellationException], is
 * contained as "no failure" and routed with [FaultPhase.FAILURE_DETECTOR]); a non-null result marks the span
 * [SpanStatus.ERROR], is stashed on [Span.detectedFailure] for the auto-root verdict, and is recorded via the
 * propagation path (canonical lineage key, so a returned failure's climb collapses to one birthplace,
 * ADR-015). A detector that *returns* a [CancellationException] is a failure like any other here — only the
 * auto-root *verdict* treats it specially (→ `CANCELLED`). Recording never fails a succeeding return: a
 * non-fatal record failure is contained, a JVM-fatal one rethrown.
 */
private fun <T> detectReturnedFailure(span: Span, detector: (T) -> Throwable?, value: T) {
    val failure = guardDetector(::quietFaultHook) { detector(value) } ?: return
    span.detectedFailure = failure
    span.markStatus(SpanStatus.ERROR)
    try {
        span.recordPropagatedException(failure)
    } catch (recordFailure: Throwable) {
        if (recordFailure.isFatalFault()) throw recordFailure
        // contain: telemetry recording must never turn a returning operation into a throw
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
 * The single auto-root boundary (ADR-013 + ADR-016): install a fresh [SpanCollector], let the recursion
 * open+register+run+end the root (the collector is now in context, so the nested [span] takes the
 * instrumentation path), then [reportTrace] the outcome **before** the collector context exits — so the
 * collector and any ambient [TraceConfig] are still resolved (overlaid alone; config inherited, never frozen
 * here — ADR-010).
 *
 * The report's status on a **normal return** follows the precedence chain (ADR-018): an **explicit**
 * [returnedOutcome] (`!== alwaysOkOutcome`) wins and maps the returned value (a failure-as-value consumer maps
 * a returned domain failure to `ERROR` with attached orphans, ADR-016); otherwise the **root's own detected
 * failure** (read from [Span.detectedFailure], set by [detectReturnedFailure] on the forwarded [failureDetector])
 * defaults the verdict — a returned [CancellationException] → [TraceStatus.CANCELLED], any other detected
 * throwable → [TraceStatus.ERROR]; otherwise [TraceStatus.OK]. The **escaping** outcomes are always core's,
 * never a mapper's: an escaping [CancellationException] → [TraceStatus.CANCELLED], any other escaping throwable
 * → [TraceStatus.ERROR], the throwable rethrown unchanged. [returnedOutcome] runs **exactly once**, on a normal
 * return, and only here — never on a child, a manual-boundary root, or an identified-but-uncollected span.
 * Because it runs after the inner [span]'s `finally` (root already ended), a mapper fault can neither flip the
 * root [SpanStatus] nor become its birthplace. Reports **after** the root has ended and **before** the
 * collector context exits, with strict/report precedence via [reportAutoRoot].
 */
private suspend fun <T> autoRootSpan(
    name: String,
    attributes: Map<String, String>,
    links: List<TraceLink>,
    returnedOutcome: (T) -> TraceOutcome,
    failureDetector: (T) -> Throwable?,
    block: suspend () -> T,
): T {
    val collector = SpanCollector()
    return withContext(collector) {
        val value = try {
            // Recurse to open+run+end the root via the instrumentation path (collector now in context, so
            // this call never auto-roots). returnedOutcome is left at its default here — a non-auto-root span
            // never consults it; this boundary owns the single report below. failureDetector IS forwarded, so
            // the root's own returned value is detected on that instrumentation path exactly once (ADR-018).
            span(name, attributes, links, failureDetector = failureDetector, block = block)
        } catch (t: Throwable) {
            // Escaping outcome is core's, not the mapper's: CancellationException → CANCELLED, else ERROR;
            // report with the escaped throwable so a strict-mode report failure rides it as suppressed.
            reportAutoRoot(collector, if (t is CancellationException) TraceStatus.CANCELLED else TraceStatus.ERROR, t)
            throw t
        }
        // Normal return: the root has already ended (span's finally). Verdict precedence (ADR-018): an explicit
        // returnedOutcome wins; else the root's own detected failure supplies the default — a returned
        // CancellationException → CANCELLED (mirror of the escaping-cancellation case above), any other detected
        // throwable → ERROR; else OK. Read root.detectedFailure (authoritative, set only by the root's own
        // detector), never the general-purpose SpanStatus.
        val detected = collector.spans.firstOrNull { it.parentId == null }?.detectedFailure
        val outcome = when {
            returnedOutcome !== alwaysOkOutcome -> runReturnedOutcome(returnedOutcome, value)
            detected is CancellationException -> TraceOutcome(TraceStatus.CANCELLED)
            detected != null -> TraceOutcome(TraceStatus.ERROR)
            else -> TraceOutcome(TraceStatus.OK)
        }
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
