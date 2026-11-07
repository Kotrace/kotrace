package dev.kotrace.report

import dev.kotrace.AttachedFailure
import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.adapter.accepts
import dev.kotrace.adapter.attributeView
import dev.kotrace.bridge.runIsolated
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.ExceptionOrigin
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.recordOf
import dev.kotrace.event.spanRecordOf
import dev.kotrace.resolvedThreadConfig
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Fans the finished trace out to every [ReportAdapter] in the active [TraceConfig]. Each adapter receives a lazy
 * [Sequence] filtered by its own [TracePolicy], built from one shared walk of the tree. With no [TraceConfig] or no
 * [ReportAdapter] in scope this is a no-op.
 *
 * [status] is handed to [ReportAdapter.onReport] before any record is forced, so an adapter can skip an outcome it
 * does not want at no cost (see the [ReportAdapter.onReport] sample).
 *
 * The report holds, per span in time order, every event [SpanEvent.reportable] admits: each
 * [dev.kotrace.event.LogEvent], and each [dev.kotrace.event.ExceptionEvent] of a climb stamped
 * [dev.kotrace.event.ExceptionOrigin] BIRTHPLACE (the deepest span carrying it) or PROPAGATED (ADR-019). A
 * [dev.kotrace.event.NamedEvent] is live-only. By default an adapter drops PROPAGATED copies
 * ([TracePolicy.acceptsPropagatedException]) before [TracePolicy.acceptsEvent] runs, so a crash sink sees each
 * failure once, at its birthplace. A consumer's [dev.kotrace.event.addException] record is stamped EXPLICIT
 * (ADR-040) and kept by default; [TracePolicy.acceptsExplicitException] can drop it before [TracePolicy.acceptsEvent].
 *
 * Failures registered by [dev.kotrace.event.attachException] follow the tree walk in registration order, on their
 * owner spans and with their registration times. The registry is copied once when reporting begins; a later
 * registration belongs to a later report. Reports do not drain it. Both attachment routes are stamped ATTACHED.
 *
 * [attached] carries **trace-level orphan failures**, throwables that are no span's birthplace (a saga's
 * suppressed rollbacks). Each is reported as an [dev.kotrace.event.ExceptionRecord] of the root, after the registry so
 * the birthplace dedup cannot drop it, and never written onto [Span.events], where it would shadow the tree's
 * real crash origin. It is read once, when the report begins: a list that throws costs only the orphans (the
 * [FaultHook] hears of it with [FaultPhase.REPORT]), never the report.
 *
 * **Spans themselves, on request** (ADR-044). An adapter whose [TracePolicy.acceptsSpanRecords] is set also receives
 * one [dev.kotrace.event.SpanRecord] per span it accepts ([TracePolicy.acceptsSpan]), just before that span's events,
 * with the span's status and end as they stood when the report began: a span still open then has no end.
 *
 * **Every root is reported** (ADR-026). A manual collector can hold several trees: two top-level `span {}` calls,
 * or spans whose parent it does not hold. Each is walked in start order into the one report, keeping its own
 * `trace_id` / `parent_id`; [attached] is keyed to the earliest root.
 */
fun SpanCollector.reportTrace(status: TraceStatus, attached: List<Throwable> = emptyList()) {
    val config = resolvedThreadConfig()
    val adapters = config?.reportAdapters.orEmpty()
    if (adapters.isEmpty()) return
    val all = spans
    if (all.isEmpty()) return
    // Fix the report's content now (ADR-019/029/044): an event appended later, by an adapter's own onReport or a late
    // bridge, must not enter it, and a span ending later stays open in it.
    val frozen = FrozenSpans(all)
    val registered = runIsolated(FaultPhase.REPORT, "report.attach_registry", recover = { emptyList() }) {
        val copy = attachedSnapshot()
        if (copy.isEmpty()) copy else {
            val membership = Collections.newSetFromMap(IdentityHashMap<Span, Boolean>())
            membership.addAll(all)
            copy.filter { it.owner in membership }
        }
    }
    val orphans = runIsolated(FaultPhase.REPORT, "report.attached", recover = { emptyList() }) { attached.toList() }
    val reportedAt = System.nanoTime()

    // Walked on the first adapter's read and shared, so an adapter that gates on status pays no walk (ADR-029).
    val entries by lazy { walkEntries(all, registered, orphans, reportedAt, frozen) }

    // An adapter consumes its view in-call, so this guard also contains its policy and message faults (ADR-014).
    adapters.forEach { adapter ->
        runIsolated(FaultPhase.REPORT, "report.deliver", recover = {}) {
            adapter.onReport(status, adapter.viewOf(frozen) { entries })
        }
    }
}

/** Every root's walk in tree order, then [registered] and [attached], stamped with their origins. */
private fun walkEntries(
    all: List<Span>,
    registered: List<AttachedFailure>,
    attached: List<Throwable>,
    reportedAt: Long,
    frozen: FrozenSpans,
): WalkEntries {
    val roots = rootsOf(all)
    val tree = TraceTreeIndex(all, roots, frozen)

    val entries = ArrayList<EventEntry>()
    val spans = ArrayList<Span>(all.size)
    val eventOffsets = IntArray(all.size + 1)
    fun walk(span: Span) {
        eventOffsets[spans.size] = entries.size
        spans += span
        val birthplaces = tree.birthplaceExceptionsOf(span)
        // The same snapshot the birthplace index used, so a late append cannot be mislabelled (ADR-019).
        tree.eventsOf(span).filter { it.reportable() }.sortedWith(byTime).forEach { event ->
            val origin = when (event) {
                !is ExceptionEvent -> null
                else -> when {
                    event.explicit -> ExceptionOrigin.EXPLICIT
                    event in birthplaces -> ExceptionOrigin.BIRTHPLACE
                    else -> ExceptionOrigin.PROPAGATED
                }
            }
            entries += EventEntry(span, event, origin)
        }
        tree.childrenOf(span).forEach(::walk)
    }
    roots.forEach(::walk)
    eventOffsets[spans.size] = entries.size

    registered.forEach { entries += EventEntry(it.owner, ExceptionEvent(it.throwable, it.atNanos), ExceptionOrigin.ATTACHED) }
    if (attached.isNotEmpty()) {
        val root = roots.first()
        attached.forEach { entries += EventEntry(root, ExceptionEvent(it, reportedAt), ExceptionOrigin.ATTACHED) }
    }
    return WalkEntries(entries, spans, eventOffsets)
}

/** Spans in walk order and their event ranges, followed by the attached orphans in [events]. */
private class WalkEntries(val events: List<EventEntry>, val spans: List<Span>, val eventOffsets: IntArray)

private class EventEntry(val span: Span, val event: SpanEvent, val origin: ExceptionOrigin?)

/**
 * Whether an event enters the report at all. Exhaustive over the sealed [SpanEvent], so a new event kind fails to
 * compile until it is classified here. A [NamedEvent] is live-only, so it is not reportable.
 */
internal fun SpanEvent.reportable(): Boolean = when (this) {
    is LogEvent -> true
    is NamedEvent -> false
    is ExceptionEvent -> true
}

/**
 * This adapter's lazy view of the walked [entries], filtered by the same [accepts] predicate the live path uses.
 * A span entry survives only when [TracePolicy.acceptsSpanRecords] is set and [TracePolicy.acceptsSpan] accepts its
 * span (ADR-044). A [dev.kotrace.event.TraceRecord] is built only for an entry that survives, holding only the
 * attributes [TracePolicy.acceptsAttribute] admits (ADR-039).
 */
private fun ReportAdapter.viewOf(frozen: FrozenSpans, entries: () -> WalkEntries): Sequence<TraceRecord> {
    // Read on consumption, so a throwing getter cannot abort before the adapter's status gate ran (ADR-019).
    val keepPropagated by lazy { policy.acceptsPropagatedException }
    val keepExplicit by lazy { policy.acceptsExplicitException }
    val keepSpans by lazy { policy.acceptsSpanRecords }
    fun recordOf(entry: EventEntry): TraceRecord? {
        // The origin gates run before policy.accepts, so a default adapter's policy never sees a dropped copy.
        if (!(keepPropagated || entry.origin != ExceptionOrigin.PROPAGATED) ||
            !(keepExplicit || entry.origin != ExceptionOrigin.EXPLICIT) ||
            !policy.accepts(entry.span, entry.event)
        ) return null
        return entry.span.recordOf(entry.event, origin = entry.origin)
    }

    return sequence {
        val walked = entries()
        var eventIndex = 0
        if (keepSpans) {
            walked.spans.forEachIndexed { index, span ->
                if (policy.acceptsSpan(span)) yield(span.spanRecordOf(frozen.lifecycleOf(span)))
                while (eventIndex < walked.eventOffsets[index + 1]) {
                    recordOf(walked.events[eventIndex++])?.let { yield(it) }
                }
            }
        }
        // Without span records this is the whole walk; otherwise only the attached orphans remain.
        while (eventIndex < walked.events.size) {
            recordOf(walked.events[eventIndex++])?.let { yield(it) }
        }
    }.map { policy.attributeView(it, FaultPhase.REPORT, "report.attribute") }
}
