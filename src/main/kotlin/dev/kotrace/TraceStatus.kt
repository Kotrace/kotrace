package dev.kotrace

/**
 * The verdict of a whole trace, handed to a [ReportAdapter] by [reportTrace]. The auto-root (ADR-013) derives it
 * from how the block completed: a normal return is [OK], a genuine cancellation of the root's coroutine is
 * [CANCELLED], and any other throwable is [ERROR], a timeout inside the root included (ADR-022). It is separate
 * from the binary [SpanStatus] because a trace has outcomes a single span cannot express.
 *
 * **Where the timeout sits decides the verdict.** `span("load") { withTimeout(5_000) { … } }` is [ERROR]: the
 * timeout fails the operation inside the span, and the `TimeoutCancellationException` is reported as its
 * birthplace. `withTimeout(5_000) { span("load") { … } }` — a timeout the *caller* wraps around a top-level
 * span — cancels that span's coroutine, so the trace is [CANCELLED] with no exception record (ADR-022). A failure
 * sink that must see timeouts either keeps [CANCELLED] traces too or puts the timeout inside the span.
 *
 * A [CANCELLED] report is not always free of failures: when the caller cancels while a nested span's failure is
 * still on its way up — a child's inner timeout, say — the child's exception record stays in the report (ADR-022).
 */
enum class TraceStatus { OK, ERROR, CANCELLED }
