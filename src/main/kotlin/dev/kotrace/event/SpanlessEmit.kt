package dev.kotrace.event

import dev.kotrace.FaultPhase
import dev.kotrace.accepts
import dev.kotrace.currentThreadScopeId
import dev.kotrace.guardAdapter
import dev.kotrace.guardPolicy
import dev.kotrace.guardShared
import dev.kotrace.liveFanOut
import dev.kotrace.resolvedThreadConfig

/**
 * The span-less counterpart of [log] / [addNamed] / [addException] (ADR-010), for an emit outside any span (an
 * app-lifecycle callback, a push handler). The record has no span identity and goes **live only**, since there is
 * no tree to report; each adapter's policy still gates it, minus [dev.kotrace.TracePolicy.acceptsSpan]. Inside a
 * [dev.kotrace.withScope] it carries the [TraceRecord.scopeId].
 */
private fun emitSpanless(event: SpanEvent, info: Map<String, String> = emptyMap()) {
    val config = resolvedThreadConfig()
    val live = config?.liveAdapters ?: return
    if (live.isEmpty()) return
    val hook = config.faultHook
    // Inside a live fan-out on this thread it has nowhere to be stored, so it is dropped (ADR-025).
    liveFanOut {
        val accepting = live.filter { adapter ->
            guardPolicy(FaultPhase.SPANLESS_LIVE, adapter, hook) { adapter.policy.accepts(event) }
        }
        if (accepting.isEmpty()) return
        val record = guardShared(FaultPhase.SPANLESS_LIVE, hook) {
            spanlessRecordOf(event, currentThreadScopeId(), info)
        } ?: return
        accepting.forEach { adapter ->
            guardAdapter(FaultPhase.SPANLESS_LIVE, adapter, hook) { adapter.onLive(record) }
        }
    }
}

/** Flattens a span-less [event]: null identity, the ambient [scopeId], record-level [info]. */
private fun spanlessRecordOf(event: SpanEvent, scopeId: String?, info: Map<String, String>): TraceRecord = when (event) {
    is LogEvent -> LogRecord(null, null, null, null, event.atNanos, scopeId, emptyMap(), emptyList(), event.attributes, event.message, event.sensitive)
    is NamedEvent -> NamedRecord(null, null, null, null, event.atNanos, scopeId, emptyMap(), emptyList(), event.name, event.attributes)
    is ExceptionEvent -> ExceptionRecord(null, null, null, null, event.atNanos, scopeId, info, emptyList(), event.throwable)
}

/**
 * Logs a breadcrumb with no owning span, live only. Mirrors [log]: [attributes] carry consumer symbols,
 * [sensitive] marks a [message] carrying user data, and [message] is built only once an adapter accepts it.
 */
fun emitLog(attributes: Map<String, String> = emptyMap(), sensitive: Boolean = false, message: () -> String) {
    emitSpanless(LogEvent(attributes, message, System.nanoTime(), sensitive))
}

/** Emits a named event with no owning span, live only. Mirrors [addNamed]. */
fun emitNamed(name: String, attributes: Map<String, String> = emptyMap()) {
    emitSpanless(NamedEvent(name, attributes, System.nanoTime()))
}

/**
 * Emits [cause] with no owning span, live only, the moment it is called. Mirrors [addException], including
 * [info] on the [ExceptionRecord] (ADR-010).
 *
 * @sample dev.kotrace.samples.ScopeSamples.spanlessEmit
 */
fun emitException(cause: Throwable, info: Map<String, String> = emptyMap()) {
    emitSpanless(ExceptionEvent(cause, System.nanoTime()), info)
}
