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
 * Fans the finished trace out to every [ReportAdapter] in the active [TraceConfig] — the one report
 * fan-out path. A single depth-first walk builds the record stream once; each adapter then receives a
 * lazy [Sequence] filtered by its own [TracePolicy], so a report split across adapters (a success sink, a
 * failure sink, a layer-scoped sink) still costs one walk. With no [TraceConfig] or no [ReportAdapter] in
 * scope this is a no-op.
 *
 * [status] is the trace's verdict, handed to each [ReportAdapter.onReport] before its records are forced, so
 * an adapter can self-gate on the outcome it wants — the sequence is lazy, so a skipped outcome forces no
 * work (see the [ReportAdapter.onReport] sample).
 *
 * The walk collects, per span, every event [SpanEvent.reportable] admits, in time order — each
 * [dev.kotrace.event.LogEvent] and **every** [dev.kotrace.event.ExceptionEvent] on the climb, each stamped
 * [dev.kotrace.event.ExceptionOrigin] BIRTHPLACE (deepest span carrying the lineage,
 * [TraceTreeIndex.birthplaceExceptionsOf]) or PROPAGATED (an ancestor copy) — ADR-019. Report membership is that one
 * declared predicate, not an implicit filter: a [dev.kotrace.event.NamedEvent] is not reportable — a
 * product/analytics occurrence fanned out live (see [LiveAdapter]), not tail-buffered for failure — and a future
 * event kind must classify itself there or fail to compile.
 * Each adapter filters the collected entries through its own view ([viewOf]): the birthplace **dedup** is now a
 * per-adapter gate ([TracePolicy.acceptsPropagatedException], default drops PROPAGATED, so a default crash sink
 * still sees birthplaces only), applied *before* [TracePolicy.acceptsEvent] (which still gates a surviving
 * [dev.kotrace.event.ExceptionEvent] and defaults to keeping it — a breadcrumb filter never swallows the crash
 * cause unless a policy explicitly covers exceptions).
 *
 * [attached] carries **trace-level orphan failures** — throwables that belong to the trace as a whole but
 * are the birthplace of no span, the canonical case being a saga's suppressed rollback throwables collected
 * on the failed result value. Each is emitted as an [dev.kotrace.event.ExceptionRecord] keyed to the **root**
 * span (its `trace_id` / `operation`). They are appended **after** the tree walk on purpose: the birthplace
 * dedup ([TraceTreeIndex.birthplaceExceptionsOf]) lives inside the walk, so a post-walk entry rides through it — an orphan
 * failure must not be silenced just because it is not the leaf-most throwable on a branch. They are
 * deliberately **not** written onto [Span.events]: doing so would let one flip the root into a birthplace and
 * shadow the tree's real crash origin. With no throwable to attach [attached] is empty and this is inert.
 */
fun SpanCollector.reportTrace(status: TraceStatus, attached: List<Throwable> = emptyList()) {
    val config = resolvedThreadConfig()
    val adapters = config?.reportAdapters.orEmpty()
    if (adapters.isEmpty()) return
    val hook = config?.faultHook
    val all = spans
    val root = all.firstOrNull { it.parentId == null } ?: return
    val tree = TraceTreeIndex(all, root)

    val entries = ArrayList<WalkEntry>()
    fun walk(span: Span) {
        val birthplaces = tree.birthplaceExceptionsOf(span)
        // Read the SAME event snapshot the birthplace index used (ADR-019): a late off-thread/bridge append
        // between indexing and the walk would otherwise be collected here yet missing from `birthplaces`,
        // mislabelling it. One snapshot per span keeps classification internally consistent.
        tree.eventsOf(span).filter { it.reportable() }.sortedBy { it.atNanos }.forEach { event ->
            // No drop (ADR-019): every exception copy is collected and stamped BIRTHPLACE (deepest carrying the
            // lineage) or PROPAGATED (an ancestor copy); a non-exception event has no origin. The per-adapter
            // gate in viewOf decides which survive, so a crash sink still sees birthplaces only by default.
            val origin = when {
                event !is ExceptionEvent -> null
                event in birthplaces -> ExceptionOrigin.BIRTHPLACE
                else -> ExceptionOrigin.PROPAGATED
            }
            entries += WalkEntry(span, event, origin)
        }
        tree.childrenOf(span).forEach(::walk)
    }
    walk(root)

    if (attached.isNotEmpty()) {
        val now = System.nanoTime()
        // Orphans are origins with no deeper copy — stamped BIRTHPLACE so the propagated gate never drops them.
        attached.forEach { entries += WalkEntry(root, ExceptionEvent(it, now), ExceptionOrigin.BIRTHPLACE) }
    }

    // Guard the entire synchronous onReport per adapter (ADR-014): the adapter consumes its lazy view
    // in-call, so its policy/message faults are contained here too. A throwing sink never skips its
    // siblings or propagates into the boundary. (Retaining the sequence to consume after onReport returns
    // is unsupported — see ReportAdapter.onReport.)
    adapters.forEach { adapter ->
        guardAdapter(FaultPhase.REPORT, adapter, hook) { adapter.onReport(status, adapter.viewOf(entries)) }
    }
}

private class WalkEntry(val span: Span, val event: SpanEvent, val origin: ExceptionOrigin?)

/**
 * Whether an event enters the failure **report** at all — the one declared statement of report membership.
 * Exhaustive over the sealed [SpanEvent], so a future event kind fails to compile until it is classified
 * here, rather than being silently omitted by whatever [reportTrace] happens to filter. This gates *whether* an
 * event is reportable, not *how* it is collected: the walk retains every reportable [LogEvent] and every
 * [ExceptionEvent] copy (origin-stamped), and the per-adapter [viewOf] decides which survive — a default view
 * dedups the exception climb to its birthplace, an opt-in one keeps the whole climb (ADR-019).
 *
 * A [NamedEvent] is a product/analytics occurrence — live-only, fanned out to a [LiveAdapter] as it happens,
 * never tail-buffered for failure — so it is not reportable.
 */
internal fun SpanEvent.reportable(): Boolean = when (this) {
    is LogEvent -> true
    is NamedEvent -> false
    is ExceptionEvent -> true
}

/**
 * This adapter's lazy view of the walked [entries], filtered by the one [accepts] predicate the live path
 * also uses: a [LogEvent] passes its policy's span / event / sensitive gates; an [ExceptionEvent] passes
 * unless the policy's [TracePolicy.acceptsEvent] deliberately drops it (default: kept). Only when an entry
 * survives is its [dev.kotrace.event.TraceRecord] built — a [LogEvent]'s lazy message resolves here, once.
 */
private fun ReportAdapter.viewOf(entries: List<WalkEntry>): Sequence<TraceRecord> {
    // Read the propagated gate ONCE, and only when the sequence is consumed (ADR-019): reading it eagerly at
    // viewOf-build time would let a throwing getter abort before an adapter's status self-gate ran.
    val keepPropagated by lazy { policy.acceptsPropagatedException }
    return entries.asSequence()
        // Propagated gate FIRST — before policy.accepts — so a default adapter's policy is never invoked on a
        // dropped copy (its side effects, and a throwing policy, stay off the dropped entries): the pre-ADR-019
        // "policy never sees a non-birthplace" invariant is preserved exactly.
        .filter { keepPropagated || it.origin != ExceptionOrigin.PROPAGATED }
        .filter { policy.accepts(it.span, it.event) }
        .map { it.span.recordOf(it.event, origin = it.origin) }
}

/**
 * One immutable topology view for a report/render operation. Children are grouped and sorted once rather
 * than found by scanning the complete span list at every node. Birthplaces are indexed in the same post-order
 * traversal, so report and [renderTree] share both ordering and exception-dedup semantics.
 *
 * [birthplaceExceptionsOf] decides per event by lineage key (ADR-015): an event belongs to this span iff no
 * descendant carries the same [ExceptionEvent.lineageKey]. A single climbing failure therefore collapses to
 * its deepest span, while unrelated failures remain distinct. Identity-backed sets preserve the contract for
 * throwables with hostile or value-based `equals` implementations.
 *
 * Subtree key sets use small-to-large merging: a child set is reused after its birthplace result is final,
 * and smaller sibling sets merge into the largest. This avoids copying every accumulated key at each parent;
 * a trace without exceptions allocates no lineage sets.
 */
internal class TraceTreeIndex(all: List<Span>, root: Span) {
    private val childrenByParentId: Map<String, List<Span>> = indexChildren(all)

    private var birthplacesBySpan: IdentityHashMap<Span, List<ExceptionEvent>>? = null

    /**
     * One immutable **event snapshot per span** (ADR-019), taken once during indexing and reused by the walk
     * ([eventsOf]). [Span.events] is copy-on-write and may receive a late off-thread/bridge append; indexing
     * and walking the *same* snapshot keeps birthplace classification consistent regardless of such a writer.
     */
    private val eventsBySpan = IdentityHashMap<Span, List<SpanEvent>>()

    init {
        indexBirthplaces(root)
    }

    /** Children of [parent], ordered by start — the tree edge is [Span.parentId] → [Span.spanId]. */
    fun childrenOf(parent: Span): List<Span> = childrenByParentId[parent.spanId].orEmpty()

    /** The exception events that belong at [span], excluding copies of a lineage found below it. */
    fun birthplaceExceptionsOf(span: Span): List<ExceptionEvent> = birthplacesBySpan?.get(span).orEmpty()

    /** The single event snapshot indexed for [span] — the walk reads this, not [Span.events], for consistency. */
    fun eventsOf(span: Span): List<SpanEvent> = eventsBySpan[span].orEmpty()

    private fun indexBirthplaces(span: Span): MutableSet<Any>? {
        // Snapshot this span's events ONCE, before anything reads them, so the walk sees exactly what the
        // birthplace index saw (ADR-019). Taken for every span, including exception-free ones (the walk needs
        // it for logs too), so it precedes the mine.isEmpty() early return below.
        val events = span.events.toList()
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
