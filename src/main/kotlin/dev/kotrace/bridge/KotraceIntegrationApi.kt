package dev.kotrace.bridge

/**
 * Marks the API an integration module (`kotrace-okhttp`, `kotrace-room`, a Ktor integration) builds on, as opt-in
 * (ADR-035). An application has no use for it: its own faults are its own, never the
 * [dev.kotrace.adapter.FaultHook]'s.
 *
 * `ERROR` level, so an un-opted call does not compile.
 */
@RequiresOptIn(
    message = "For integration modules only: an application reports its own faults through its own channels.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class KotraceIntegrationApi
