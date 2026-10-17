package dev.kotrace.event

import dev.kotrace.FaultPhase
import dev.kotrace.Span
import dev.kotrace.TraceConfig
import dev.kotrace.accepts
import dev.kotrace.guardAdapter
import dev.kotrace.guardPolicy
import dev.kotrace.guardShared
import dev.kotrace.liveFanOut

/**
 * Appends [event] to the span, then offers it to each [dev.kotrace.LiveAdapter] whose policy [accepts] it. The
 * append is **unconditional** (ADR-002): policies filter only at fan-out, so a crash is never filtered out at
 * capture, and only the [Span.MAX_EVENTS] cap evicts (ADR-029). The record is built only once an adapter accepts
 * the event, so a rejected log never builds its message.
 */
internal fun Span.emit(event: SpanEvent, config: TraceConfig?, extraInfo: Map<String, String> = emptyMap()) {
    appendEvent(event)
    val liveAdapters = config?.liveAdapters ?: return
    if (liveAdapters.isEmpty()) return
    val hook = config.faultHook
    // An adapter logging through kotrace from its own onLive is stored above, but not fanned out again (ADR-025).
    liveFanOut {
        val accepting = liveAdapters.filter { adapter ->
            guardPolicy(FaultPhase.LIVE, adapter, hook) { adapter.policy.accepts(this, event) }
        }
        if (accepting.isEmpty()) return
        val record = guardShared(FaultPhase.LIVE, hook) { recordOf(event, extraInfo) } ?: return
        accepting.forEach { adapter ->
            guardAdapter(FaultPhase.LIVE, adapter, hook) { adapter.onLive(record) }
        }
    }
}
