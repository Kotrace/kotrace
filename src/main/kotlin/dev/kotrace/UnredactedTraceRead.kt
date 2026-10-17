package dev.kotrace

/**
 * Marks an **unredacted, human-only** trace read: [List.renderTree] and the raw timeline behind it, [Span.events]
 * and [dev.kotrace.event.exception] (ADR-028). Unlike [reportTrace] and `toJson`, no policy applies: `sensitive`
 * messages and raw exception text come through. The output is for a person (a debug read, a test dump) and **must
 * never reach a sink, logger, or crash report**, as `Log.d(TAG, spans.renderTree())` would.
 *
 * `ERROR` level, so an un-opted call does not compile. Opting in makes the choice conscious; it does not make the
 * output safe to ship.
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
