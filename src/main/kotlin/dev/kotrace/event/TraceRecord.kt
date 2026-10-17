package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceLink
import java.util.Locale

/**
 * One flat line lifted off a [dev.kotrace.Span], the unit fanned out to a [dev.kotrace.TraceAdapter]. Unlike a
 * raw [SpanEvent] it carries the identity fields ([traceId] / [spanId] / [parentId] / [operation]), the join keys a
 * backend rebuilds the tree from.
 *
 * One kind per [SpanEvent] kind: a [LogRecord], a [NamedRecord], and an [ExceptionRecord], which the report marks
 * with an [ExceptionOrigin] (ADR-019).
 */
sealed interface TraceRecord {
    /**
     * The owning span's trace id. Null, like [spanId] and [operation], on a span-less emit
     * ([emitLog]/[emitNamed]/[emitException], ADR-010), which correlates through [scopeId] instead.
     */
    val traceId: String?
    val spanId: String?
    val parentId: String?
    val operation: String?
    val atNanos: Long

    /**
     * The [dev.kotrace.withScope] id active when this record was emitted, or null (ADR-010): a correlation key
     * above [traceId] (`scope ⊇ trace ⊇ span`), with no trace semantics.
     */
    val scopeId: String?

    /**
     * The owning span's [dev.kotrace.Span.info] (`http.status`), never a filter key (ADR-001). Best-effort on a
     * live record, final on a report record.
     */
    val info: Map<String, String>

    /** The owning span's cross-trace [dev.kotrace.Span.links]; empty when it has none. */
    val links: List<TraceLink>
}

/** A record carrying free-form [attributes]. [ExceptionRecord] is not one: its `Throwable` is its data. */
sealed interface AttributedRecord : TraceRecord {
    val attributes: Map<String, String>
}

/**
 * Flattens [event] into its [TraceRecord], stamped with this span's identity and [Span.scopeId]. [extraInfo]
 * merges over [Span.info]: it is how [addException]'s `info` reaches the record (ADR-010).
 */
internal fun Span.recordOf(
    event: SpanEvent,
    extraInfo: Map<String, String> = emptyMap(),
    origin: ExceptionOrigin? = null,
): TraceRecord {
    val info = if (extraInfo.isEmpty()) info.toMap() else info.toMap() + extraInfo
    return when (event) {
        is LogEvent -> LogRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, info, links, event.attributes, event.message, event.sensitive)
        is NamedEvent -> NamedRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, info, links, event.name, event.attributes)
        is ExceptionEvent -> ExceptionRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, info, links, event.throwable, origin)
    }
}

/**
 * A one-line JSON rendering for a log pipeline (ELK, Loki), with snake_case keys so `trace_id` and `span_id` are
 * queryable fields.
 *
 * Wire shape (ADR-004): identity and the per-kind `message` / `name` / `exception` are top-level keys; the
 * `attributes` and `info` maps are **nested** objects, so a consumer's key can never collide with a reserved one.
 * Empty maps and absent optional keys are omitted. This is machine egress, never PII (see [dev.kotrace.Span]): an
 * exception renders its class name only.
 *
 * Hand-rolled rather than `kotlinx.serialization`, so every consumer of this core library is spared the runtime and
 * plugin; the shape is closed and string-only, and escaping is the one risk ([appendEscaped]).
 */
fun TraceRecord.toJson(): String = buildString {
    append('{')
    appendField("trace_id", traceId); append(',')
    appendField("span_id", spanId); append(',')
    appendField("parent_span_id", parentId); append(',')
    appendField("operation", operation); append(',')
    when (val record = this@toJson) {
        is LogRecord -> {
            appendField("kind", "log"); append(',')
            appendField("message", record.message)
            appendObject("attributes", record.attributes)
        }
        is NamedRecord -> {
            appendField("kind", "event"); append(',')
            appendField("name", record.name)
            appendObject("attributes", record.attributes)
        }
        is ExceptionRecord -> {
            appendField("kind", "exception"); append(',')
            // Class name only: the message can hold user data, and belongs in a crash reporter, joined by trace_id.
            appendField("exception", record.throwable.javaClass.name)
            // Only for a PROPAGATED copy (ADR-019), so a birthplace-only report keeps its older shape.
            if (record.origin == ExceptionOrigin.PROPAGATED) { append(','); appendField("exception_origin", "propagated") }
        }
    }
    appendObject("info", info)
    appendLinks("links", links)
    appendOptionalField("scope_id", scopeId)
    append('}')
}

/** Renders `"key":value`, escaping the key too: map keys are consumer-chosen and could break the line. */
private fun StringBuilder.appendField(key: String, value: String?) {
    append('"').appendEscaped(key).append("\":")
    if (value == null) append("null") else append('"').appendEscaped(value).append('"')
}

/** Renders `,"key":"value"` only when [value] is non-null. */
private fun StringBuilder.appendOptionalField(key: String, value: String?) {
    if (value == null) return
    append(',').append('"').appendEscaped(key).append("\":\"").appendEscaped(value).append('"')
}

/** Renders `,"key":{…}`, or nothing for an empty [map]. */
private fun StringBuilder.appendObject(key: String, map: Map<String, String>) {
    if (map.isEmpty()) return
    append(',').append('"').append(key).append("\":{")
    var first = true
    for ((k, v) in map) {
        if (!first) append(',')
        first = false
        appendField(k, v)
    }
    append('}')
}

/** Renders `,"key":[{"trace_id":…,"attributes":{…}}]`, or nothing for empty [links]. */
private fun StringBuilder.appendLinks(key: String, links: List<TraceLink>) {
    if (links.isEmpty()) return
    append(',').append('"').append(key).append("\":[")
    var first = true
    for (link in links) {
        if (!first) append(',')
        first = false
        append('{')
        appendField("trace_id", link.traceId)
        appendObject("attributes", link.attributes)
        append('}')
    }
    append(']')
}

private fun StringBuilder.appendEscaped(s: String): StringBuilder {
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(Locale.ROOT, c.code)) else append(c)
    }
    return this
}
