package dev.kotrace

/**
 * One flat, sink-ready log line lifted off a [Span] — the unit [SpanCollector.report] fans a failed
 * trace out into. Every field is a symbol or id (never user data), so a sink can drop it into a crash
 * report as-is and a reader can search it:
 *
 * - [traceId] groups every record across one flow;
 * - [spanId] groups the records of one span; [parentId] + [operation] rebuild the tree.
 *
 * [throwable] is set only on the record synthesised for a span's caught failure; a plain log event
 * carries none.
 */
data class TraceRecord(
    val traceId: String,
    val spanId: String,
    val parentId: String?,
    val operation: String,
    val level: LogLevel,
    val message: String,
    val throwable: Throwable? = null,
    val atNanos: Long,
)

/**
 * Where [TraceRecord]s go. A consumer implements this once (route to Logcat, Crashlytics, …) and never
 * walks the span tree itself — [SpanCollector.report] does the fan-out.
 */
fun interface TraceSink {
    fun emit(record: TraceRecord)
}

/**
 * A per-span predicate the consumer supplies to [SpanCollector.report] to drop a span's breadcrumb events
 * from the fan-out — e.g. keep `repo` spans, drop `sql` ones, keyed off [Span.attributes]. kotrace never
 * interprets the attributes; the meaning ("layer") lives entirely in the consumer's predicate.
 *
 * It gates **only** the log events. The birthplace throwable is emitted regardless of this filter — a
 * filter tunes breadcrumb verbosity, it can never drop the crash cause.
 */
fun interface SpanFilter {
    fun keep(span: Span): Boolean
}

/**
 * A one-line JSON rendering with snake_case keys — the shape a JSON log pipeline (ELK, Loki, …) ingests
 * as structured fields, so `trace_id` / `span_id` are queryable columns rather than substrings. Keys are
 * fixed and always present (`exception` is `null` on a plain log line). Hand-rolled — the core takes no
 * serialization dependency — but string values are JSON-escaped, so a message with a quote or newline
 * stays valid.
 */
fun TraceRecord.toJson(): String = buildString {
    append('{')
    appendField("trace_id", traceId); append(',')
    appendField("span_id", spanId); append(',')
    appendField("parent_span_id", parentId); append(',')
    appendField("operation", operation); append(',')
    appendField("level", level.name); append(',')
    appendField("message", message); append(',')
    appendField("exception", throwable?.let { "${it::class.simpleName}: ${it.message}" })
    append('}')
}

private fun StringBuilder.appendField(key: String, value: String?) {
    append('"').append(key).append("\":")
    if (value == null) append("null") else append('"').appendEscaped(value).append('"')
}

private fun StringBuilder.appendEscaped(s: String): StringBuilder {
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    return this
}

/**
 * Fans the collected trace out to [sink] as individual [TraceRecord]s — the searchable alternative to a
 * single concatenated [formatTree] blob. Walks the tree depth-first from the root; per span, emits each
 * [SpanEvent] in time order, then, if the span carries a throwable, one final ERROR record for it.
 *
 * Emitting per event (not per span, not one line) is the point: the sink writes one searchable record
 * each, so `trace_id` recovers the whole flow and `span_id` recovers one span.
 *
 * [filter] gates a span's breadcrumb events (default: keep all). A filtered-out span still emits its
 * birthplace throwable — that emit sits outside the guard, so a layer filter can never swallow the crash.
 */
fun SpanCollector.report(sink: TraceSink, filter: SpanFilter = SpanFilter { true }) {
    val all = spans
    val root = all.firstOrNull { it.parentId == null } ?: return
    fun childrenOf(parent: Span) = all.filter { it.parentId == parent.spanId }.sortedBy { it.startNanos }

    fun walk(span: Span) {
        if (filter.keep(span)) {
            span.events.sortedBy { it.atNanos }.forEach { event ->
                sink.emit(span.toRecord(event.level, event.message, atNanos = event.atNanos))
            }
        }
        val children = childrenOf(span)
        // Only the birthplace (deepest ERROR span) carries the throwable — ancestors merely propagate
        // ERROR status, so emitting the throwable at each level would duplicate it up the path.
        val isBirthplace = span.status == SpanStatus.ERROR && children.none { it.status == SpanStatus.ERROR }
        if (isBirthplace) span.error?.let { cause ->
            sink.emit(
                span.toRecord(
                    LogLevel.ERROR,
                    cause.message ?: cause::class.simpleName.orEmpty(),
                    throwable = cause,
                    atNanos = span.endNanos ?: span.startNanos,
                ),
            )
        }
        children.forEach(::walk)
    }
    walk(root)
}

private fun Span.toRecord(level: LogLevel, message: String, throwable: Throwable? = null, atNanos: Long) =
    TraceRecord(
        traceId = traceId,
        spanId = spanId,
        parentId = parentId,
        operation = name,
        level = level,
        message = message,
        throwable = throwable,
        atNanos = atNanos,
    )
