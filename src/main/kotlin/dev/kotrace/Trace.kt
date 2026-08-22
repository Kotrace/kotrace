package dev.kotrace

import dev.kotrace.event.addException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Opens a child span of the current span, closes it on return, and marks it ERROR (recording the
 * throwable) on the way out — **rethrowing unchanged**. Named for the node it opens (a span), not the tree
 * (ADR-003); the non-suspend counterpart is [startSpan].
 *
 * This is instrumentation, not error handling: [span] only observes and rethrows, so a caller may wrap
 * a body in it and still handle failures inside however it likes. ERROR propagates up the tree naturally
 * — the rethrown throwable passes through every enclosing [span], marking each ancestor.
 *
 * @sample dev.kotrace.samples.SpanSamples.spanUsage
 */
suspend fun <T> span(name: String, attributes: Map<String, String> = emptyMap(), block: suspend () -> T): T {
    val opened = createSpan(currentCoroutineContext()[SpanContext]?.span, name, attributes)
    currentCollector()?.add(opened)
    return try {
        // Overlay only the element — withContext already inherits the current context. Passing the
        // whole currentCoroutineContext() would re-inject its Job and break structured concurrency.
        withContext(SpanContext(opened)) { block() }
    } catch (t: Throwable) {
        // ERROR marks the whole failing path as the throwable rethrows through each enclosing span.
        // Which span is the *birthplace* is decided structurally at read time (the deepest span carrying
        // the throwable — see isBirthplaceAmong) — not by exception identity, which coroutine stacktrace
        // recovery breaks by copying `t` across each `withContext` boundary.
        opened.markStatus(SpanStatus.ERROR)
        opened.addException(t)
        throw t
    } finally {
        opened.markEnd(System.nanoTime())
    }
}

/**
 * Opens a span in the current context and registers it into [currentThreadCollector], for **non-suspend** code
 * running on a coroutine's thread that has no `coroutineContext` handle to call [span] — the OkHttp
 * `Call.Factory` (a raw call built on the coroutine thread) is the case this exists for. The caller must
 * close it with [end].
 *
 * Opt-in ([NonSuspendTracingBridge]): unlike [span] this does **not** install its span into the coroutine
 * context, so a nested suspend [span] misparents. Use it only where no coroutine frame exists.
 *
 * Parented to [currentThreadSpan] (the thread-local mirror, since this is off the coroutine frame): a
 * child when one is active, else a **root** (fresh `traceId`) — an untraced request still gets a span, so
 * a live sink can log it. Whether it rooted is on the returned span (`parentId == null`); the caller need
 * not decide. With no active collector the span simply isn't collected (report needs one), which is the
 * correct no-op for a truly untraced background call.
 */
@NonSuspendTracingBridge
fun startSpan(name: String, attributes: Map<String, String> = emptyMap()): Span {
    val opened = createSpan(currentThreadSpan(), name, attributes)
    currentThreadCollector()?.add(opened)
    return opened
}

/** Closes a span opened by [startSpan], stamping its end, [status] and optional [error]. Mirrors OTel `span.end()`. */
@NonSuspendTracingBridge
fun Span.end(status: SpanStatus = SpanStatus.OK, error: Throwable? = null) {
    markCompleted(status, System.nanoTime()) // both fields in one atomic publish
    if (error != null) this.addException(error)
}

/**
 * Builds a span under [parent] — the one place the `Span(…)` shape lives, shared by the suspend [span]
 * and the non-suspend [startSpan]. A null [parent] roots a fresh trace (new `traceId`). The caller
 * resolves [parent] from its own source — [span] from the coroutine context (authoritative), [startSpan]
 * from the [currentThreadSpan] mirror — so this never reaches for ambient state.
 */
private fun createSpan(parent: Span?, name: String, attributes: Map<String, String>): Span = Span(
    traceId = parent?.traceId ?: hex(16),
    spanId = hex(8),
    parentId = parent?.spanId,
    name = name,
    startNanos = System.nanoTime(),
    attributes = attributes,
)

private val random = SecureRandom()

/** Lowercase hex of [bytes] random bytes — 16 for a trace id (W3C 32 chars), 8 for a span id (16). */
internal fun hex(bytes: Int): String {
    val b = ByteArray(bytes)
    random.nextBytes(b)
    return b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
