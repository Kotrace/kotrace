package dev.kotrace.bridge

import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.SpanStatus
import dev.kotrace.TraceLink
import dev.kotrace.currentThreadCollector
import dev.kotrace.currentThreadScopeId
import dev.kotrace.currentThreadSpan
import dev.kotrace.event.recordPropagatedException
import dev.kotrace.internal.alsoSuppress
import dev.kotrace.internal.createSpan
import dev.kotrace.span

/**
 * Opens a span for **non-suspend** code; close it with [end]. It is a child of [parent], or roots a new trace when
 * that is null, and is registered into [collector] if there is one. The defaults read this thread's mirrors, live
 * on a coroutine's thread (the OkHttp `Call.Factory`); on any other thread pass a [TraceHandoff]'s captured
 * context instead (ADR-023).
 *
 * [traits] and [attributes] mean what they do on [span]: fixed filter dimensions, and the seed of
 * [Span.attributes] that every record carries.
 *
 * Opt-in ([NonSuspendTracingBridge]): unlike [span] it does not install the span into the coroutine context, so a
 * nested suspend [span] misparents. Use it only where no coroutine frame exists.
 */
@NonSuspendTracingBridge
fun startSpan(
    name: String,
    traits: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    attributes: Map<String, String> = emptyMap(),
    parent: Span? = currentThreadSpan(),
    collector: SpanCollector? = currentThreadCollector(),
    scopeId: String? = currentThreadScopeId(),
): Span {
    val opened = createSpan(parent, name, traits, links, attributes, scopeId)
    collector?.add(opened)
    return opened
}

/**
 * Ends a span opened by [startSpan] with [status], recording [error] if given.
 *
 * Only the first call counts (ADR-023): a later one can neither flip the status nor record twice. A non-fatal
 * failure to record [error] is attached to it as suppressed (ADR-013), so the caller's `throw error` still throws
 * the application's throwable.
 */
@NonSuspendTracingBridge
fun Span.end(status: SpanStatus = SpanStatus.OK, error: Throwable? = null) {
    if (!tryComplete(status, System.nanoTime())) return
    if (error == null) return
    runIsolated(phase = null, escaping = error, recover = { error.alsoSuppress(it) }) {
        recordPropagatedException(error)
    }
}
