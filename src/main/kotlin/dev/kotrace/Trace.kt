package dev.kotrace

import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.bridge.runIsolated
import dev.kotrace.bridge.startSpan
import dev.kotrace.event.recordPropagatedException
import dev.kotrace.internal.alsoSuppress
import dev.kotrace.internal.createSpan
import dev.kotrace.report.reportTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Opens a span around [block] and closes it on return. If [block] throws, the span is marked ERROR, the throwable
 * is recorded, and it is **rethrown unchanged**; each enclosing span is marked the same way as it climbs. This is
 * instrumentation, not error handling: it only observes. The non-suspend counterpart is [startSpan].
 *
 * **What the span carries.** [traits] are its fixed filter dimensions, the only per-span input `acceptsSpan`
 * reads. [attributes] seed [Span.attributes], which ride every record as `spanAttributes` from the first event on;
 * stamp a value known only later with [Span.putAttribute]. Both hold static symbols only (ADR-001/032). [links]
 * point at other traces ([TraceLink], ADR-009) and belong to this span only.
 *
 * **Returned failures.** [returnVerdictClassifier] defaults to [Kotrace.returnVerdictClassifier] and runs on every
 * span, turning a returned failure value into ERROR (ADR-018/020). A [ReturnVerdict.Failure] with a throwable also
 * records it: pass the stable one the value carries, so the climb dedups to one birthplace. With a null throwable it
 * only changes the status. Pass `{ ReturnVerdict.Success }` to opt a span out. The classifier sees `T`: if the
 * result is discarded in a `Unit`-expected position, `T` is `Unit` and nothing is classified, so bind it
 * (`val r: Result<X> = span(name) { … }`).
 *
 * **Auto-root (ADR-013).** With neither a current span nor a [SpanCollector] in context, this call owns the trace:
 * it installs a collector, runs the root, and reports once with this [TraceStatus]:
 * - **thrown:** [TraceStatus.CANCELLED] for a genuine cancellation of the calling coroutine (a
 *   [CancellationException] with the coroutine no longer active), else [TraceStatus.ERROR], a
 *   `TimeoutCancellationException` from a `withTimeout` inside [block] included (ADR-022).
 * - **returned:** an explicit [returnedOutcome] maps the value (ADR-016); else a [ReturnVerdict.Failure] from the
 *   root's classifier gives [TraceStatus.ERROR], or [TraceStatus.CANCELLED] when its throwable is a genuine
 *   cancellation by the same test; else [TraceStatus.OK] (ADR-018).
 *
 * Anywhere else the span is a child, or a manual-boundary root its caller reports, and [returnedOutcome] is never
 * invoked.
 *
 * **Faults in the callbacks.** [returnedOutcome] must be pure, fast and non-suspending. A non-fatal throw from it,
 * or from the classifier, is contained and routed to the [FaultHook] ([FaultPhase.RETURNED_OUTCOME],
 * [FaultPhase.RETURN_VERDICT_CLASSIFIER]): a failing mapper reports [TraceStatus.OK], a failing classifier reads as
 * [ReturnVerdict.Success]. A JVM-fatal throw propagates.
 *
 * **kotrace's own faults never change the outcome (ADR-034).** [block] returns or throws exactly as it would
 * untraced. A fault opening the span is routed to the [FaultHook] ([FaultPhase.SPAN_OPEN]) and [block] runs
 * untraced; a fault while reporting goes there as [FaultPhase.REPORT]. The one exception is
 * [Kotrace.strictWhenNotInstalled]: in a debug build that armed it, a missing install throws on purpose (ADR-011).
 * A JVM-fatal error always propagates.
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
 * @sample dev.kotrace.samples.SpanSamples.spanReturnVerdictClassifierUsage
 * @sample dev.kotrace.samples.SpanSamples.spanInFlowUsage
 */
suspend fun <T> span(
    name: String,
    traits: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    attributes: Map<String, String> = emptyMap(),
    returnedOutcome: (T) -> TraceOutcome = alwaysOkOutcome,
    returnVerdictClassifier: ReturnVerdictClassifier<T> = InheritAmbient,
    block: suspend () -> T,
): T {
    val context = currentCoroutineContext()
    // Auto-root only when both are absent (ADR-013): under a span without a collector, this is still a child.
    val spanCollector = context[SpanCollector]
    val spanContext = context[SpanContext]
    if (spanContext == null && spanCollector == null) {
        return autoRootSpan(
            name,
            traits,
            links,
            attributes,
            context[ScopeContext]?.scopeId,
            returnedOutcome,
            returnVerdictClassifier,
            block,
        )
    }
    // A child or a manual-boundary root: someone else reports.
    val exit = executeSpan(
        parent = spanContext?.span,
        collector = spanCollector,
        name = name,
        traits = traits,
        links = links,
        attributes = attributes,
        scopeId = context[ScopeContext]?.scopeId,
        returnVerdictClassifier = returnVerdictClassifier,
        block = block,
    )
    return when (exit) {
        is SpanExit.Completed -> exit.value
        is SpanExit.Escaped -> throw exit.throwable
    }
}

/**
 * The verdict of a whole trace, handed to a [ReportAdapter] by [reportTrace]. The auto-root (ADR-013) derives it
 * from how the block completed: a normal return is [OK], a genuine cancellation of the root's coroutine is
 * [CANCELLED], and any other throwable is [ERROR], a timeout inside the root included (ADR-022). It is separate
 * from the binary [SpanStatus] because a trace has outcomes a single span cannot express.
 *
 * **Where the timeout sits decides the verdict.** `span("load") { withTimeout(5_000) { … } }` is [ERROR]: the
 * timeout fails the operation inside the span, and the `TimeoutCancellationException` is reported as its
 * birthplace. `withTimeout(5_000) { span("load") { … } }` (a timeout the *caller* wraps around a top-level
 * span) cancels that span's coroutine, so the trace is [CANCELLED] with no exception record (ADR-022). A failure
 * sink that must see timeouts either keeps [CANCELLED] traces too or puts the timeout inside the span.
 *
 * A [CANCELLED] report is not always free of failures: when the caller cancels while a nested span's failure is
 * still on its way up (a child's inner timeout, say), the child's exception record stays in the report (ADR-022).
 */
enum class TraceStatus { OK, ERROR, CANCELLED }

/**
 * What a `returnedOutcome` mapper reports for a normally returned value at the auto-root (ADR-016): any
 * [TraceStatus], taken as given, plus orphan failures to attach.
 *
 * [attached] is **only for trace-level orphan failures**, throwables that are no span's birthplace (a saga's
 * suppressed rollbacks, ADR-012). A throwable a span already records reaches the report through the tree;
 * attaching it too would report it twice.
 */
data class TraceOutcome(val status: TraceStatus, val attached: List<Throwable> = emptyList())

/**
 * [span]'s default `returnedOutcome`, identity-compared by [autoRootSpan] to mean "none given". A non-capturing
 * singleton, typed `(Any?) -> …` to stand in at any `T`.
 */
private val alwaysOkOutcome: (Any?) -> TraceOutcome = { TraceOutcome(TraceStatus.OK) }

/**
 * [span]'s default `returnVerdictClassifier`, meaning "use [Kotrace.returnVerdictClassifier]". Identity-compared,
 * typed to stand in at any `T`.
 */
private val InheritAmbient: ReturnVerdictClassifier<Any?> = { ReturnVerdict.Success }

/**
 * The auto-root (ADR-013): installs a collector, runs the root through [executeSpan], and reports once, after the
 * root has ended and before the collector context exits. The status rules are on [span].
 */
private suspend fun <T> autoRootSpan(
    name: String,
    traits: Map<String, String>,
    links: List<TraceLink>,
    attributes: Map<String, String>,
    scopeId: String?,
    returnedOutcome: (T) -> TraceOutcome,
    returnVerdictClassifier: ReturnVerdictClassifier<T>,
    block: suspend () -> T,
): T {
    val collector = SpanCollector()
    return withContext(collector) {
        // Throws only a JVM-fatal error: a fault opening the root runs the block untraced (ADR-034).
        val exit = executeSpan(
            parent = null,
            collector = collector,
            name = name,
            traits = traits,
            links = links,
            attributes = attributes,
            scopeId = scopeId,
            returnVerdictClassifier = returnVerdictClassifier,
            block = block,
        )
        // Use the verdict the root span acted on; re-checking the Job could contradict it (ADR-022).
        val completion = when (exit) {
            is SpanExit.Completed -> exit
            is SpanExit.Escaped -> {
                val status = if (exit.cancelled) TraceStatus.CANCELLED else TraceStatus.ERROR
                reportAutoRoot(collector, status, escaped = exit.throwable)
                throw exit.throwable
            }
        }
        // Precedence (ADR-018): an explicit returnedOutcome, then a returned cancellation, then the root's Failure
        // verdict, then OK.
        val value = completion.value
        val outcome = when {
            // Fault-isolated (ADR-016): a throwing mapper falls back to OK, the default mapper's verdict, so it never
            // invents a failure.
            returnedOutcome !== alwaysOkOutcome ->
                runIsolated(FaultPhase.RETURNED_OUTCOME, "span.returned_outcome", recover = { TraceOutcome(TraceStatus.OK) }) {
                    returnedOutcome(value)
                }
            completion.cancelled -> TraceOutcome(TraceStatus.CANCELLED)
            completion.verdict is ReturnVerdict.Failure -> TraceOutcome(TraceStatus.ERROR)
            else -> TraceOutcome(TraceStatus.OK)
        }
        reportAutoRoot(collector, outcome.status, escaped = null, attached = outcome.attached)
        value
    }
}

/**
 * Reports the trace without letting a fault of kotrace's own change the traced outcome (ADR-034): it goes to the
 * [FaultHook] as [FaultPhase.REPORT]. The strict-not-installed failure (ADR-011) is meant to be seen instead: it
 * propagates after a normal return, and rides an [escaped] application throwable as suppressed (ADR-013).
 */
private fun reportAutoRoot(
    collector: SpanCollector,
    status: TraceStatus,
    escaped: Throwable?,
    attached: List<Throwable> = emptyList(),
) {
    runIsolated(FaultPhase.REPORT, "report.trace", escaping = escaped, recover = {}) { collector.reportTrace(status, attached) }
}

/**
 * How a span's block exited, with the genuine-cancellation verdict decided once, where the span first saw it
 * (ADR-022). The owner reads [cancelled] rather than re-checking a Job that may have been cancelled since.
 */
private sealed interface SpanExit<out T> {
    val cancelled: Boolean

    /** A normal return and the classifier's [verdict] on it. [verdict] and [cancelled] matter only to the auto-root. */
    class Completed<T>(val value: T, val verdict: ReturnVerdict, override val cancelled: Boolean) :
        SpanExit<T>

    /**
     * A throw: [throwable] as it escaped, recorded on the span unless [cancelled] or the span never opened. The
     * caller must rethrow it.
     */
    class Escaped(val throwable: Throwable, override val cancelled: Boolean) : SpanExit<Nothing>
}

/**
 * Opens, registers, runs and ends one span, for both a child and the auto-root. A normal return is classified and
 * returned as [SpanExit.Completed]; a throw from [block] is recorded (unless it is a genuine cancellation) and
 * returned as [SpanExit.Escaped]. The span always ends. A fault opening it is contained ([FaultPhase.SPAN_OPEN]) and
 * [block] runs untraced, so this throws only a JVM-fatal error.
 */
private suspend fun <T> executeSpan(
    parent: Span?,
    collector: SpanCollector?,
    name: String,
    traits: Map<String, String>,
    links: List<TraceLink>,
    attributes: Map<String, String>,
    scopeId: String?,
    returnVerdictClassifier: ReturnVerdictClassifier<T>,
    block: suspend () -> T,
): SpanExit<T> {
    // A fault opening the span is kotrace's, never the traced code's (ADR-034): it goes to the FaultHook and the block
    // runs untraced, unclassified. Spans the block opens then parent under this span's parent, or become roots of the
    // collector.
    val opened = runIsolated(FaultPhase.SPAN_OPEN, "span.open", recover = { null }) {
        createSpan(parent, name, traits, links, attributes, scopeId).also { collector?.add(it) }
    }
    if (opened == null) {
        return try {
            SpanExit.Completed(block(), ReturnVerdict.Success, cancelled = false)
        } catch (t: Throwable) {
            SpanExit.Escaped(t, cancelled = t.isCancellationOfCurrentCoroutine())
        }
    }
    // The per-call classifier, else the process one; null means none (ADR-018/020).
    val classifier: ReturnVerdictClassifier<T>? =
        if (returnVerdictClassifier === InheritAmbient) Kotrace.returnVerdictClassifier() else returnVerdictClassifier
    return try {
        // Overlay the element only: passing currentCoroutineContext() would re-inject its Job.
        val value = withContext(SpanContext(opened)) { block() }
        // No classifier reads as Success. Fault-isolated (ADR-018/020): a non-fatal throw, a thrown
        // CancellationException included, reads as Success too, so classification never fails a return.
        val verdict = if (classifier == null) {
            ReturnVerdict.Success
        } else {
            runIsolated(FaultPhase.RETURN_VERDICT_CLASSIFIER, "span.classify", recover = { ReturnVerdict.Success }) { classifier(value) }
        }
        if (verdict is ReturnVerdict.Failure) opened.markStatus(SpanStatus.ERROR)
        val returnedThrowable = (verdict as? ReturnVerdict.Failure)?.throwable
        // A returned cancellation of this coroutine is not recorded, like a thrown one (ADR-022).
        val cancelled = returnedThrowable?.isCancellationOfCurrentCoroutine() == true
        if (returnedThrowable != null && !cancelled) {
            // Recorded with its lineage key, like a thrown failure (ADR-015). A recording fault never fails the return.
            runIsolated(phase = null, recover = {}) { opened.recordPropagatedException(returnedThrowable) }
        }
        SpanExit.Completed(value, verdict, cancelled)
    } catch (t: Throwable) {
        opened.markStatus(SpanStatus.ERROR)
        // A cancellation of this coroutine is not this span's failure; a CancellationException while it is still
        // active (an inner withTimeout) is (ADR-022).
        val cancelled = t.isCancellationOfCurrentCoroutine()
        if (cancelled) return SpanExit.Escaped(t, cancelled = true)
        // Every enclosing span records the climbing throwable; its lineage key collapses the climb to the
        // birthplace (ADR-015). Recording must never replace it: a non-fatal failure rides it as suppressed (ADR-013).
        runIsolated(phase = null, escaping = t, recover = { t.alsoSuppress(it) }) {
            opened.recordPropagatedException(t)
        }
        SpanExit.Escaped(t, cancelled = false)
    } finally {
        opened.markEnd(System.nanoTime())
    }
}

/**
 * Whether this is a genuine cancellation of the running coroutine (ADR-022): a [CancellationException] seen while
 * the coroutine is no longer active. One seen while it is still active (an inner `withTimeout`, a cancelled
 * `Deferred`) was only thrown through it, and is a failure.
 */
private suspend fun Throwable.isCancellationOfCurrentCoroutine(): Boolean =
    this is CancellationException && !currentCoroutineContext().isActive

