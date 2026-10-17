package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceConfig
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.accepts
import dev.kotrace.bridge.runIsolated
import dev.kotrace.internal.liveFanOut

/**
 * Appends [event] to the span, then offers it to each [dev.kotrace.adapter.LiveAdapter] whose policy [accepts] it. The
 * append is **unconditional** (ADR-002): policies filter only at fan-out, so a crash is never filtered out at
 * capture, and only the [Span.maxEvents] cap evicts (ADR-029). The record is built only once an adapter accepts
 * the event, so a rejected log never builds its message.
 */
internal fun Span.emit(event: SpanEvent, config: TraceConfig?, extraAttributes: Map<String, String> = emptyMap()) {
    appendEvent(event)
    val liveAdapters = config?.liveAdapters ?: return
    if (liveAdapters.isEmpty()) return
    // An adapter logging through kotrace from its own onLive is stored above, but not fanned out again (ADR-025).
    liveFanOut {
        // A faulting policy reads as reject.
        val accepting = liveAdapters.filter { adapter ->
            runIsolated(FaultPhase.LIVE, "live.policy", recover = { false }) {
                adapter.policy.accepts(this, event)
            }
        }
        if (accepting.isEmpty()) return
        // Shared by every adapter, so a fault here is no one adapter's: no adapter receives the event.
        val record = runIsolated(FaultPhase.LIVE, "live.record", recover = { null }) {
            recordOf(event, extraAttributes)
        } ?: return
        accepting.forEach { adapter ->
            runIsolated(FaultPhase.LIVE, "live.deliver", recover = {}) { adapter.onLive(record) }
        }
    }
}
