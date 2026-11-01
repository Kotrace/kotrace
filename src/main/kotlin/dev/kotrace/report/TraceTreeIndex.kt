package dev.kotrace.report

import dev.kotrace.Span
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.SpanEvent
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One topology view for a report or [renderTree]: children grouped and sorted once, and birthplaces indexed in the
 * same post-order pass, so both share ordering and dedup. Indexing and the walk read the same [timelines], so a late
 * append cannot split them (ADR-019).
 *
 * An exception event is a birthplace iff no descendant carries the same [ExceptionEvent.lineageKey] (ADR-015), so
 * a climb collapses to its deepest span while unrelated failures stay distinct. Sets are identity-backed, immune to
 * a throwable's `equals`, and merged small-to-large; a trace without exceptions allocates none.
 */
internal class TraceTreeIndex(
    all: List<Span>,
    roots: List<Span>,
    private val timelines: FrozenSpans,
) {
    private val childrenByParentId: Map<String, List<Span>> = indexChildren(all)

    private var birthplacesBySpan: IdentityHashMap<Span, List<ExceptionEvent>>? = null

    init {
        // Lineage keys never merge across roots: a throwable seen under two roots is a birthplace in each.
        roots.forEach { indexBirthplaces(it) }
    }

    /** Children of [parent], ordered by start. */
    fun childrenOf(parent: Span): List<Span> = childrenByParentId[parent.spanId].orEmpty()

    /** The exception events that belong at [span], excluding copies of a lineage found below it. */
    fun birthplaceExceptionsOf(span: Span): List<ExceptionEvent> = birthplacesBySpan?.get(span).orEmpty()

    /** The event snapshot indexed for [span]. */
    fun eventsOf(span: Span): List<SpanEvent> = timelines.of(span)

    /** [span]'s status and end when the report began (ADR-044). */
    fun lifecycleOf(span: Span): Span.State = timelines.lifecycleOf(span)

    private fun indexBirthplaces(span: Span): MutableSet<Any>? {
        val events = timelines.of(span)

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
        val indexed = HashMap<String, MutableList<Span>>(capacityFor(all.size))
        all.forEach { span ->
            val parentId = span.parentId ?: return@forEach
            indexed.getOrPut(parentId, ::ArrayList) += span
        }
        indexed.values.forEach { children ->
            if (children.size > 1) children.sortWith(byStart)
        }
        return indexed
    }
}

/**
 * Every span as it stood when a report began (ADR-019/029/044): its timeline, one [dev.kotrace.EventTimeline.snapshot]
 * taken in O(1), and its status and end, taken in one read ([Span.lifecycle]). An event appended later, by an
 * adapter or a late bridge, never enters it, a span ending later stays open in it, and a walk retried after a fault
 * reads the same values.
 *
 * A trace of at most [LINEAR_LOOKUP_SPANS] spans finds a span by scanning, with no map: a small trace pays almost
 * nothing to freeze, which a one-span report notices most.
 */
internal class FrozenSpans(private val spans: List<Span>) {
    // Parallel arrays rather than a holder per span: freezing allocates nothing per span beyond the snapshots.
    private val timelines: Array<List<SpanEvent>> = Array(spans.size) { spans[it].eventBuffer.snapshot() }
    private val lifecycles: Array<Span.State> = Array(spans.size) { spans[it].lifecycle() }

    private val indexBySpan: IdentityHashMap<Span, Int>? =
        if (spans.size <= LINEAR_LOOKUP_SPANS) null
        else IdentityHashMap<Span, Int>(spans.size).apply { spans.forEachIndexed { index, span -> put(span, index) } }

    /** [span]'s frozen timeline; the same list on every call. */
    fun of(span: Span): List<SpanEvent> = timelines[indexOf(span)]

    /** [span]'s status and end when the report began. */
    fun lifecycleOf(span: Span): Span.State = lifecycles[indexOf(span)]

    private fun indexOf(span: Span): Int {
        indexBySpan?.let { return it.getValue(span) }
        val index = spans.indexOfFirst { it === span }
        require(index >= 0) { "span ${span.spanId} is not part of this report" }
        return index
    }

    private companion object {
        const val LINEAR_LOOKUP_SPANS = 8
    }
}

/**
 * The roots in [all], earliest first (ADR-026): every span with no parent, or whose parent is not in [all] (a
 * subtree a nested manual collector recorded under someone else's span).
 */
internal fun rootsOf(all: List<Span>): List<Span> {
    if (all.size <= 1) return all
    val ids = all.mapTo(HashSet(capacityFor(all.size))) { it.spanId }
    return all.filter { it.parentId == null || it.parentId !in ids }.sortedWith(byStart)
}

/**
 * Spans by start, comparing the primitive: a `sortBy { it.startNanos }` boxes two `Long`s per comparison, which was
 * nearly half of a 512-span report's allocation.
 */
internal val byStart: Comparator<Span> = Comparator { a, b -> a.startNanos.compareTo(b.startNanos) }

/** A hash capacity that holds [size] entries without resizing at the default load factor. */
private fun capacityFor(size: Int): Int = size * 4 / 3 + 1

/** Events by time, comparing the primitive, like [byStart]. */
internal val byTime: Comparator<SpanEvent> = Comparator { a, b -> a.atNanos.compareTo(b.atNanos) }
