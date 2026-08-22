package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.currentThreadConfig

/**
 * A named, structured occurrence on a span ([name] + [attributes]) — the OTel `event.name` shape, e.g. a
 * product-analytics event. kotrace stays generic: it never names "analytics"; the meaning lives in the
 * consumer's adapter (consent, destination). [attributes] values are static symbols/ids, never user
 * content (EH-MON-4) unless the consuming adapter is explicitly gated for it.
 */
class NamedEvent(
    val name: String,
    override val attributes: Map<String, String>,
    override val atNanos: Long,
) : AttributedEvent

/** A flattened [NamedEvent]: identity + a named, structured occurrence. */
data class NamedRecord(
    override val traceId: String,
    override val spanId: String,
    override val parentId: String?,
    override val operation: String,
    override val atNanos: Long,
    override val info: Map<String, String>,
    val name: String,
    override val attributes: Map<String, String>,
) : AttributedRecord

/**
 * The named-event verb — adds a named, structured event ([NamedEvent]) to this span, the OTel `addEvent` /
 * `event.name` shape (name + attributes) a consumer's analytics adapter consumes live. Named `addNamed`, not
 * `addEvent`: it pairs with [addException] (verb + kind), and every verb adds *an* event, so the umbrella
 * word would mislead. A named event is live-only (not reportable) and, like every verb, stored
 * unconditionally then filtered at fan-out (ADR-002); kotrace stays generic — "analytics" is a consumer
 * concern realised in a [dev.kotrace.LiveAdapter], not here.
 *
 * Called on a span in hand: a suspend caller reaches it via `currentSpan()?.addNamed(...)`; a non-suspend
 * bridge holding the span calls it directly, or resolves via `currentThreadSpan()`. Config (live adapters)
 * is read through the [dev.kotrace.currentThreadConfig] mirror — this verb is non-suspend, so it cannot read the context directly.
 */
fun Span.addNamed(name: String, attributes: Map<String, String> = emptyMap()) {
    emit(NamedEvent(name, attributes, System.nanoTime()), currentThreadConfig())
}
