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
 * otherwise; [failureClassifier] defaults to the process-wide classifier ([Kotrace.failureClassifier]) and,
 * on **every** span, turns a *returned* failure value into span-level ERROR (ADR-018/020). A
 * [ReturnedFailure.CausedBy] also records the throwable as a birthplace; [ReturnedFailure.ValueOnly] changes
 * status only and emits no record. Pass it to override or opt a span out (`{ null }`); omit it to inherit the
 * ambient rule.
 *
 * **Auto-root (ADR-013).** A `span` opened with *neither* a current span *nor* a [SpanCollector] in context
 * owns the whole trace: it installs a collector, opens the root, runs [block], and calls [reportTrace] once
 * at the outcome. The **escaping** outcomes are core's: an escaping [CancellationException] →
 * [TraceStatus.CANCELLED], any other escaping throwable → [TraceStatus.ERROR] — the application throwable
 * always rethrown. On a **normal return** the trace's [TraceStatus] comes from the precedence chain below (an
 * explicit [returnedOutcome] wins; else the root's classified failure defaults the verdict; else [TraceStatus.OK]).
 * An explicit [returnedOutcome] can report a *returned* (not thrown) domain failure as `ERROR` and carry
 * trace-level orphan failures via [TraceOutcome.attached] (ADR-012). [returnedOutcome] runs **exactly once**,
 * on a normal return, and only when this call auto-roots; in every other context state it is **never invoked**.
 *
 * **Verdict precedence when auto-rooting on a normal return (ADR-018).** An explicit [returnedOutcome] (i.e.,
 * not the default) wins; otherwise the trace verdict comes from the **root's own classified failure**:
 * [ReturnedFailure.CausedBy] carrying a [CancellationException] → [TraceStatus.CANCELLED] (the same as a thrown
 * escaping one), any other classification → [TraceStatus.ERROR], else [TraceStatus.OK]. So a
 * [failureClassifier] alone (no [returnedOutcome]) already reports a root-returned failure as `ERROR`.
 *
 * **`failureClassifier` sees `T` — do not discard the returned value.** The classifier is run on what [block]
 * returns. If the `span` call sits in a **`Unit`-expected position** and its result is discarded (e.g., it is
 * the last expression of a `() -> Unit` lambda), Kotlin infers `T = Unit` and the classifier receives `Unit`,
 * not the `Result` — so nothing is classified. A failure-as-value caller *uses* the returned value (returns it
 * up), so `T` is the real type; bind it (`val r: Result<X> = span(name) { … }`) rather than calling `span`
 * as a value-discarding statement.
 *
 * Any other context state is the instrumentation-only path below: with a collector present the span is a child
 * (or a manual-boundary root the consumer reports itself); with a span present but no collector, it is a child
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
 * attempted. Because the mapper runs after the root execution helper's `finally`, a mapper fault
 * can neither flip the root [SpanStatus] nor become its birthplace. Marking the root span for a returned
 * failure is [failureClassifier]'s job (ADR-018/020), not the block's — a classifier throw is contained the
 * same way ([FaultPhase.FAILURE_CLASSIFIER]); a classifier returning [ReturnedFailure.CausedBy] with the
 * *stable* throwable a value carries is what lets a returned failure's climb dedup to one birthplace, so it
 * must not synthesize one per call. [ReturnedFailure.ValueOnly] marks ERROR without inventing an exception,
 * log, or machine record.
 *
 * @sample dev.kotrace.samples.SpanSamples.spanUsage
 * @sample dev.kotrace.samples.SpanSamples.spanReturnedOutcomeUsage
 * @sample dev.kotrace.samples.SpanSamples.spanFailureClassifierUsage
 */
suspend fun <T> span(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    returnedOutcome: (T) -> TraceOutcome = alwaysOkOutcome,
    failureClassifier: (T) -> ReturnedFailure? = InheritAmbient,
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()
    // Auto-root iff BOTH are absent (ADR-013). Keying on the collector alone would let an
    // identified-but-uncollected span (a SpanContext with no collector) mint a *child* into a fresh
    // collector whose walk then finds no root — silent loss. Read the coroutine context, not the mirrors.
    val spanCollector = context[SpanCollector]
    val spanContext = context[SpanContext]
    if (spanContext == null && spanCollector == null) {
        return autoRootSpan(
            name,
            attributes,
            links,
            context[ScopeContext]?.scopeId,
            returnedOutcome,
            failureClassifier,
            block,
        )
    }
    // Non-auto-root: returnedOutcome is inert (only the auto-root consults it, ADR-016). This is a child, a
    // manual-boundary root, or an identified-but-uncollected span; the enclosing root or a manual reportTrace
    // owns the single report.
    return executeSpan(
        parent = spanContext?.span,
        collector = spanCollector,
        name = name,
        attributes = attributes,
        links = links,
        scopeId = context[ScopeContext]?.scopeId,
        failureClassifier = failureClassifier,
        block = block,
    ).value
}

/** A normally completed span's value plus the classification needed only by its auto-root owner. */
private class SpanCompletion<T>(val value: T, val classifiedFailure: ReturnedFailure?)

/**
 * Opens, registers, runs and closes one span. The completion envelope keeps the root classifier's control-flow
 * result out of the persistent [Span] model while still carrying it to [autoRootSpan] without a second
 * invocation. A thrown block never produces a completion; it follows the unchanged record-and-rethrow path.
 */
private suspend fun <T> executeSpan(
    parent: Span?,
    collector: SpanCollector?,
    name: String,
    attributes: Map<String, String>,
    links: List<TraceLink>,
    scopeId: String?,
    failureClassifier: (T) -> ReturnedFailure?,
    block: suspend () -> T,
): SpanCompletion<T> {
    val opened = createSpan(parent, name, attributes, links, scopeId)
    collector?.add(opened)
    // Resolve the returned-failure classifier once (ADR-018/020): the per-call override if given, else the
    // process-wide classifier — a direct read that never forces config resolution or the strict latch. null
    // (nothing installed, no override) ⇒ no classification on the normal-return path below.
    val classifier: ((T) -> ReturnedFailure?)? =
        if (failureClassifier === InheritAmbient) Kotrace.failureClassifier() else failureClassifier
    return try {
        // Overlay only the element — withContext already inherits the current context. Passing the
        // whole currentCoroutineContext() would re-inject its Job and break structured concurrency.
        val value = withContext(SpanContext(opened)) { block() }
        // A classified return marks ERROR; a CausedBy additionally records the throwable, while ValueOnly
        // deliberately emits no event (ADR-020). Runs only on a normal return; on a throw the catch owns it.
        val classifiedFailure = classifier?.let { classifyReturnedFailure(opened, it, value) }
        SpanCompletion(value, classifiedFailure)
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
 * Sentinel default for [span]'s `failureClassifier` parameter, meaning "inherit the process classifier"
 * ([Kotrace.failureClassifier]). Non-capturing → JVM singleton, identity-compared (`===`); contravariance lets
 * this `(Any?) -> …` stand in for the `(T) -> …` parameter at every `T`. Resolution yields a *nullable*
 * classifier (`null` ⇒ no-op), so no second no-op sentinel is needed.
 */
private val InheritAmbient: FailureClassifier = { null }

/**
 * Classifies a returned failure (ADR-018/020). Runs [classifier] on [value] under fault isolation
 * ([guardFailureClassifier] — a non-fatal classifier throw, including a classifier-*thrown*
 * [CancellationException], is contained as "no failure" and routed with [FaultPhase.FAILURE_CLASSIFIER]); a
 * non-null result marks the span [SpanStatus.ERROR] and is returned to the auto-root verdict. A
 * [ReturnedFailure.CausedBy] is additionally recorded through the propagation path (canonical lineage key,
 * so a returned failure's climb collapses to one birthplace, ADR-015); [ReturnedFailure.ValueOnly] emits no
 * event. A classifier that returns a `CausedBy(CancellationException)` is a failure like any other here — only
 * the auto-root verdict treats it specially (`CANCELLED`). Recording never fails a succeeding return: a
 * non-fatal record failure is contained, a JVM-fatal one rethrown.
 */
private fun <T> classifyReturnedFailure(
    span: Span,
    classifier: (T) -> ReturnedFailure?,
    value: T,
): ReturnedFailure? {
    val failure = guardFailureClassifier(::quietFaultHook) { classifier(value) } ?: return null
    span.markStatus(SpanStatus.ERROR)
    val throwable = (failure as? ReturnedFailure.CausedBy)?.throwable ?: return failure
    try {
        span.recordPropagatedException(throwable)
    } catch (recordFailure: Throwable) {
        if (recordFailure.isFatalFault()) throw recordFailure
        // contain: telemetry recording must never turn a returning operation into a throw
    }
    return failure
}

/**
 * Reports the auto-root trace with strict-mode precedence (ADR-013): if reporting throws — e.g., strict
 * [resolvedThreadConfig] with nothing installed (ADR-011) — and an application throwable already [escaped],
 * preserve it and attach the failure as suppressed (never let a `finally` throw replace the app throwable).
 * On a normally completing block a report failure propagates as itself; a JVM-fatal failure always does.
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
 * The single auto-root boundary (ADR-013 + ADR-016): install a fresh [SpanCollector], open+register+run+end the
 * root through [executeSpan], then [reportTrace] the outcome **before** the collector context exits — so the
 * collector and any ambient [TraceConfig] are still resolved (overlaid alone; config inherited, never frozen
 * here — ADR-010).
 *
 * The report's status on a **normal return** follows the precedence chain (ADR-018): an **explicit**
 * [returnedOutcome] (`!== alwaysOkOutcome`) wins and maps the returned value (a failure-as-value consumer maps
 * a returned domain failure to `ERROR` with attached orphans, ADR-016); otherwise the **root's own classified
 * failure** (carried by [SpanCompletion] from [classifyReturnedFailure]) defaults the verdict — a
 * [ReturnedFailure.CausedBy] carrying [CancellationException] → [TraceStatus.CANCELLED], any other
 * classification → [TraceStatus.ERROR]; otherwise [TraceStatus.OK]. The **escaping** outcomes are always core's,
 * never a mapper's: an escaping [CancellationException] → [TraceStatus.CANCELLED], any other escaping throwable
 * → [TraceStatus.ERROR], the throwable rethrown unchanged. [returnedOutcome] runs **exactly once**, on a normal
 * return, and only here — never on a child, a manual-boundary root, or an identified-but-uncollected span.
 * Because it runs after [executeSpan]'s `finally` (root already ended), a mapper fault can neither flip the
 * root [SpanStatus] nor become its birthplace. Reports **after** the root has ended and **before** the
 * collector context exits, with strict/report precedence via [reportAutoRoot].
 */
private suspend fun <T> autoRootSpan(
    name: String,
    attributes: Map<String, String>,
    links: List<TraceLink>,
    scopeId: String?,
    returnedOutcome: (T) -> TraceOutcome,
    failureClassifier: (T) -> ReturnedFailure?,
    block: suspend () -> T,
): T {
    val collector = SpanCollector()
    return withContext(collector) {
        val completion = try {
            executeSpan(
                parent = null,
                collector = collector,
                name = name,
                attributes = attributes,
                links = links,
                scopeId = scopeId,
                failureClassifier = failureClassifier,
                block = block,
            )
        } catch (t: Throwable) {
            // Escaping outcome is core's, not the mapper's: CancellationException → CANCELLED, else ERROR;
            // report with the escaped throwable so a strict-mode report failure rides it as suppressed.
            reportAutoRoot(collector, if (t is CancellationException) TraceStatus.CANCELLED else TraceStatus.ERROR, t)
            throw t
        }
        // Normal return: the root has already ended (span's finally). Verdict precedence (ADR-018): an explicit
        // returnedOutcome wins; else the root's own classified failure supplies the default — a CausedBy
        // CancellationException → CANCELLED (mirror of the escaping-cancellation case above), any other
        // classification → ERROR; else OK. Read the root completion's result, never the general-purpose
        // SpanStatus or the best-effort event timeline.
        val value = completion.value
        val classified = completion.classifiedFailure
        val classifiedThrowable = (classified as? ReturnedFailure.CausedBy)?.throwable
        val outcome = when {
            returnedOutcome !== alwaysOkOutcome -> runReturnedOutcome(returnedOutcome, value)
            classifiedThrowable is CancellationException -> TraceOutcome(TraceStatus.CANCELLED)
            classified != null -> TraceOutcome(TraceStatus.ERROR)
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
