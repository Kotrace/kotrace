package dev.kotrace

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Opens a child of the current span, closes it on return, and marks it ERROR (recording the throwable)
 * on the way out — **rethrowing unchanged**.
 *
 * This is instrumentation, not error handling: [trace] only observes and rethrows, so a caller may wrap
 * a body in it and still handle failures inside however it likes. ERROR propagates up the tree naturally
 * — the rethrown throwable passes through every enclosing [trace], marking each ancestor.
 */
suspend fun <T> trace(name: String, attributes: Map<String, String> = emptyMap(), block: suspend () -> T): T {
    val parent = currentCoroutineContext()[SpanContext]?.span
    val span = Span(
        traceId = parent?.traceId ?: hex(16),
        spanId = hex(8),
        parentId = parent?.spanId,
        name = name,
        startNanos = System.nanoTime(),
        attributes = attributes.toMutableMap(),
    )
    currentCoroutineContext()[SpanCollector]?.add(span)
    return try {
        // Overlay only the element — withContext already inherits the current context. Passing the
        // whole currentCoroutineContext() would re-inject its Job and break structured concurrency.
        withContext(SpanContext(span)) { block() }
    } catch (t: Throwable) {
        // ERROR marks the whole failing path as the throwable rethrows through each enclosing trace.
        // Which span is the *birthplace* is decided structurally at read time (the deepest ERROR span)
        // — not by exception identity, which coroutine stacktrace recovery breaks by copying `t` across
        // each `withContext` boundary.
        span.status = SpanStatus.ERROR
        span.error = t
        throw t
    } finally {
        span.endNanos = System.nanoTime()
    }
}

/**
 * Opens a child of [currentSpan] and registers it into [currentCollector], for **non-suspend** code
 * running on a coroutine's thread that has no `coroutineContext` handle to call [trace] — the OkHttp
 * `Call.Factory` (a raw call built on the coroutine thread) is the case this exists for. The caller must
 * close it with [endHere].
 *
 * With no active span it opens a **root** (fresh `traceId`) — an untraced request still gets a span, so
 * a live sink can log it; with no active collector the span simply isn't collected (report needs one),
 * which is the correct no-op for a truly untraced background call.
 */
fun startChildSpanHere(name: String, attributes: Map<String, String> = emptyMap()): Span {
    val parent = currentSpan()
    val span = Span(
        traceId = parent?.traceId ?: hex(16),
        spanId = hex(8),
        parentId = parent?.spanId,
        name = name,
        startNanos = System.nanoTime(),
        attributes = attributes.toMutableMap(),
    )
    currentCollector()?.add(span)
    return span
}

/** Closes a span opened by [startChildSpanHere], stamping its end, [status] and optional [error]. */
fun Span.endHere(status: SpanStatus = SpanStatus.OK, error: Throwable? = null) {
    endNanos = System.nanoTime()
    this.status = status
    if (error != null) this.error = error
}

/**
 * The birthplace span: the deepest ERROR span that carries a throwable. Depth is walked over
 * [SpanCollector.spans] via [Span.parentId]; only spans whose failure was a `Throwable` set
 * [Span.error], so a tree whose failures were all non-throwable yields none.
 */
fun SpanCollector.birthplaceSpan(): Span? {
    val byId = spans.associateBy { it.spanId }
    fun depth(span: Span): Int {
        var d = 0
        var cursor: Span? = span
        while (cursor?.parentId != null) {
            cursor = byId[cursor.parentId]
            d++
        }
        return d
    }
    return spans
        .filter { it.status == SpanStatus.ERROR && it.error != null }
        .maxByOrNull(::depth)
}

/**
 * Runs [block] as the root of a fresh trace and returns every span collected, error path included. The
 * failure is swallowed here so the caller can inspect the tree; a real sink instead reads
 * [SpanCollector] from context at the report site and leaves the throwable to propagate.
 */
suspend fun collectTrace(block: suspend () -> Unit): List<Span> {
    val collector = SpanCollector()
    withContext(collector) {
        runCatching { block() }
    }
    return collector.spans
}

private val random = SecureRandom()

/** Lowercase hex of [bytes] random bytes — 16 for a trace id (W3C 32 chars), 8 for a span id (16). */
internal fun hex(bytes: Int): String {
    val b = ByteArray(bytes)
    random.nextBytes(b)
    return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
