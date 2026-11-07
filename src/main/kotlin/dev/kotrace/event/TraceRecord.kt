package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.SpanStatus
import dev.kotrace.TraceLink
import java.util.Locale

/**
 * One [SpanEvent] flattened together with its span: the unit a [dev.kotrace.adapter.TraceAdapter] receives, live
 * through `onLive` or at the end of the trace through `onReport`. An adapter never walks the [Span] tree; each record
 * carries the identity a backend rebuilds the tree from.
 *
 * There is one kind per [SpanEvent] kind: a [LogRecord], a [NamedRecord], and an [ExceptionRecord], which a report
 * marks with an [ExceptionOrigin] (ADR-019). A fourth, [SpanRecord], is the span itself, delivered only by a report
 * and only to an adapter that asks for it (ADR-044). A `when` over them is exhaustive.
 *
 * **Identity.** [traceId], [spanId] and [parentId] are the span's W3C ids; [parentId] is null on a root. [operation]
 * is the span's name. All four are null on a span-less emit ([emitLog], [emitNamed], [emitException], ADR-010), which
 * correlates through [scopeId] instead.
 *
 * **Time.** [atNanos] is when the event happened, from `System.nanoTime()`: it orders the records of one process and
 * measures the gaps between them, but it is not a wall-clock time and means nothing across processes. A sink that
 * needs a timestamp stamps its own.
 *
 * **Correlation.** [scopeId] is the [dev.kotrace.withScope] id active when the event was emitted, else the installed
 * fallback's (`Kotrace.install(fallbackScopeId = …)`, ADR-038), else null (ADR-010): a key above [traceId]
 * (`scope ⊇ trace ⊇ span`), with no trace semantics. A span's records carry the scope resolved when it opened. [links] are the span's cross-trace edges
 * ([dev.kotrace.Span.links], ADR-009), empty when it has none or on a span-less record.
 *
 * **The span's payload.** [spanAttributes] are the span's [dev.kotrace.Span.attributes] (`http.status`), never a
 * filter key (ADR-001/032). A live record holds them as they stood when the event was emitted, so a value stamped
 * later is missing; a report record reads them when the report is built, after the span ended, so they are final.
 * The `attributes` of [addException] are merged in, on the live record only; those of [emitException] are a
 * span-less record's only [spanAttributes]. They are distinct from the event's own [AttributedRecord.attributes].
 *
 * **What an adapter sees.** A record handed to an adapter carries only the attributes its
 * [dev.kotrace.adapter.TracePolicy.acceptsAttribute] admits, in [spanAttributes], [AttributedRecord.attributes] and
 * each link's [TraceLink.attributes]; none by default (ADR-039). Two adapters on one flow can thus receive the
 * same event with different attributes.
 */
sealed interface TraceRecord {
    val traceId: String?
    val spanId: String?
    val parentId: String?
    val operation: String?
    val atNanos: Long
    val scopeId: String?
    val spanAttributes: Map<String, String>
    val links: List<TraceLink>
}

/**
 * A record whose event carries its own [attributes], set by the event verb (`log(attributes) { … }`,
 * `addNamed(name, attributes)`), beside the span's [spanAttributes]. [ExceptionRecord] is not one: its `Throwable` is
 * its data.
 */
sealed interface AttributedRecord : TraceRecord {
    val attributes: Map<String, String>
}

/**
 * Flattens [event] into its [TraceRecord], stamped with this span's identity and [Span.scopeId], and with a copy of
 * [Span.attributes] taken now. [extraAttributes] merge over that copy: they are how the `attributes` of
 * [addException] reach a live record (ADR-010). [origin] is set only by the report (ADR-019).
 */
internal fun Span.recordOf(
    event: SpanEvent,
    extraAttributes: Map<String, String> = emptyMap(),
    origin: ExceptionOrigin? = null,
): TraceRecord {
    val spanAttributes = if (extraAttributes.isEmpty()) attributes.toMap() else attributes.toMap() + extraAttributes
    return when (event) {
        is LogEvent -> LogRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, spanAttributes, links, event.attributes, event.message, event.sensitive)
        is NamedEvent -> NamedRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, spanAttributes, links, event.name, event.attributes)
        is ExceptionEvent -> ExceptionRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, spanAttributes, links, event.throwable, origin)
    }
}

/**
 * Renders this record as one line of JSON for a log pipeline (ELK, Loki), with snake_case keys so `trace_id` and
 * `span_id` are queryable fields. For example, a log line:
 *
 * ```json
 * {"trace_id":"4bf9…","span_id":"00f0…","parent_span_id":null,"operation":"checkout","kind":"log",
 *  "message":"priced","attributes":{"level":"INFO"},"span_attributes":{"http.status":"200"},
 *  "links":[{"trace_id":"a3ce…","attributes":{"relation":"reports"}}],"scope_id":"session-7"}
 * ```
 *
 * **Shape (ADR-004/032).**
 * - Always present: `trace_id`, `span_id`, `parent_span_id` and `operation`, written as `null` on a span-less record,
 *   then `kind` (`log`, `event`, `exception` or `span`) and that kind's value: `message`, `name`, `exception`, or
 *   for a span `span_status` (`ok`, `error` or `cancelled`) and `duration_nanos`, `null` while the span is open.
 * - Present only when not empty: the event's `attributes`, the span's `span_attributes`, `links` and `scope_id`, and
 *   `exception_origin`, written as `propagated` for [ExceptionOrigin.PROPAGATED] or `explicit` for
 *   [ExceptionOrigin.EXPLICIT], or `attached` for [ExceptionOrigin.ATTACHED]; a birthplace or live record omits it.
 * - Both attribute maps are **nested** objects, so a consumer's key can never collide with a reserved one.
 * - [TraceRecord.atNanos] is not rendered, nor a span's end: neither is a wall-clock time. A span's
 *   `duration_nanos` is a measured length, which means the same in any process.
 *
 * **A shape of its own.** The keys are kotrace's, not a vendor schema such as the Elastic Common Schema: a pipeline
 * that wants `trace.id` or `error.type` renames them at ingest. A line carries no timestamp, so the log shipper
 * stamps one as it reads the line; from a [dev.kotrace.adapter.ReportAdapter], that is the end of the trace for every
 * record. An adapter that needs another shape or a time per event builds its own line from the record's public
 * fields.
 *
 * **What it renders.** This is machine egress, so it must never carry user data (see [dev.kotrace.Span]). An
 * exception renders its class name only, never its message; an adapter that wants the message reads
 * [ExceptionRecord.throwable] into a line of its own. A [LogRecord] marked `sensitive` renders its message
 * as is: keeping it out is the adapter's [dev.kotrace.adapter.TracePolicy.acceptsSensitive], which refuses it by
 * default. Likewise it renders every attribute the record holds, which on a delivered record are only those the
 * adapter's [dev.kotrace.adapter.TracePolicy.acceptsAttribute] admitted (ADR-039).
 *
 * Hand-rolled rather than `kotlinx.serialization`, so no consumer of this core library pays for the runtime and the
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
            // Birthplaces and live records keep their older shape (ADR-019/040).
            when (record.origin) {
                ExceptionOrigin.PROPAGATED -> appendOptionalField("exception_origin", "propagated")
                ExceptionOrigin.EXPLICIT -> appendOptionalField("exception_origin", "explicit")
                ExceptionOrigin.ATTACHED -> appendOptionalField("exception_origin", "attached")
                ExceptionOrigin.BIRTHPLACE, null -> Unit
            }
        }
        is SpanRecord -> {
            appendField("kind", "span"); append(',')
            // Spelled out, so renaming a constant never changes the wire (ADR-044).
            val status = when (record.status) {
                SpanStatus.OK -> "ok"
                SpanStatus.ERROR -> "error"
                SpanStatus.CANCELLED -> "cancelled"
            }
            appendField("span_status", status); append(',')
            append("\"duration_nanos\":").append(record.endNanos?.let { (it - record.atNanos).toString() } ?: "null")
        }
    }
    appendObject("span_attributes", spanAttributes)
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

/**
 * Appends [s] escaped for a JSON string: the quote, the backslash, and every control character, the common ones as
 * `\n`, `\r`, `\t` and the rest as `\u00XX`. Anything else, non-ASCII included, is written as is, which JSON allows.
 */
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
