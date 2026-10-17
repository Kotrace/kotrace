package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceLink
import dev.kotrace.resolvedThreadConfig

/**
 * A breadcrumb on a span: [message] plus [attributes]. Severity, if any, is an attribute the consumer sets and
 * interprets.
 *
 * [message] is built only when an adapter accepts the event (ADR-002). A non-sensitive message is a **static
 * symbol only**, since it can reach a crash report. [sensitive] marks a message that carries user data (a
 * captured body); [dev.kotrace.TracePolicy.acceptsSensitive] decides where it goes, dropped by default. To surface
 * one safe field of a body, log it separately, never by un-marking the body.
 */
class LogEvent(
    attributes: Map<String, String>,
    messageProvider: () -> String,
    override val atNanos: Long,
    val sensitive: Boolean = false,
) : AttributedEvent {
    /** Copied, so a caller mutating its map later cannot make live and report disagree. */
    override val attributes: Map<String, String> = attributes.toMap()

    val message: String by lazy(LazyThreadSafetyMode.PUBLICATION, messageProvider)
}

/** A flattened [LogEvent]: identity plus the log line and its [attributes]. */
data class LogRecord(
    override val traceId: String?,
    override val spanId: String?,
    override val parentId: String?,
    override val operation: String?,
    override val atNanos: Long,
    override val scopeId: String? = null,
    override val info: Map<String, String>,
    override val links: List<TraceLink>,
    override val attributes: Map<String, String>,
    val message: String,
    val sensitive: Boolean = false,
) : AttributedRecord

/**
 * Logs [message] with [attributes] on this span. Severity, tag and the like are consumer symbols in
 * [attributes]. [sensitive] marks a message carrying user data (see [LogEvent.sensitive]), and [message] is built
 * only when an adapter accepts the event (ADR-002).
 *
 * A suspend caller writes `currentSpan()?.log { }`; a non-suspend bridge calls it on the span it holds, or on
 * `currentThreadSpan()`.
 */
fun Span.log(attributes: Map<String, String> = emptyMap(), sensitive: Boolean = false, message: () -> String) {
    emit(LogEvent(attributes, message, System.nanoTime(), sensitive), resolvedThreadConfig())
}