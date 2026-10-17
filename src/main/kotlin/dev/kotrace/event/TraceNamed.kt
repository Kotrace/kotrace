package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceLink
import dev.kotrace.resolvedThreadConfig

/**
 * A named occurrence on a span ([name] plus [attributes]), such as a product-analytics event; its meaning lives in
 * the consumer's adapter. [attributes] values are static symbols, never user content.
 */
class NamedEvent(
    val name: String,
    attributes: Map<String, String>,
    override val atNanos: Long,
) : AttributedEvent {
    /** Copied, so a caller mutating its map later cannot make live and report disagree. */
    override val attributes: Map<String, String> = attributes.toMap()
}

/** A flattened [NamedEvent]: identity plus the named occurrence. */
data class NamedRecord(
    override val traceId: String?,
    override val spanId: String?,
    override val parentId: String?,
    override val operation: String?,
    override val atNanos: Long,
    override val scopeId: String? = null,
    override val spanAttributes: Map<String, String>,
    override val links: List<TraceLink>,
    val name: String,
    override val attributes: Map<String, String>,
) : AttributedRecord

/**
 * Adds a [NamedEvent] to this span, for a [dev.kotrace.adapter.LiveAdapter] such as an analytics sink. A named event is
 * live-only: it is never part of a report.
 */
fun Span.addNamed(name: String, attributes: Map<String, String> = emptyMap()) {
    emit(NamedEvent(name, attributes, System.nanoTime()), resolvedThreadConfig())
}
