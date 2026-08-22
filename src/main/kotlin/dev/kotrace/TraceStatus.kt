package dev.kotrace

/**
 * The terminal verdict of a whole trace, decided at the fan-out boundary from the root span's
 * [SpanStatus] — distinct from [SpanStatus] on purpose: that is one node's live status, this is the
 * trace's completion outcome handed to a [ReportAdapter]. Values are SCREAMING_SNAKE to match
 * [SpanStatus]; the shared [OK] / [ERROR] read identically across both. The separate type holds outcomes
 * a single span cannot express: [CANCELLED] now, a future `PARTIAL` once it has a producer — neither
 * widening [SpanStatus], which stays binary.
 */
enum class TraceStatus { OK, ERROR, CANCELLED }
