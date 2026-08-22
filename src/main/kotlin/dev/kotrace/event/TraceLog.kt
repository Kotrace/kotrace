package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.currentThreadConfig

/**
 * A log line on a span ([message] + [attributes]) — the diagnostic breadcrumb kind of event. Severity is
 * an attribute (e.g. a `"level"` key), set and interpreted by the consumer; kotrace does not rank it.
 *
 * [message] is built lazily from the provider passed at construction, so an event no adapter accepts never
 * pays to build its string — it is resolved only at fan-out, once a [dev.kotrace.TracePolicy] [dev.kotrace.accepts]
 * it (ADR-002); the event is stored either way. A
 * non-sensitive [message] is a **static symbol only** ([dev.kotrace.Span.name] / [dev.kotrace.Span.attributes] rule): it can
 * reach a crash report, so it must carry no user data. [sensitive] is a capture-site **classification**,
 * not a routing decision: it marks a [message] carrying user data (a captured body). Where it goes is a
 * [dev.kotrace.TracePolicy.acceptsSensitive] decision, defaulting to dropped (fail-closed). To surface one safe field
 * from a body, emit it as a separate non-sensitive [NamedEvent] or [LogEvent], never by un-marking the body.
 */
class LogEvent(
    override val attributes: Map<String, String>,
    messageProvider: () -> String,
    override val atNanos: Long,
    val sensitive: Boolean = false,
) : AttributedEvent {
    val message: String by lazy(LazyThreadSafetyMode.PUBLICATION, messageProvider)
}

/** A flattened [LogEvent]: identity + a log line with its [attributes]. [sensitive] rides through for the policy gate. */
data class LogRecord(
    override val traceId: String,
    override val spanId: String,
    override val parentId: String?,
    override val operation: String,
    override val atNanos: Long,
    override val info: Map<String, String>,
    override val attributes: Map<String, String>,
    val message: String,
    val sensitive: Boolean = false,
) : AttributedRecord

/**
 * The log verb — appends a log [message] with [attributes] to this span. Severity, tag and the like ride
 * in [attributes] as consumer symbols; kotrace neither ranks nor names them. [sensitive] classifies the
 * [message] as carrying user data (a captured body); whether it is fanned out is each adapter's
 * [dev.kotrace.TracePolicy.acceptsSensitive] decision (see [LogEvent.sensitive]). The [message] provider is
 * resolved lazily only when an adapter [dev.kotrace.accepts] the event at fan-out, so a filtered event costs
 * nothing to build even though it is stored (ADR-002).
 *
 * Called on a span in hand: a suspend caller reaches it via `currentSpan()?.log { }`; a non-suspend bridge
 * holding the span across a thread boundary (the OkHttp request tag, a Room callback) calls it directly,
 * or resolves the span via `currentThreadSpan()`. Config (the live adapters) is read through the
 * [dev.kotrace.currentThreadConfig] mirror — this verb is non-suspend, so it cannot read the context directly.
 */
fun Span.log(attributes: Map<String, String> = emptyMap(), sensitive: Boolean = false, message: () -> String) {
    emit(LogEvent(attributes, message, System.nanoTime(), sensitive), currentThreadConfig())
}