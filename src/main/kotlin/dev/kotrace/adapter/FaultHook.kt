package dev.kotrace.adapter

import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus

/**
 * The phase a fault was contained in, passed to a [FaultHook]: the coarse "where", which the hook's `faultMessage`
 * narrows to one step. [LIVE], [SPANLESS_LIVE] and [REPORT] are fan-out phases (ADR-014); [REPORT] also covers a
 * fault of kotrace's own while reporting (ADR-034). [RETURNED_OUTCOME] is the auto-root value mapper (ADR-016) and
 * [RETURN_VERDICT_CLASSIFIER] the `returnVerdictClassifier` (ADR-018/020): a contained fault there means "no failure"
 * (the mapper falls back to [TraceStatus.OK]). [SPAN_OPEN] is a fault opening a span, whose block then runs untraced
 * (ADR-034).
 *
 * [HTTP] and [DATABASE] are faults an integration contained in its own telemetry steps (ADR-035), named by domain
 * rather than by library: OkHttp and Ktor report [HTTP], Room [DATABASE].
 *
 * [INSTALL] is a fault in the `Kotrace.install` provider, which leaves the configuration with no adapter (ADR-037).
 */
enum class FaultPhase {
    LIVE, SPANLESS_LIVE, REPORT, RETURNED_OUTCOME, RETURN_VERDICT_CLASSIFIER, SPAN_OPEN, HTTP, DATABASE, INSTALL,
}

/**
 * An **opt-in** hook (ADR-014) told of each fault kotrace contains: a throwing [TracePolicy], `onLive` or
 * `onReport`, record construction, the auto-root value mapper or the `returnVerdictClassifier` ([FaultPhase] says
 * which). The hook only observes: with or without one, the fault is contained, so a broken sink can never
 * crash the traced operation; without one, nothing reports it. One per process, installed once with
 * `Kotrace.installFaultHook`, apart from any [TraceConfig] (ADR-037), so it hears of faults in every flow, including
 * one with no configuration and the `Kotrace.install` provider itself.
 *
 * - `faultMessage` names the step that failed with a stable symbol, such as `live.deliver` (an adapter's `onLive`),
 *   `live.policy`, `report.deliver` or `http.response.body` (ADR-036). It survives code shrinking, unlike the
 *   cause's stack trace, and is never user data. It is `null` when the step has nothing to add to [FaultPhase].
 *   It does not say which adapter failed: an adapter that tracks its own health catches its own faults.
 * - A non-fatal throw from [onFault] itself is swallowed; a JVM-fatal one ([isJvmFatal]) is rethrown.
 * - While the hook runs on a thread, a further fault on that thread is dropped without re-invoking it.
 * - It may run concurrently on different threads, so any state it keeps must be thread-safe.
 */
fun interface FaultHook {
    fun onFault(phase: FaultPhase, faultMessage: String?, cause: Throwable)
}

/**
 * Whether this is a JVM-fatal throwable, which kotrace never swallows (ADR-014): an [OutOfMemoryError] or
 * [StackOverflowError] ([VirtualMachineError]), a [ThreadDeath], or a [LinkageError] means the process is
 * compromised, and containing it would hide a fatal state. kotrace rethrows these past fault isolation (no hook,
 * no sibling continuation), and its OkHttp and Room integrations do the same.
 *
 * Public so code that contains its own telemetry faults (an adapter, a bridge, an integration) applies the same
 * rule: catch `Throwable`, rethrow when this is `true`, contain otherwise.
 */
fun Throwable.isJvmFatal(): Boolean =
    this is VirtualMachineError || this is ThreadDeath || this is LinkageError
