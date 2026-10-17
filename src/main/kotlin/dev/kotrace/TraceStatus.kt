package dev.kotrace

/**
 * The terminal verdict of a whole trace, handed to a [ReportAdapter] at [reportTrace]. It is the caller's
 * to supply; the auto-root boundary (ADR-013) derives it from the block's **completion** — a normal return
 * is [OK], a genuine cancellation of the root's coroutine (an escaping `CancellationException` with that
 * coroutine no longer active) is [CANCELLED], any other escaping throwable is [ERROR] — a
 * `TimeoutCancellationException` from a `withTimeout` inside the root included, since a timeout is a failure of
 * the operation (ADR-022) — not
 * from the root [SpanStatus], which is binary ([OK]/[ERROR]) and cannot by itself distinguish [CANCELLED]
 * from [ERROR]. Distinct from [SpanStatus] on purpose: that is one node's live status, this is the trace's
 * completion outcome. Values are SCREAMING_SNAKE to match [SpanStatus]; the shared [OK] / [ERROR] read
 * identically across both. The separate type holds outcomes a single span cannot express: [CANCELLED] now,
 * a future `PARTIAL` once it has a producer — neither widening [SpanStatus].
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
