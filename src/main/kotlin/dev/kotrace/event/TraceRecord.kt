package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceLink
import java.util.Locale

/**
 * One flat, adapter-ready line lifted off a [dev.kotrace.Span] — the unit fanned out to a [dev.kotrace.TraceAdapter]. The
 * identity fields ([traceId] / [spanId] / [parentId] / [operation]) are the searchable join keys that a
 * flattened line carries but a raw [SpanEvent] does not; a backend rebuilds the tree from them.
 *
 * Sealed to three kinds, one per [SpanEvent] kind a span produces: a [LogRecord] (breadcrumb), a
 * [NamedRecord] (named/analytics event), and an [ExceptionRecord] (the crash, from an [ExceptionEvent] at
 * the birthplace — it bypasses every policy gate).
 */
sealed interface TraceRecord {
    /**
     * The owning span's trace id — a searchable join key. **Nullable**: a span-less emit
     * ([emitLog]/[emitNamed]/[emitException], ADR-010) has no trace, so its record carries a null
     * [traceId] (and [spanId] / [operation]); its correlation, if any, rides [scopeId] instead. A record
     * lifted off a real [Span] always has these set.
     */
    val traceId: String?
    val spanId: String?
    val parentId: String?
    val operation: String?
    val atNanos: Long

    /**
     * The correlation umbrella above [traceId] (`scope ⊇ trace ⊇ span`, ADR-010) — the ambient
     * [dev.kotrace.withScope] id active when this record was emitted, or null outside any scope. A **plain
     * correlation key**: never subject to duration, outcome, the report path or waterfall rendering — those
     * are trace semantics. A span opened inside a scope stamps it alongside [traceId]; a span-less emit
     * inside a scope carries it alone. Rendered by [toJson] only when non-null (back-compat).
     */
    val scopeId: String?

    /**
     * The owning span's late **emitted info** ([dev.kotrace.Span.info]) — a result value like `http.status`,
     * stamped onto every record lifted off that span. Distinct from a record's own [AttributedRecord.attributes]
     * (event attributes): [info] is span-scoped and never a policy filter key (ADR-001). Best-effort on a live
     * record (whatever was stamped by emit time), final on a report record (span complete).
     */
    val info: Map<String, String>

    /**
     * The owning span's birth-set **cross-trace links** ([dev.kotrace.Span.links]) — references to other
     * traces, stamped onto every record lifted off that span (span-scoped, the same treatment as [info]).
     * Empty for a span with no links. See [TraceLink] for why a link is [TraceLink.traceId] only.
     */
    val links: List<TraceLink>
}

/**
 * A record carrying free-form [attributes] — the flattened counterpart of [AttributedEvent], shared by
 * [LogRecord] and [NamedRecord]. [ExceptionRecord] is deliberately not one: its `Throwable` is its data.
 */
sealed interface AttributedRecord : TraceRecord {
    val attributes: Map<String, String>
}

/**
 * Flattens a [SpanEvent] into its [TraceRecord] kind, stamping this span's identity — and its ambient
 * [Span.scopeId] — onto the line. [extraInfo] is merged over the span's [Span.info] (empty by default);
 * it is how [dev.kotrace.event.addException]'s record-level `info` reaches the record without riding the
 * object-only [ExceptionEvent] (ADR-005/ADR-010).
 */
internal fun Span.recordOf(event: SpanEvent, extraInfo: Map<String, String> = emptyMap()): TraceRecord {
    val info = if (extraInfo.isEmpty()) info.toMap() else info.toMap() + extraInfo
    return when (event) {
        is LogEvent -> LogRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, info, links, event.attributes, event.message, event.sensitive)
        is NamedEvent -> NamedRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, info, links, event.name, event.attributes)
        is ExceptionEvent -> ExceptionRecord(traceId, spanId, parentId, name, event.atNanos, scopeId, info, links, event.throwable)
    }
}

/**
 * A one-line JSON rendering with snake_case identity keys — the shape a JSON log pipeline (ELK, Loki, …)
 * ingests as structured fields, so `trace_id` / `span_id` are queryable rather than substrings. Hand-rolled
 * (no serialization dependency); string values are JSON-escaped, so a quote or newline stays valid.
 *
 * Wire shape (ADR-004): the record's own identity (`trace_id`, `span_id`, `parent_span_id`, `operation`,
 * `kind`, and the per-kind `message` / `name` / `exception`) are top-level keys; the two open string→string
 * maps ride **nested** under their own objects — `"attributes":{…}` and `"info":{…}` — not spread as
 * sibling keys. Nesting is namespaced by construction: a consumer-chosen `attributes`/`info` key can never
 * collide with a reserved identity key (or with the other map), so nothing is emitted twice and no value is
 * silently dropped. Modern backends still query a nested field by its dotted/underscored path
 * (`attributes.http_method`), and a nested object maps to a single Elasticsearch `flattened` field, avoiding
 * top-level mapping explosion. Empty maps are omitted, keeping the line lean. This renderer is machine
 * egress — never PII (see [dev.kotrace.Span]'s invariant): the exception carries its class name only.
 *
 * Why hand-rolled, not `kotlinx.serialization`:
 * - Zero dependency. kotrace is a core lib every consumer pulls transitively; a `@Serializable`-based
 *   renderer would force the serialization runtime + plugin onto all of them.
 * - The domain is closed and trivial (3 sealed kinds, string→string maps only), so there is no
 *   polymorphism/number-typing to configure; the only real hand-rolling risk is escaping, handled in
 *   [appendEscaped]. Revisit if the shape gains typed values.
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
            // Class name only — never throwable.message. message is not fully in our control (stdlib /
            // third-party interpolate user data), and toJson feeds a general log store (ELK/Loki), the wrong
            // sink for PII (see Span's invariant). The full message lives on record.throwable, for an adapter
            // that routes it to a crash reporter — the sink allowed to hold PII. Correlate the two by trace_id.
            appendField("exception", record.throwable.javaClass.name)
        }
    }
    appendObject("info", info)
    appendLinks("links", links)
    // scope_id is emitted only when present (ADR-010): absent = today's shape, so an unscoped record and
    // every pre-scope tool are untouched — parallel to how empty attributes/info/links omit their key.
    appendOptionalField("scope_id", scopeId)
    append('}')
}

private fun StringBuilder.appendField(key: String, value: String?) {
    append('"').append(key).append("\":")
    if (value == null) append("null") else append('"').appendEscaped(value).append('"')
}

/** Renders `,"key":"value"` only when [value] is non-null (leads with the comma, so the caller need not). */
private fun StringBuilder.appendOptionalField(key: String, value: String?) {
    if (value == null) return
    append(',').append('"').append(key).append("\":\"").appendEscaped(value).append('"')
}

/** Renders [map] as a nested `"key":{…}` object. Empty maps emit nothing (leaner line, stable enough — a
 *  consumer reads a missing object as no attributes/info). Leads with a comma, so the caller need not. */
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

/** Renders [links] as a nested `"key":[{…}]` array, each link a `{"trace_id":…,"attributes":{…}}` object.
 *  Empty list emits nothing (leaner line, mirrors [appendObject]). A link's `attributes` object is itself
 *  omitted when empty. Leads with a comma, so the caller need not. */
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
