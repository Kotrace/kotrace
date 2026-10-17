package dev.kotrace

/**
 * Marks an **unredacted, human-only** trace read — [List.renderTree], and the raw timeline it is built from:
 * [Span.events] and [dev.kotrace.event.exception] (ADR-028). Reading a span's events hands out exactly what
 * `renderTree` renders — `sensitive` messages and raw throwables — so the same contract applies to both.
 *
 * Unlike the machine path ([reportTrace] / `toJson`, PII-safe by construction and policy-gated), the marked
 * API renders *everything*: `sensitive` [dev.kotrace.event.LogEvent] messages, [dev.kotrace.event.NamedEvent]
 * attributes, and the raw `throwable.message` at the birthplace. Its output is for a person reading a trace
 * — an interactive debug read, a test dump — and **must never reach a sink, logger, or crash report**. Piping
 * it there (`Log.d(TAG, spans.renderTree())`) ships the same PII the machine path exists to withhold.
 *
 * `ERROR` level: an un-opted call does not compile. A legitimate human-read site opts in with
 * `@OptIn(UnredactedTraceRead::class)` and a comment; the marker turns an accidental ship into a build
 * failure pointing here. Opting in does not make the output safe to route off-device — it only makes the
 * "human-only" contract a conscious choice rather than a silent one.
 */
@RequiresOptIn(
    message = "An unredacted human debug read — it exposes sensitive log messages and raw exception text. " +
        "Never route what it returns to a sink, logger, or crash report. For machine egress use reportTrace / " +
        "toJson instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class UnredactedTraceRead
