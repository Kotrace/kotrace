package dev.kotrace

/**
 * The phase a fault was contained in, passed to an [AdapterFaultHook]. [LIVE], [SPANLESS_LIVE] and [REPORT] are
 * fan-out phases (ADR-014). [RETURNED_OUTCOME] is the auto-root value mapper (ADR-016) and [FAILURE_CLASSIFIER]
 * the `failureClassifier` (ADR-018/020): neither is an adapter, so both report `adapter = null`, and a contained
 * fault means "no failure" (the mapper falls back to [TraceStatus.OK]).
 */
enum class FaultPhase { LIVE, SPANLESS_LIVE, REPORT, RETURNED_OUTCOME, FAILURE_CLASSIFIER }

/**
 * An **opt-in** hook (ADR-014) told of each fault kotrace contains: a throwing [TracePolicy], `onLive` or
 * `onReport`, or record construction. Without one the fault is swallowed silently, so a broken sink can never
 * crash the traced operation. Carried on [TraceConfig].
 *
 * - `adapter` is the sink that threw, or `null` when no single adapter owns the fault: a shared record
 *   construction (that event then reaches no adapter), a report's `attached` list that throws (the report carries
 *   no orphans), or the auto-root value mapper (the trace reports [TraceStatus.OK]).
 * - A non-fatal throw from [onAdapterFault] itself is swallowed; a JVM-fatal one ([isJvmFatal]) is rethrown.
 * - While the hook runs on a thread, a further fault on that thread is dropped without re-invoking it.
 * - It may run concurrently on different threads, so any state it keeps must be thread-safe.
 */
fun interface AdapterFaultHook {
    fun onAdapterFault(phase: FaultPhase, adapter: TraceAdapter?, cause: Throwable)
}

/**
 * Whether this is a JVM-fatal throwable, which kotrace never swallows (ADR-014): an [OutOfMemoryError] or
 * [StackOverflowError] ([VirtualMachineError]), a [ThreadDeath], or a [LinkageError] means the process is
 * compromised, and containing it would hide a fatal state. kotrace rethrows these past fault isolation — no hook,
 * no sibling continuation — and its OkHttp and Room integrations do the same.
 *
 * Public so code that contains its own telemetry faults (an adapter, a bridge, an integration) applies the same
 * rule: catch `Throwable`, rethrow when this is `true`, contain otherwise.
 */
fun Throwable.isJvmFatal(): Boolean =
    this is VirtualMachineError || this is ThreadDeath || this is LinkageError

/**
 * Attaches [failure] as suppressed, unless it is this same instance: self-suppression throws, which would replace
 * the application throwable (ADR-013).
 */
internal fun Throwable.alsoSuppress(failure: Throwable) {
    if (failure !== this) addSuppressed(failure)
}

/**
 * Whether a live fan-out is running on this thread (ADR-025). Internal for [liveFanOut] and for
 * [TraceHandoff.captureOrNull], which refuses to trace a sink's own I/O.
 */
internal val liveFanOutRunning: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

/**
 * Runs one live fan-out unless one is already running on this thread (ADR-025). A live adapter that logs through
 * kotrace (a Timber tree forwarding to `emitLog`) re-enters from its own `onLive`, which recursed into a
 * [StackOverflowError]. The nested event is still stored and reported, only not fanned live again. Not a fault, so
 * the hook is not told.
 */
internal inline fun liveFanOut(block: () -> Unit) {
    if (liveFanOutRunning.get()) return
    liveFanOutRunning.set(true)
    try {
        block()
    } finally {
        liveFanOutRunning.set(false)
    }
}

/** Whether a fault hook is running on this thread: the reentrancy guard. */
private val hookRunning = ThreadLocal.withInitial { false }

private fun notifyFault(phase: FaultPhase, adapter: TraceAdapter?, hook: AdapterFaultHook?, cause: Throwable) {
    if (hook == null || hookRunning.get()) return
    hookRunning.set(true)
    try {
        hook.onAdapterFault(phase, adapter, cause)
    } catch (fromHook: Throwable) {
        if (fromHook.isJvmFatal()) throw fromHook
    } finally {
        hookRunning.set(false)
    }
}

/** Runs an adapter's `onLive` or `onReport`, containing a non-fatal fault. */
internal fun guardAdapter(phase: FaultPhase, adapter: TraceAdapter?, hook: AdapterFaultHook?, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        if (t.isJvmFatal()) throw t
        notifyFault(phase, adapter, hook, t)
    }
}

/** Runs an adapter's policy; a non-fatal fault is contained and read as *reject*. */
internal fun guardPolicy(phase: FaultPhase, adapter: TraceAdapter, hook: AdapterFaultHook?, block: () -> Boolean): Boolean =
    try {
        block()
    } catch (t: Throwable) {
        if (t.isJvmFatal()) throw t
        notifyFault(phase, adapter, hook, t)
        false
    }

/** Runs work shared by every adapter; a non-fatal fault returns `null`, so no adapter receives that event. */
internal fun <R : Any> guardShared(phase: FaultPhase, hook: AdapterFaultHook?, block: () -> R): R? =
    try {
        block()
    } catch (t: Throwable) {
        if (t.isJvmFatal()) throw t
        notifyFault(phase, null, hook, t)
        null
    }

/**
 * Runs the auto-root value mapper (ADR-016); a non-fatal fault returns `null`. The [hook] is resolved only on a
 * fault, so a successful mapper never resolves config ahead of the report.
 */
internal fun <R : Any> guardReturnedOutcome(hook: () -> AdapterFaultHook?, block: () -> R): R? =
    try {
        block()
    } catch (t: Throwable) {
        if (t.isJvmFatal()) throw t
        notifyFault(FaultPhase.RETURNED_OUTCOME, null, hook(), t)
        null
    }

/**
 * Runs the `failureClassifier` (ADR-018/020); a non-fatal throw, a thrown `CancellationException` included, is
 * contained as "no failure", so classification never fails a return. The [hook] is resolved only on a fault.
 */
internal fun guardFailureClassifier(
    hook: () -> AdapterFaultHook?,
    block: () -> ReturnedFailure?,
): ReturnedFailure? =
    try {
        block()
    } catch (t: Throwable) {
        if (t.isJvmFatal()) throw t
        notifyFault(FaultPhase.FAILURE_CLASSIFIER, null, hook(), t)
        null
    }
