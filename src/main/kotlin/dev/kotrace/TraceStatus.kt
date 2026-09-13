package dev.kotrace

/**
 * The terminal verdict of a whole trace, handed to a [ReportAdapter] at [reportTrace]. It is the caller's
 * to supply; the auto-root boundary (ADR-013) derives it from the block's **completion** — a normal return
 * is [OK], an escaping `CancellationException` is [CANCELLED], any other escaping throwable is [ERROR] — not
 * from the root [SpanStatus], which is binary ([OK]/[ERROR]) and cannot by itself distinguish [CANCELLED]
 * from [ERROR]. Distinct from [SpanStatus] on purpose: that is one node's live status, this is the trace's
 * completion outcome. Values are SCREAMING_SNAKE to match [SpanStatus]; the shared [OK] / [ERROR] read
 * identically across both. The separate type holds outcomes a single span cannot express: [CANCELLED] now,
 * a future `PARTIAL` once it has a producer — neither widening [SpanStatus].
 */
enum class TraceStatus { OK, ERROR, CANCELLED }
