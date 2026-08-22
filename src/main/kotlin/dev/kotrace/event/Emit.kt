package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceConfig
import dev.kotrace.accepts

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
internal fun Span.emit(event: SpanEvent, config: TraceConfig?) {
    events += event
    val accepting = config?.liveAdapters?.filter { it.policy.accepts(this, event) }.orEmpty()
    if (accepting.isEmpty()) return
    val record = recordOf(event)
    accepting.forEach { it.onLive(record) }
}
