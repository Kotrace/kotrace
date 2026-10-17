package dev.kotrace.bridge

import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.internal.routeFault

/**
 * Runs [block] under fault isolation, the one rule every telemetry step follows, in the core and in an integration
 * (ADR-014/034/035): a fault in telemetry never changes what the traced code returns or throws.
 *
 * When [block] throws:
 * - a JVM-fatal error propagates;
 * - the strict-not-installed failure (ADR-011), meant to be seen in a debug build, propagates too, except that it
 *   rides [escaping], the throwable the traced code is already failing with, as suppressed when there is one, so it
 *   never replaces it;
 * - any other fault is told to the [FaultHook] as [phase], with [faultMessage], a stable symbol naming the step
 *   (`http.response.body`), never user data. A null [phase] keeps the hook out of it, for a fault that is expected
 *   or surfaces another way.
 *
 * Unless the fault propagated, [recover] then gives the result in place of [block]'s: a fallback value, or a step of
 * its own, such as adding the fault as suppressed to a throwable.
 *
 * The hook is the process-wide one (`Kotrace.installFaultHook`, ADR-037). Inline, so a hot path allocates nothing.
 */
@KotraceIntegrationApi
inline fun <R> runIsolated(
    phase: FaultPhase?,
    faultMessage: String? = null,
    escaping: Throwable? = null,
    recover: (Throwable) -> R,
    block: () -> R,
): R =
    try {
        block()
    } catch (fault: Throwable) {
        routeFault(fault, phase, faultMessage, escaping)
        recover(fault)
    }
