package dev.kotrace.report

import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.adapter.accepts
import dev.kotrace.bridge.runIsolated
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.ExceptionOrigin
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.recordOf
import dev.kotrace.resolvedThreadConfig

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
 * failure once, at its birthplace.
 *
 * [attached] carries **trace-level orphan failures**, throwables that are no span's birthplace (a saga's
 * suppressed rollbacks). Each is reported as an [dev.kotrace.event.ExceptionRecord] of the root, after the walk so
 * the birthplace dedup cannot drop it, and never written onto [Span.events], where it would shadow the tree's
 * real crash origin. It is read once, when the report begins: a list that throws costs only the orphans (the
 * [FaultHook] hears of it with [FaultPhase.REPORT]), never the report.
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
    // Fix the report's content now (ADR-019/029): an event appended later, by an adapter's own onReport or a late
    // bridge, must not enter it.
    val frozen = FrozenTimelines(all)
    val orphans = runIsolated(FaultPhase.REPORT, "report.attached", recover = { emptyList() }) { attached.toList() }
    val reportedAt = System.nanoTime()

    // Walked on the first adapter's read and shared, so an adapter that gates on status pays no walk (ADR-029).
    val entries by lazy { walkEntries(all, orphans, reportedAt, frozen) }

    // An adapter consumes its view in-call, so this guard also contains its policy and message faults (ADR-014).
    adapters.forEach { adapter ->
        runIsolated(FaultPhase.REPORT, "report.deliver", recover = {}) {
            adapter.onReport(status, adapter.viewOf { entries })
        }
    }
}

/** The whole trace as origin-stamped entries: every root's walk in tree order, then [attached]. */
private fun walkEntries(
    all: List<Span>,
    attached: List<Throwable>,
    reportedAt: Long,
    frozen: FrozenTimelines,
): List<WalkEntry> {
    val roots = rootsOf(all)
    val tree = TraceTreeIndex(all, roots, frozen)

    val entries = ArrayList<WalkEntry>()
    fun walk(span: Span) {
        val birthplaces = tree.birthplaceExceptionsOf(span)
        // The same snapshot the birthplace index used, so a late append cannot be mislabelled (ADR-019).
        tree.eventsOf(span).filter { it.reportable() }.sortedWith(byTime).forEach { event ->
            val origin = when (event) {
                !is ExceptionEvent -> null
                in birthplaces -> ExceptionOrigin.BIRTHPLACE
                else -> ExceptionOrigin.PROPAGATED
            }
            entries += WalkEntry(span, event, origin)
        }
        tree.childrenOf(span).forEach(::walk)
    }
    roots.forEach(::walk)

    if (attached.isNotEmpty()) {
        val root = roots.first()
        // An orphan has no deeper copy: BIRTHPLACE, so the propagated gate never drops it.
        attached.forEach { entries += WalkEntry(root, ExceptionEvent(it, reportedAt), ExceptionOrigin.BIRTHPLACE) }
    }
    return entries
}

private class WalkEntry(val span: Span, val event: SpanEvent, val origin: ExceptionOrigin?)

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
 * A [dev.kotrace.event.TraceRecord] is built only for an entry that survives.
 */
private fun ReportAdapter.viewOf(entries: () -> List<WalkEntry>): Sequence<TraceRecord> {
    // Read on consumption, so a throwing getter cannot abort before the adapter's status gate ran (ADR-019).
    val keepPropagated by lazy { policy.acceptsPropagatedException }
    return Sequence { entries().iterator() }
        // Before policy.accepts, so a default adapter's policy never sees a dropped copy.
        .filter { keepPropagated || it.origin != ExceptionOrigin.PROPAGATED }
        .filter { policy.accepts(it.span, it.event) }
        .map { it.span.recordOf(it.event, origin = it.origin) }
}
