package dev.kotrace.event

import dev.kotrace.FaultPhase
import dev.kotrace.Span
import dev.kotrace.TraceConfig
import dev.kotrace.accepts
import dev.kotrace.guardAdapter
import dev.kotrace.guardPolicy
import dev.kotrace.guardShared

/**
 * Appends [event] to the span, then offers it to each [dev.kotrace.LiveAdapter] as it happens — the per-event path
 * parallel to the failure fan-out ([dev.kotrace.reportTrace]). A live adapter sees it only if its policy
 * [accepts] the span/event (the same predicate the report path uses). The append is **unconditional** —
 * there is no capture gate (ADR-002); fan-out is the single filtering authority. The record is built only
 * once at least one live adapter [accepts] the event, so a rejected log never resolves its lazy message and
 * a report-only trace pays nothing per event beyond the append.
 *
 * `internal`, shared by the log, named, and exception verbs — all three append here unconditionally, so a
 * crash is never dropped and a filtered breadcrumb is still stored (just not fanned out).
 */
internal fun Span.emit(event: SpanEvent, config: TraceConfig?, extraInfo: Map<String, String> = emptyMap()) {
    eventBuffer += event
    val liveAdapters = config?.liveAdapters ?: return
    if (liveAdapters.isEmpty()) return
    val hook = config.faultHook
    // Per-adapter policy under its own guard (ADR-014): a throwing policy is contained (treated as reject),
    // never propagated into the traced operation.
    val accepting = liveAdapters.filter { adapter ->
        guardPolicy(FaultPhase.LIVE, adapter, hook) { adapter.policy.accepts(this, event) }
    }
    if (accepting.isEmpty()) return
    // Shared, per-event record construction under a shared guard: on failure the event is delivered to no
    // accepting adapter (the record does not exist), the operation continues, and the hook fires with null.
    val record = guardShared(FaultPhase.LIVE, hook) { recordOf(event, extraInfo) } ?: return
    accepting.forEach { adapter ->
        guardAdapter(FaultPhase.LIVE, adapter, hook) { adapter.onLive(record) }
    }
}
