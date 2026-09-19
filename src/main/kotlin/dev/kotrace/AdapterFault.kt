package dev.kotrace

/**
 * The phase a fault was contained in — passed to an [AdapterFaultHook] so a consumer can tell a live
 * breadcrumb failure from a report failure. [LIVE], [SPANLESS_LIVE] and [REPORT] are fan-out phases (ADR-014);
 * [RETURNED_OUTCOME] is the auto-root value mapper (ADR-016) and [FAILURE_DETECTOR] the per-span
 * `failureDetector` (ADR-018) — both are report/detection-time inputs, not adapters, so both are reported with
 * `adapter = null`, and when contained the detection is treated as "no failure" (and the mapper falls back to
 * [TraceStatus.OK]).
 */
enum class FaultPhase { LIVE, SPANLESS_LIVE, REPORT, RETURNED_OUTCOME, FAILURE_DETECTOR }

/**
 * An **opt-in** diagnostic hook (ADR-014) invoked when a fan-out phase contains a fault — a throwing
 * [TracePolicy], a throwing `onLive`/`onReport`, or a throwing shared record construction. It exists so a
 * consumer can *observe* suppressed faults (log or count them); it is **not** required, and kotrace's
 * default (no hook) swallows the fault silently so a broken sink can never crash the traced operation.
 *
 * Carried on [TraceConfig]. Contract:
 * - `adapter` is the sink whose region threw, or `null` for a fault with no single adapter owner: a shared,
 *   per-event record-construction fault (the event is then not delivered to any accepting adapter), or a
 *   throwing auto-root value mapper ([FaultPhase.RETURNED_OUTCOME], ADR-016 — the trace then reports
 *   [TraceStatus.OK] with no attachments).
 * - A **non-fatal** throw from [onAdapterFault] itself is swallowed; a JVM-fatal one
 *   ([VirtualMachineError]/[ThreadDeath]/[LinkageError]) is rethrown.
 * - **Per-thread reentrancy**: while a hook is running on a thread, a further fault on that thread is dropped
 *   without re-invoking it. Fan-out from within a hook is allowed, but any nested fault is not re-notified.
 *   A hook must not rely on emitting through kotrace.
 * - **Concurrency**: the reentrancy guard is *per-thread*, so [onAdapterFault] may run **concurrently on
 *   different threads** (parallel traced flows fanning out at once). An implementation that accumulates state
 *   (a counter, a log buffer) must be thread-safe.
 */
fun interface AdapterFaultHook {
    fun onAdapterFault(phase: FaultPhase, adapter: TraceAdapter?, cause: Throwable)
}

/**
 * JVM-fatal throwables kotrace never swallows (ADR-014): they are rethrown past fault isolation — no hook, no
 * sibling continuation. An [OutOfMemoryError]/[StackOverflowError] ([VirtualMachineError]), a [ThreadDeath],
 * or a [LinkageError] means the process is compromised; containing it would hide a fatal state.
 */
internal fun Throwable.isFatalFault(): Boolean =
    this is VirtualMachineError || this is ThreadDeath || this is LinkageError

/**
 * Attaches [failure] as a suppressed exception on this throwable — unless it is the **same instance**, since
 * `addSuppressed(this)` throws `IllegalArgumentException` ("Self-suppression not permitted"), which would
 * itself replace the application throwable (ADR-013). Used so a telemetry/strict failure never displaces the
 * throwable it should ride alongside.
 */
internal fun Throwable.alsoSuppress(failure: Throwable) {
    if (failure !== this) addSuppressed(failure)
}

/** Per-thread "a fault hook is running here" flag — the reentrancy guard (never a process-global boolean). */
private val hookRunning = ThreadLocal.withInitial { false }

/** Routes a contained fault to [hook], per-thread-reentrancy-guarded; a non-fatal throw from the hook is swallowed. */
private fun notifyFault(phase: FaultPhase, adapter: TraceAdapter?, hook: AdapterFaultHook?, cause: Throwable) {
    if (hook == null || hookRunning.get()) return
    hookRunning.set(true)
    try {
        hook.onAdapterFault(phase, adapter, cause)
    } catch (fromHook: Throwable) {
        if (fromHook.isFatalFault()) throw fromHook
        // a non-fatal throw from the hook is swallowed — the hook can never propagate
    } finally {
        hookRunning.set(false)
    }
}

/** Runs a Unit adapter region (`onLive`/`onReport`): a non-fatal fault is contained + routed to [hook], a fatal rethrown. */
internal fun guardAdapter(phase: FaultPhase, adapter: TraceAdapter?, hook: AdapterFaultHook?, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        if (t.isFatalFault()) throw t
        notifyFault(phase, adapter, hook, t)
    }
}

/** Runs an adapter's boolean policy region: a non-fatal fault is contained (treated as *reject*) + routed; a fatal rethrown. */
internal fun guardPolicy(phase: FaultPhase, adapter: TraceAdapter, hook: AdapterFaultHook?, block: () -> Boolean): Boolean =
    try {
        block()
    } catch (t: Throwable) {
        if (t.isFatalFault()) throw t
        notifyFault(phase, adapter, hook, t)
        false
    }

/**
 * Runs the shared, per-event record construction: a non-fatal fault returns `null` (delivery of that one
 * event is aborted for **all** accepting adapters) and is routed with `adapter = null`; a fatal is rethrown.
 */
internal fun <R : Any> guardShared(phase: FaultPhase, hook: AdapterFaultHook?, block: () -> R): R? =
    try {
        block()
    } catch (t: Throwable) {
        if (t.isFatalFault()) throw t
        notifyFault(phase, null, hook, t)
        null
    }

/**
 * Runs the auto-root return-value mapper (ADR-016): a non-fatal fault returns `null` (the caller then reports
 * [TraceStatus.OK]) and is routed with `adapter = null`; a fatal is rethrown. Unlike [guardShared] the [hook]
 * is a **supplier resolved only on a fault**, so a successful mapper never resolves config ahead of the
 * report — preserving the ADR-016 `markEnd → mapper → report/config-resolution` ordering.
 */
internal fun <R : Any> guardReturnedOutcome(hook: () -> AdapterFaultHook?, block: () -> R): R? =
    try {
        block()
    } catch (t: Throwable) {
        if (t.isFatalFault()) throw t
        notifyFault(FaultPhase.RETURNED_OUTCOME, null, hook(), t)
        null
    }

/**
 * Runs the per-span `failureDetector` (ADR-018) on a normal return: a **non-fatal** throw — including a
 * detector-thrown [kotlinx.coroutines.CancellationException], which is a config fault here, distinct from a
 * detector that *returns* one — is contained (returns `null`, i.e. "no failure detected") and routed with
 * [FaultPhase.FAILURE_DETECTOR] and `adapter = null`; a JVM-fatal fault is rethrown. Telemetry classification
 * must never turn a succeeding return into a failure. The [hook] is a **supplier resolved only on a fault**, so
 * a successful detector never resolves config on the hot path. A `null` result is ambiguous by design —
 * "detector said not-a-failure" and "detector faulted and was contained" both mean *do not record*.
 */
internal fun guardDetector(hook: () -> AdapterFaultHook?, block: () -> Throwable?): Throwable? =
    try {
        block()
    } catch (t: Throwable) {
        if (t.isFatalFault()) throw t
        notifyFault(FaultPhase.FAILURE_DETECTOR, null, hook(), t)
        null
    }
