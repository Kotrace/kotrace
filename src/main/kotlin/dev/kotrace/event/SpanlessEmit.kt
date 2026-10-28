package dev.kotrace.event

import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.accepts
import dev.kotrace.adapter.attributeView
import dev.kotrace.bridge.runIsolated
import dev.kotrace.currentThreadScopeId
import dev.kotrace.internal.liveFanOut
import dev.kotrace.resolveScopeId
import dev.kotrace.resolvedThreadConfig

/**
 * The span-less counterpart of [log] / [addNamed] / [addException] (ADR-010), for an emit outside any span (an
 * app-lifecycle callback, a push handler). The record has no span identity and goes **live only**, since there is
 * no tree to report; each adapter's policy still gates it, minus [dev.kotrace.adapter.TracePolicy.acceptsSpan], and
 * filters its attributes ([dev.kotrace.adapter.TracePolicy.acceptsAttribute], ADR-039). Inside
 * a [dev.kotrace.withScope] it carries the [TraceRecord.scopeId]; outside one, the installed fallback's (ADR-038).
 */
private fun emitSpanless(event: SpanEvent, attributes: Map<String, String> = emptyMap()) {
    val config = resolvedThreadConfig()
    val live = config?.liveAdapters ?: return
    if (live.isEmpty()) return
    // Inside a live fan-out on this thread it has nowhere to be stored, so it is dropped (ADR-025).
    liveFanOut {
        // A faulting policy reads as reject.
        val accepting = live.filter { adapter ->
            runIsolated(FaultPhase.SPANLESS_LIVE, "spanless.policy", recover = { false }) {
                adapter.policy.accepts(event)
            }
        }
        if (accepting.isEmpty()) return
        // Resolved apart from the record: a throwing fallback provider costs the scope, never the record (ADR-038).
        val scopeId = resolveScopeId(currentThreadScopeId(), FaultPhase.SPANLESS_LIVE)
        // Shared by every adapter, so a fault here is no one adapter's: no adapter receives the event.
        val record = runIsolated(FaultPhase.SPANLESS_LIVE, "spanless.record", recover = { null }) {
            spanlessRecordOf(event, scopeId, attributes)
        } ?: return
        accepting.forEach { adapter ->
            runIsolated(FaultPhase.SPANLESS_LIVE, "spanless.deliver", recover = {}) {
                adapter.onLive(adapter.policy.attributeView(record, FaultPhase.SPANLESS_LIVE, "spanless.attribute"))
            }
        }
    }
}

/** Flattens a span-less [event]: null identity, the resolved [scopeId], record-level [attributes]. */
private fun spanlessRecordOf(event: SpanEvent, scopeId: String?, attributes: Map<String, String>): TraceRecord = when (event) {
    is LogEvent -> LogRecord(null, null, null, null, event.atNanos, scopeId, emptyMap(), emptyList(), event.attributes, event.message, event.sensitive)
    is NamedEvent -> NamedRecord(null, null, null, null, event.atNanos, scopeId, emptyMap(), emptyList(), event.name, event.attributes)
    is ExceptionEvent -> ExceptionRecord(null, null, null, null, event.atNanos, scopeId, attributes, emptyList(), event.throwable)
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
 * [attributes] on the [ExceptionRecord] (ADR-010), snapshotted here like every input map (ADR-028).
 *
 * @sample dev.kotrace.samples.ScopeSamples.spanlessEmit
 */
fun emitException(cause: Throwable, attributes: Map<String, String> = emptyMap()) {
    emitSpanless(ExceptionEvent(cause, System.nanoTime()), attributes.toMap())
}
