package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.ExceptionOrigin
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.recordOf
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
 * failure once, at its birthplace.
 *
 * [attached] carries **trace-level orphan failures**, throwables that are no span's birthplace (a saga's
 * suppressed rollbacks). Each is reported as an [dev.kotrace.event.ExceptionRecord] of the root, after the walk so
 * the birthplace dedup cannot drop it, and never written onto [Span.events], where it would shadow the tree's
 * real crash origin. It is read once, when the report begins: a list that throws costs only the orphans (the
 * [AdapterFaultHook] hears of it with [FaultPhase.REPORT]), never the report.
 *
 * **Every root is reported** (ADR-026). A manual collector can hold several trees: two top-level `span {}` calls,
 * or spans whose parent it does not hold. Each is walked in start order into the one report, keeping its own
 * `trace_id` / `parent_id`; [attached] is keyed to the earliest root.
 */
fun SpanCollector.reportTrace(status: TraceStatus, attached: List<Throwable> = emptyList()) {
    val config = resolvedThreadConfig()
    val adapters = config?.reportAdapters.orEmpty()
    if (adapters.isEmpty()) return
    val hook = config?.faultHook
    val all = spans
    if (all.isEmpty()) return
    // Fix the report's content now (ADR-019/029): an event appended later, by an adapter's own onReport or a late
    // bridge, must not enter it.
    val frozen = FrozenTimelines(all)
    val orphans = guardShared(FaultPhase.REPORT, hook) { attached.toList() } ?: emptyList()
    val reportedAt = System.nanoTime()

    // Walked on the first adapter's read and shared, so an adapter that gates on status pays no walk (ADR-029).
    val entries by lazy { walkEntries(all, orphans, reportedAt, frozen::of) }

    // An adapter consumes its view in-call, so this guard also contains its policy and message faults (ADR-014).
    adapters.forEach { adapter ->
        guardAdapter(FaultPhase.REPORT, adapter, hook) { adapter.onReport(status, adapter.viewOf { entries }) }
    }
}

/** The whole trace as origin-stamped entries: every root's walk in tree order, then [attached]. */
private fun walkEntries(
    all: List<Span>,
    attached: List<Throwable>,
    reportedAt: Long,
    frozenEvents: (Span) -> List<SpanEvent>,
): List<WalkEntry> {
    val roots = rootsOf(all)
    val tree = TraceTreeIndex(all, roots, frozenEvents)

    val entries = ArrayList<WalkEntry>()
    fun walk(span: Span) {
        val birthplaces = tree.birthplaceExceptionsOf(span)
        // The same snapshot the birthplace index used, so a late append cannot be mislabelled (ADR-019).
        tree.eventsOf(span).filter { it.reportable() }.sortedBy { it.atNanos }.forEach { event ->
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
 * Every span's timeline as it stood when a report began (ADR-019/029). Construction takes an O(1) copy-on-write
 * iterator per span; [of] drains it on first read and keeps the list, so a walk retried after a fault reads the
 * same timelines. Read only inside the report's synchronized lazy walk, so it needs no lock.
 */
internal class FrozenTimelines(spans: List<Span>) {
    private val snapshots = IdentityHashMap<Span, Iterator<SpanEvent>>(spans.size).apply {
        spans.forEach { put(it, it.eventBuffer.iterator()) }
    }

    /** Allocated on the first read, so a report no adapter consumes pays only for the snapshots. */
    private var drained: IdentityHashMap<Span, List<SpanEvent>>? = null

    /** [span]'s frozen timeline; the same list on every call. */
    fun of(span: Span): List<SpanEvent> {
        val cache = drained ?: IdentityHashMap<Span, List<SpanEvent>>(snapshots.size).also { drained = it }
        return cache.getOrPut(span) { snapshots.getValue(span).asSequence().toList() }
    }
}

/**
 * The roots in [all], earliest first (ADR-026): every span with no parent, or whose parent is not in [all] (a
 * subtree a nested manual collector recorded under someone else's span).
 */
internal fun rootsOf(all: List<Span>): List<Span> {
    if (all.size <= 1) return all
    val ids = all.mapTo(HashSet(all.size)) { it.spanId }
    return all.filter { it.parentId == null || it.parentId !in ids }.sortedBy { it.startNanos }
}

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

/**
 * One topology view for a report or [renderTree]: children grouped and sorted once, and birthplaces indexed in the
 * same post-order pass, so both share ordering and dedup.
 *
 * An exception event is a birthplace iff no descendant carries the same [ExceptionEvent.lineageKey] (ADR-015), so
 * a climb collapses to its deepest span while unrelated failures stay distinct. Sets are identity-backed, immune to
 * a throwable's `equals`, and merged small-to-large; a trace without exceptions allocates none.
 */
internal class TraceTreeIndex(
    all: List<Span>,
    roots: List<Span>,
    private val snapshotOf: (Span) -> List<SpanEvent> = { it.eventBuffer.toList() },
) {
    private val childrenByParentId: Map<String, List<Span>> = indexChildren(all)

    private var birthplacesBySpan: IdentityHashMap<Span, List<ExceptionEvent>>? = null

    /** One event snapshot per span, shared by indexing and the walk so a late append cannot split them (ADR-019). */
    private val eventsBySpan = IdentityHashMap<Span, List<SpanEvent>>()

    init {
        // Lineage keys never merge across roots: a throwable seen under two roots is a birthplace in each.
        roots.forEach { indexBirthplaces(it) }
    }

    /** Children of [parent], ordered by start. */
    fun childrenOf(parent: Span): List<Span> = childrenByParentId[parent.spanId].orEmpty()

    /** The exception events that belong at [span], excluding copies of a lineage found below it. */
    fun birthplaceExceptionsOf(span: Span): List<ExceptionEvent> = birthplacesBySpan?.get(span).orEmpty()

    /** The event snapshot indexed for [span]. */
    fun eventsOf(span: Span): List<SpanEvent> = eventsBySpan[span].orEmpty()

    private fun indexBirthplaces(span: Span): MutableSet<Any>? {
        // Every span, exception-free ones included: the walk needs the snapshot for logs too.
        val events = snapshotOf(span)
        eventsBySpan[span] = events

        var subtreeKeys: MutableSet<Any>? = null
        childrenOf(span).forEach { child ->
            val childKeys = indexBirthplaces(child) ?: return@forEach
            val accumulated = subtreeKeys
            when {
                accumulated == null -> subtreeKeys = childKeys
                accumulated.size < childKeys.size -> {
                    childKeys.addAll(accumulated)
                    subtreeKeys = childKeys
                }
                else -> accumulated.addAll(childKeys)
            }
        }

        val mine = events.filterIsInstance<ExceptionEvent>()
        if (mine.isEmpty()) return subtreeKeys

        val descendantKeys = subtreeKeys
        val birthplaces = if (descendantKeys == null) {
            mine
        } else {
            mine.filter { it.lineageKey !in descendantKeys }
        }
        val indexed = birthplacesBySpan ?: IdentityHashMap<Span, List<ExceptionEvent>>().also {
            birthplacesBySpan = it
        }
        indexed[span] = birthplaces

        val allKeys = descendantKeys ?: identitySet()
        mine.forEach { allKeys += it.lineageKey }
        return allKeys
    }

    private fun identitySet(): MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    private fun indexChildren(all: List<Span>): Map<String, List<Span>> {
        if (all.size <= 1) return emptyMap()
        val indexed = HashMap<String, MutableList<Span>>()
        all.forEach { span ->
            val parentId = span.parentId ?: return@forEach
            indexed.getOrPut(parentId, ::ArrayList) += span
        }
        indexed.values.forEach { children ->
            if (children.size > 1) children.sortBy(Span::startNanos)
        }
        return indexed
    }
}
