package dev.kotrace.event

/**
 * One timestamped occurrence on a [dev.kotrace.Span]: an [AttributedEvent] ([LogEvent] or [NamedEvent]) or an
 * [ExceptionEvent]. [atNanos] is `System.nanoTime()`, for ordering only, not a wall clock.
 */
sealed interface SpanEvent {
    val atNanos: Long
}

/**
 * An event carrying free-form [attributes]: static symbols, never user content. Severity, tag and layer ride here,
 * interpreted only by the consumer's [dev.kotrace.TracePolicy]. [ExceptionEvent] is not one: its `Throwable` is its
 * data, and an attribute bag would invite copying `exception.message` into it.
 */
sealed interface AttributedEvent : SpanEvent {
    val attributes: Map<String, String>
}
