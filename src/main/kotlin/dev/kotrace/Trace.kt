package dev.kotrace

import dev.kotrace.event.recordPropagatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Opens a span around [block] and closes it on return. If [block] throws, the span is marked ERROR, the throwable
 * is recorded, and it is **rethrown unchanged**; each enclosing span is marked the same way as it climbs. This is
 * instrumentation, not error handling: it only observes. The non-suspend counterpart is [startSpan].
 *
 * **Auto-root (ADR-013).** With neither a current span nor a [SpanCollector] in context, this call owns the trace:
 * it installs a collector, runs the root, and reports once with this [TraceStatus]:
 * - **thrown:** [TraceStatus.CANCELLED] for a genuine cancellation of the calling coroutine (a
 *   [CancellationException] with the coroutine no longer active), else [TraceStatus.ERROR] — a
 *   `TimeoutCancellationException` from a `withTimeout` inside [block] included (ADR-022).
 * - **returned:** an explicit [returnedOutcome] maps the value (ADR-016); else a failure [failureClassifier]
 *   found on the root gives [TraceStatus.ERROR] (or [TraceStatus.CANCELLED] for a `CausedBy` cancellation, by
 *   the same test); else [TraceStatus.OK] (ADR-018).
 *
 * Anywhere else the span is a child, or a manual-boundary root its caller reports, and [returnedOutcome] is never
 * invoked.
 *
 * **[failureClassifier]** defaults to [Kotrace.failureClassifier] and runs on every span, turning a returned
 * failure value into ERROR (ADR-018/020). [ReturnedFailure.CausedBy] also records its throwable: pass the stable
 * one the value carries, so the climb dedups to one birthplace. [ReturnedFailure.ValueOnly] only changes the
 * status. Pass `{ null }` to opt a span out. The classifier sees `T`: if the result is discarded in a
 * `Unit`-expected position, `T` is `Unit` and nothing is classified, so bind it
 * (`val r: Result<X> = span(name) { … }`).
 *
 * **[returnedOutcome]** must be pure, fast and non-suspending. A non-fatal throw from it, or from the classifier,
 * is contained and routed to the [AdapterFaultHook] ([FaultPhase.RETURNED_OUTCOME], [FaultPhase.FAILURE_CLASSIFIER]);
 * a failing mapper reports [TraceStatus.OK]. A JVM-fatal throw propagates.
 *
 * **Cancellation.** A span whose own coroutine is cancelled is marked ERROR but records no exception, so no sink
 * sees a `JobCancellationException` (ADR-022). The test is on the type: blocking code that surfaces a cancellation
 * as something else (`IOException("Canceled")`, `ClosedByInterruptException`) is recorded as a failure. Rethrow it
 * as a `CancellationException` if it should read as a cancellation.
 *
 * **In `flow { }`, never `emit` inside a span:** the block runs under `withContext`, so `emit` fails with "Flow
 * invariant is violated". Compute inside the span and emit outside it, as `spanInFlowUsage` shows.
 *
 * @sample dev.kotrace.samples.SpanSamples.spanUsage
 * @sample dev.kotrace.samples.SpanSamples.spanReturnedOutcomeUsage
 * @sample dev.kotrace.samples.SpanSamples.spanFailureClassifierUsage
 * @sample dev.kotrace.samples.SpanSamples.spanInFlowUsage
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
    // Auto-root only when both are absent (ADR-013): under a span without a collector, this is still a child.
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
    // A child or a manual-boundary root: someone else reports.
    val exit = executeSpan(
        parent = spanContext?.span,
        collector = spanCollector,
        name = name,
        attributes = attributes,
        links = links,
        scopeId = context[ScopeContext]?.scopeId,
        failureClassifier = failureClassifier,
        block = block,
    )
    return when (exit) {
        is SpanExit.Completed -> exit.value
        is SpanExit.Escaped -> throw exit.throwable
    }
}

/**
 * How a span's block exited, with the genuine-cancellation verdict decided once, where the span first saw it
 * (ADR-022). The owner reads [cancelled] rather than re-checking a Job that may have been cancelled since.
 */
private sealed interface SpanExit<out T> {
    val cancelled: Boolean

    /** A normal return. [classifiedFailure] and [cancelled] matter only to the auto-root. */
    class Completed<T>(val value: T, val classifiedFailure: ReturnedFailure?, override val cancelled: Boolean) :
        SpanExit<T>

    /** A throw: [throwable] as it escaped, recorded unless [cancelled]. The caller must rethrow it. */
    class Escaped(val throwable: Throwable, override val cancelled: Boolean) : SpanExit<Nothing>
}

/**
 * Opens, registers, runs and ends one span. A throw from [block] is recorded (unless it is a genuine
 * cancellation) and returned as [SpanExit.Escaped]; a throw while opening the span propagates.
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
): SpanExit<T> {
    val opened = createSpan(parent, name, attributes, links, scopeId)
    collector?.add(opened)
    // The per-call classifier, else the process one; null means none (ADR-018/020).
    val classifier: ((T) -> ReturnedFailure?)? =
        if (failureClassifier === InheritAmbient) Kotrace.failureClassifier() else failureClassifier
    return try {
        // Overlay the element only: passing currentCoroutineContext() would re-inject its Job.
        val value = withContext(SpanContext(opened)) { block() }
        val classifiedFailure = classifier?.let { classifyReturnedFailure(opened, it, value) }
        val causedBy = (classifiedFailure as? ReturnedFailure.CausedBy)?.throwable
        // A returned cancellation of this coroutine is not recorded, like a thrown one (ADR-022).
        val cancelled = causedBy?.isCancellationOfCurrentCoroutine() == true
        if (causedBy != null && !cancelled) recordReturnedFailure(opened, causedBy)
        SpanExit.Completed(value, classifiedFailure, cancelled)
    } catch (t: Throwable) {
        opened.markStatus(SpanStatus.ERROR)
        // A cancellation of this coroutine is not this span's failure; a CancellationException while it is still
        // active (an inner withTimeout) is (ADR-022).
        val cancelled = t.isCancellationOfCurrentCoroutine()
        if (cancelled) return SpanExit.Escaped(t, cancelled = true)
        // Every enclosing span records the climbing throwable; its lineage key collapses the climb to the
        // birthplace (ADR-015). Recording must never replace it: a non-fatal failure rides it as suppressed (ADR-013).
        try {
            opened.recordPropagatedException(t)
        } catch (recordFailure: Throwable) {
            if (recordFailure.isJvmFatal()) throw recordFailure
            t.alsoSuppress(recordFailure)
        }
        SpanExit.Escaped(t, cancelled = false)
    } finally {
        opened.markEnd(System.nanoTime())
    }
}

/**
 * [span]'s default `returnedOutcome`, identity-compared by [autoRootSpan] to mean "none given". A non-capturing
 * singleton, typed `(Any?) -> …` to stand in at any `T`.
 */
private val alwaysOkOutcome: (Any?) -> TraceOutcome = { TraceOutcome(TraceStatus.OK) }

/**
 * Whether this is a genuine cancellation of the running coroutine (ADR-022): a [CancellationException] seen while
 * the coroutine is no longer active. One seen while it is still active (an inner `withTimeout`, a cancelled
 * `Deferred`) was only thrown through it, and is a failure.
 */
private suspend fun Throwable.isCancellationOfCurrentCoroutine(): Boolean =
    this is CancellationException && !currentCoroutineContext().isActive

/**
 * [span]'s default `failureClassifier`, meaning "use [Kotrace.failureClassifier]". Identity-compared, typed to
 * stand in at any `T`.
 */
private val InheritAmbient: FailureClassifier = { null }

/** Runs [classifier] under fault isolation ([FaultPhase.FAILURE_CLASSIFIER]); a failure marks the span ERROR. */
private fun <T> classifyReturnedFailure(
    span: Span,
    classifier: (T) -> ReturnedFailure?,
    value: T,
): ReturnedFailure? {
    val failure = guardFailureClassifier(::quietFaultHook) { classifier(value) } ?: return null
    span.markStatus(SpanStatus.ERROR)
    return failure
}

/** Records a returned `CausedBy` throwable (ADR-015). A non-fatal recording failure never fails the return. */
private fun recordReturnedFailure(span: Span, throwable: Throwable) {
    try {
        span.recordPropagatedException(throwable)
    } catch (recordFailure: Throwable) {
        if (recordFailure.isJvmFatal()) throw recordFailure
    }
}

/**
 * Reports the trace. A non-fatal report failure (strict mode with nothing installed, ADR-011) rides an [escaped]
 * application throwable as suppressed, and propagates otherwise (ADR-013).
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
        if (reportFailure.isJvmFatal()) throw reportFailure
        if (escaped != null) escaped.alsoSuppress(reportFailure) else throw reportFailure
    }
}

/**
 * The auto-root (ADR-013): installs a collector, runs the root through [executeSpan], and reports once, after the
 * root has ended and before the collector context exits. The status rules are on [span].
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
        val exit = try {
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
            // Opening the root failed: no verdict was decided yet.
            reportEscaped(collector, t, cancelled = t.isCancellationOfCurrentCoroutine())
        }
        // Use the verdict the root span acted on; re-checking the Job could contradict it (ADR-022).
        val completion = when (exit) {
            is SpanExit.Completed -> exit
            is SpanExit.Escaped -> reportEscaped(collector, exit.throwable, exit.cancelled)
        }
        // Precedence (ADR-018): an explicit returnedOutcome, then the root's classified failure, then OK.
        val value = completion.value
        val classified = completion.classifiedFailure
        val outcome = when {
            returnedOutcome !== alwaysOkOutcome -> runReturnedOutcome(returnedOutcome, value)
            completion.cancelled -> TraceOutcome(TraceStatus.CANCELLED)
            classified != null -> TraceOutcome(TraceStatus.ERROR)
            else -> TraceOutcome(TraceStatus.OK)
        }
        reportAutoRoot(collector, outcome.status, escaped = null, attached = outcome.attached)
        value
    }
}

/** Reports an escaped throwable as CANCELLED or ERROR, then rethrows it. */
private fun reportEscaped(collector: SpanCollector, escaped: Throwable, cancelled: Boolean): Nothing {
    reportAutoRoot(collector, if (cancelled) TraceStatus.CANCELLED else TraceStatus.ERROR, escaped)
    throw escaped
}

/**
 * Runs [returnedOutcome] under fault isolation (ADR-016). A non-fatal throw falls back to OK, the verdict the
 * default mapper gives, so a broken mapper never invents a failure.
 */
private fun <T> runReturnedOutcome(returnedOutcome: (T) -> TraceOutcome, value: T): TraceOutcome =
    guardReturnedOutcome(::quietFaultHook) { returnedOutcome(value) } ?: TraceOutcome(TraceStatus.OK)

/**
 * The fault hook, resolved only on a fault. A non-fatal resolution failure (strict mode, ADR-011) yields null:
 * the report path surfaces it.
 */
private fun quietFaultHook(): AdapterFaultHook? =
    try {
        resolvedThreadConfig()?.faultHook
    } catch (t: Throwable) {
        if (t.isJvmFatal()) throw t
        null
    }

/**
 * Opens a span for **non-suspend** code; close it with [end]. It is a child of [parent], or roots a new trace when
 * that is null, and is registered into [collector] if there is one. The defaults read this thread's mirrors, live
 * on a coroutine's thread (the OkHttp `Call.Factory`); on any other thread pass a [TraceHandoff]'s captured
 * context instead (ADR-023).
 *
 * Opt-in ([NonSuspendTracingBridge]): unlike [span] it does not install the span into the coroutine context, so a
 * nested suspend [span] misparents. Use it only where no coroutine frame exists.
 */
@NonSuspendTracingBridge
fun startSpan(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    parent: Span? = currentThreadSpan(),
    collector: SpanCollector? = currentThreadCollector(),
    scopeId: String? = currentThreadScopeId(),
): Span {
    val opened = createSpan(parent, name, attributes, links, scopeId)
    collector?.add(opened)
    return opened
}

/**
 * Ends a span opened by [startSpan] with [status], recording [error] if given. Mirrors OTel `span.end()`.
 *
 * Only the first call counts (ADR-023): a later one can neither flip the status nor record twice. A non-fatal
 * failure to record [error] is attached to it as suppressed (ADR-013), so the caller's `throw error` still throws
 * the application's throwable.
 */
@NonSuspendTracingBridge
fun Span.end(status: SpanStatus = SpanStatus.OK, error: Throwable? = null) {
    if (!tryComplete(status, System.nanoTime())) return
    if (error == null) return
    try {
        recordPropagatedException(error)
    } catch (recordFailure: Throwable) {
        if (recordFailure.isJvmFatal()) throw recordFailure
        error.alsoSuppress(recordFailure)
    }
}

/**
 * Builds a span under [parent], or a new trace's root when it is null. Callers resolve [parent] themselves, so
 * this never reads ambient state. [links] belong to this span only, never inherited.
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
 * [bytes] random bytes as lowercase hex: 16 for a trace id, 8 for a span id. An all-zero id is invalid in W3C
 * Trace Context, so it is redrawn.
 */
internal fun hex(bytes: Int): String {
    val b = ByteArray(bytes)
    do { random.nextBytes(b) } while (b.all { it == 0.toByte() })
    // A table lookup: "%02x".format per byte was ~88% of an empty span's allocation.
    val out = CharArray(bytes * 2)
    for (i in b.indices) {
        val v = b[i].toInt() and 0xFF
        out[2 * i] = HEX_DIGITS[v ushr 4]
        out[2 * i + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()
