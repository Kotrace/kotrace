package dev.kotrace

import java.util.concurrent.CopyOnWriteArrayList

/**
 * One unit of work in a trace. A tree of these, linked by [parentId], is the call path:
 * caller → callee → callee → throwable.
 *
 * [name], every [attributes] value and every [events] message are **static symbols only** — a Span can
 * reach a crash report or, via `traceparent`, a backend, so nothing here may carry user data.
 *
 * [events] is copy-on-write: a traced fan-out (parallel `async` children) or a non-suspend callback on
 * an off-coroutine thread (OkHttp/Room) may append concurrently. Traces hold few events, so the cost is
 * negligible — the same trade [SpanCollector] makes for its span list.
 */
data class Span(
    val traceId: String,
    val spanId: String,
    val parentId: String?,
    val name: String,
    val startNanos: Long,
    var endNanos: Long? = null,
    val attributes: MutableMap<String, String> = mutableMapOf(),
    val events: MutableList<SpanEvent> = CopyOnWriteArrayList(),
    var status: SpanStatus = SpanStatus.OK,
    var error: Throwable? = null,
)
