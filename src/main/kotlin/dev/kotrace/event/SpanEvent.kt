package dev.kotrace.event

/**
 * A single timestamped occurrence bound to one [dev.kotrace.Span] — the searchable unit this tracer fans out.
 *
 * `SpanEvent` is the umbrella. Two branches: an [AttributedEvent] (a [LogEvent] breadcrumb or a
 * [NamedEvent] analytics occurrence, both carrying free [AttributedEvent.attributes]) and an
 * [ExceptionEvent] (the birthplace throwable, carrying the raw object and no attributes).
 *
 * [atNanos] is `System.nanoTime()` at emit, used only to order events within the tree render — not a
 * wall clock.
 */
sealed interface SpanEvent {
    val atNanos: Long
}

/**
 * An event carrying free-form [attributes] — the shared shape of a [LogEvent] and a [NamedEvent]. Values
 * are static symbols/ids, never user content (EH-MON-4); severity, tag and layer all ride here rather
 * than as typed fields, so kotrace neither ranks nor interprets them — the consumer's [dev.kotrace.TracePolicy] does.
 *
 * [ExceptionEvent] is deliberately **not** an `AttributedEvent`: its `Throwable` is its data, so an
 * attributes bag on it would invite `exception.message` (a PII leak) and shadow the object a crash
 * reporter needs. A [dev.kotrace.TracePolicy] can still *cover* an exception ([dev.kotrace.TracePolicy.acceptsEvent] takes the
 * umbrella [SpanEvent]), but by default it passes — a crash is not filtered unless a policy opts to.
 */
sealed interface AttributedEvent : SpanEvent {
    val attributes: Map<String, String>
}
