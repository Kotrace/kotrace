package dev.kotrace

/**
 * Marks the non-suspend tracing bridge — [startSpan] and [Span.end] — as an opt-in API (ADR-003).
 *
 * These exist only for code with no `coroutineContext` handle running on a coroutine's thread (the OkHttp
 * `Call.Factory`). Unlike [span], [startSpan] does **not** install its span into the coroutine context, so a
 * nested suspend [span]/[currentSpan] reads the *old* ambient span as parent and the manual span's children
 * mis-parent silently. In suspend code use [span] `{ }` instead.
 *
 * `ERROR` level: an un-opted call does not compile. The one legitimate site opts in with
 * `@OptIn(NonSuspendTracingBridge::class)` and a comment; every accidental suspend-context use is a build
 * failure pointing here.
 */
@RequiresOptIn(
    message = "startSpan/end is the non-suspend tracing bridge. In suspend code use span { } instead — " +
        "startSpan does not install its span as the ambient parent, so nested spans mis-parent silently.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class NonSuspendTracingBridge
