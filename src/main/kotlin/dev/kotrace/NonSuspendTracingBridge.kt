package dev.kotrace

/**
 * Marks the non-suspend tracing bridge, [startSpan], [Span.end] and [TraceHandoff], as opt-in (ADR-003, ADR-023).
 * It is only for non-suspend code: on a coroutine's thread (the OkHttp `Call.Factory`), or on another thread through
 * a [TraceHandoff] (the OkHttp interceptor). [startSpan] does not install its span into the coroutine context, so
 * spans nested in suspend code under it misparent silently; in suspend code use [span] `{ }`.
 *
 * `ERROR` level, so an un-opted call does not compile.
 */
@RequiresOptIn(
    message = "startSpan/end is the non-suspend tracing bridge. In suspend code use span { } instead — " +
        "startSpan does not install its span as the ambient parent, so nested spans mis-parent silently.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class NonSuspendTracingBridge
