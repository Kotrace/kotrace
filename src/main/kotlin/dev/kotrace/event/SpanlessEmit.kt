package dev.kotrace.event

import dev.kotrace.accepts
import dev.kotrace.currentThreadScopeId
import dev.kotrace.resolvedThreadConfig

/**
 * Span-less emit verbs (ADR-010) — the orphan counterparts of the [Span]-scoped [log] / [addNamed] /
 * [addException]. They build a record with **no span** (null [TraceRecord.traceId] / [TraceRecord.spanId] /
 * [TraceRecord.operation]) and fan it, **live only**, to the resolved fan-out config's live adapters — the
 * per-flow [dev.kotrace.TraceConfig] override if one is in context, else the process-wide
 * [dev.kotrace.Kotrace] config ([dev.kotrace.resolvedThreadConfig]). This is the path an emit outside any
 * span (an app-lifecycle callback, a push handler, any non-coroutine entry) takes so it still reaches the
 * consumer's one fan-out authority (ADR-002) instead of a sink called directly.
 *
 * There is no report path: a span-less emit has no tree to tail-buffer, so it fires as it happens or not at
 * all. Each adapter's [dev.kotrace.TracePolicy] still gates it — the same predicate as the per-trace live
 * path, minus the span-only [dev.kotrace.TracePolicy.acceptsSpan] ([accepts] over a bare event). With no
 * config installed and no override, every verb here is a safe no-op.
 *
 * If a [dev.kotrace.withScope] is active the record carries its [TraceRecord.scopeId] (nothing is truly
 * orphan inside a scope); outside any scope [scopeId][TraceRecord.scopeId] is null.
 */
private fun emitSpanless(event: SpanEvent, info: Map<String, String> = emptyMap()) {
    val live = resolvedThreadConfig()?.liveAdapters.orEmpty()
    val accepting = live.filter { it.policy.accepts(event) }
    if (accepting.isEmpty()) return
    val record = spanlessRecordOf(event, currentThreadScopeId(), info)
    accepting.forEach { it.onLive(record) }
}

/** Flattens a span-less [event] into its record kind — null identity, ambient [scopeId], record-level [info]. */
private fun spanlessRecordOf(event: SpanEvent, scopeId: String?, info: Map<String, String>): TraceRecord = when (event) {
    is LogEvent -> LogRecord(null, null, null, null, event.atNanos, scopeId, emptyMap(), emptyList(), event.attributes, event.message, event.sensitive)
    is NamedEvent -> NamedRecord(null, null, null, null, event.atNanos, scopeId, emptyMap(), emptyList(), event.name, event.attributes)
    is ExceptionEvent -> ExceptionRecord(null, null, null, null, event.atNanos, scopeId, info, emptyList(), event.throwable)
}

/**
 * The span-less log verb — a diagnostic breadcrumb with no owning span, fanned live to the default registry.
 * Mirrors [Span.log]: [attributes] carry consumer symbols (severity, tag), [sensitive] classifies a
 * [message] carrying user data (routed by each adapter's [dev.kotrace.TracePolicy.acceptsSensitive]), and
 * the [message] provider resolves lazily only once an adapter accepts the event.
 */
fun emitLog(attributes: Map<String, String> = emptyMap(), sensitive: Boolean = false, message: () -> String) {
    emitSpanless(LogEvent(attributes, message, System.nanoTime(), sensitive))
}

/**
 * The span-less named-event verb — a named, structured occurrence with no owning span, fanned live to the
 * default registry. Mirrors [Span.addNamed]; live-only, as a named event always is.
 */
fun emitNamed(name: String, attributes: Map<String, String> = emptyMap()) {
    emitSpanless(NamedEvent(name, attributes, System.nanoTime()))
}

/**
 * The span-less exception verb — records [cause] with no owning span, fanned live to the default registry.
 * Mirrors [Span.addException], including the record-level [info] map (ADR-010): it populates
 * [ExceptionRecord.info] while the [ExceptionEvent] stays object-only (a throwable, no attribute bag —
 * ADR-005). Because a span-less exception fans live and unconditionally (no trace status gates it), an
 * explicit report reaches live adapters the moment it is emitted.
 *
 * @sample dev.kotrace.samples.ScopeSamples.spanlessEmit
 */
fun emitException(cause: Throwable, info: Map<String, String> = emptyMap()) {
    emitSpanless(ExceptionEvent(cause, System.nanoTime()), info)
}
