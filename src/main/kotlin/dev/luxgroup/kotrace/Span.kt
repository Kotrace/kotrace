package dev.luxgroup.kotrace

/**
 * One unit of work in a trace. A tree of these, linked by [parentId], is the call path:
 * caller → callee → callee → throwable.
 *
 * [name] and every [attributes] value are **static symbols only** — a Span can reach a crash report
 * or, via `traceparent`, a backend, so nothing here may carry user data.
 */
data class Span(
    val traceId: String,
    val spanId: String,
    val parentId: String?,
    val name: String,
    val startNanos: Long,
    var endNanos: Long? = null,
    val attributes: MutableMap<String, String> = mutableMapOf(),
    var status: SpanStatus = SpanStatus.OK,
    var error: Throwable? = null,
)
