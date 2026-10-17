package dev.kotrace.internal

import dev.kotrace.StrictNotInstalledException
import dev.kotrace.Kotrace
import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.isJvmFatal
import dev.kotrace.bridge.TraceHandoff

/**
 * Attaches [failure] as suppressed, unless it is this same instance or already attached: self-suppression throws,
 * which would replace the application throwable (ADR-013), and a fault seen twice on its way out (once as
 * [dev.kotrace.bridge.runIsolated]'s `escaping`, once by its `recover`) is still one fault.
 */
internal fun Throwable.alsoSuppress(failure: Throwable) {
    if (failure === this || suppressed.any { it === failure }) return
    addSuppressed(failure)
}

/**
 * Whether a live fan-out is running on this thread (ADR-025). Internal for [liveFanOut] and for
 * [TraceHandoff.captureOrNull], which refuses to trace a sink's own I/O.
 */
internal val liveFanOutRunning: ThreadLocal<Boolean> = threadFlag()

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
private val hookRunning = threadFlag()

/**
 * What [dev.kotrace.bridge.runIsolated] does with a fault its block threw, kept out of the inlined body so the
 * reentrancy guard stays private:
 * - rethrows a JVM-fatal [fault];
 * - rethrows the strict-not-installed failure (ADR-011), unless it can ride [escaping] as suppressed;
 * - tells any other fault to the process-wide hook (`Kotrace.installFaultHook`, ADR-037), as [phase] with
 *   [faultMessage], unless [phase] is null. A non-fatal throw from the hook is swallowed, and while a hook runs on this thread, a
 *   further fault there is not told to it (ADR-014).
 */
@PublishedApi
internal fun routeFault(
    fault: Throwable,
    phase: FaultPhase?,
    faultMessage: String?,
    escaping: Throwable?,
) {
    if (fault.isJvmFatal()) throw fault
    if (fault is StrictNotInstalledException) {
        if (escaping == null) throw fault
        escaping.alsoSuppress(fault)
        return
    }
    if (phase == null || hookRunning.get()) return
    val hook = Kotrace.faultHook() ?: return
    hookRunning.set(true)
    try {
        hook.onFault(phase, faultMessage, fault)
    } catch (fromHook: Throwable) {
        if (fromHook.isJvmFatal()) throw fromHook
    } finally {
        hookRunning.set(false)
    }
}
