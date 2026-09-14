package dev.kotrace

import dev.kotrace.event.ExceptionEvent
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
 * [dev.kotrace.event.LogEvent], and, at the birthplace only ([birthplaceExceptionsAmong]), the span's
 * [dev.kotrace.event.ExceptionEvent]s. Report membership is that one declared predicate, not an implicit
 * filter: a [dev.kotrace.event.NamedEvent] is not reportable — a product/analytics occurrence fanned out
 * live (see [LiveAdapter]), not tail-buffered for failure — and a future event kind must classify itself
 * there or fail to compile.
 * Each adapter filters the collected entries through its [TracePolicy] ([accepts]); the [dev.kotrace.event.ExceptionEvent]
 * is subject only to [TracePolicy.acceptsEvent], which defaults to keeping it, so a breadcrumb filter never
 * swallows the crash cause unless a policy explicitly covers exceptions.
 *
 * [attached] carries **trace-level orphan failures** — throwables that belong to the trace as a whole but
 * are the birthplace of no span, the canonical case being a saga's suppressed rollback throwables collected
 * on the failed result value. Each is emitted as an [dev.kotrace.event.ExceptionRecord] keyed to the **root**
 * span (its `trace_id` / `operation`). They are appended **after** the tree walk on purpose: the birthplace
 * dedup ([birthplaceExceptionsAmong]) lives inside the walk, so a post-walk entry rides through it — an orphan
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

    val entries = ArrayList<WalkEntry>()
    fun walk(span: Span) {
        val children = all.childrenOf(span)
        val birthplaces = span.birthplaceExceptionsAmong(all)
        span.events.filter { it.reportable() }.sortedBy { it.atNanos }.forEach { event ->
            val collected = if (event is ExceptionEvent) event in birthplaces else true
            if (collected) entries += WalkEntry(span, event)
        }
        children.forEach(::walk)
    }
    walk(root)

    if (attached.isNotEmpty()) {
        val now = System.nanoTime()
        attached.forEach { entries += WalkEntry(root, ExceptionEvent(it, now)) }
    }

    // Guard the entire synchronous onReport per adapter (ADR-014): the adapter consumes its lazy view
    // in-call, so its policy/message faults are contained here too. A throwing sink never skips its
    // siblings or propagates into the boundary. (Retaining the sequence to consume after onReport returns
    // is unsupported — see ReportAdapter.onReport.)
    adapters.forEach { adapter ->
        guardAdapter(FaultPhase.REPORT, adapter, hook) { adapter.onReport(status, adapter.viewOf(entries)) }
    }
}

private class WalkEntry(val span: Span, val event: SpanEvent)

/**
 * Whether an event enters the failure **report** at all — the one declared statement of report membership.
 * Exhaustive over the sealed [SpanEvent], so a future event kind fails to compile until it is classified
 * here, rather than being silently omitted by whatever [reportTrace] happens to filter. This gates *whether* an
 * event is reportable, not *how* it is collected: [reportTrace] still branches a [LogEvent] (one record per span)
 * from an [ExceptionEvent] (the birthplace throwable, once).
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
private fun ReportAdapter.viewOf(entries: List<WalkEntry>): Sequence<TraceRecord> =
    entries.asSequence()
        .filter { policy.accepts(it.span, it.event) }
        .map { it.span.recordOf(it.event) }

/** Children of [parent], ordered by start — the tree edge is [Span.parentId] → [Span.spanId]. */
internal fun List<Span>.childrenOf(parent: Span): List<Span> =
    filter { it.parentId == parent.spanId }.sortedBy { it.startNanos }

/**
 * The [ExceptionEvent]s on this span that are **birthplaces** — the crash records that belong here. Decided
 * **per event by lineage key** (ADR-015), not once per span: an event is a birthplace iff **no descendant
 * span carries an [ExceptionEvent] with the same [ExceptionEvent.lineageKey]**.
 *
 * A single failure climbing the tree is re-recorded on every enclosing span ([dev.kotrace.span]); each copy
 * shares the deepest original's canonical key ([dev.kotrace.event.lineageKeyOf]), so only the deepest span is
 * a birthplace and the ancestors' copies are dropped — the ADR-005 climb collapse, but keyed by lineage so it
 * survives coroutine stacktrace-recovery copies and so a **recover-and-rethrow-different** flow no longer
 * drops the escaping failure: two unrelated throwables have different keys, so both report, each at its span
 * (B01). Keys are compared by **identity** (an [java.util.IdentityHashMap]-backed set), never `equals`, so a
 * throwable overriding equality cannot merge distinct lineages.
 *
 * A span with no [ExceptionEvent] yields an empty list (it emits no crash record), so the old throwable-less
 * ERROR leaf still shadows nothing (ADR-005). Shared by [reportTrace] and [renderTree]; [all] is the whole
 * span list, walked to test descendants.
 */
internal fun Span.birthplaceExceptionsAmong(all: List<Span>): List<ExceptionEvent> {
    val mine = events.filterIsInstance<ExceptionEvent>()
    if (mine.isEmpty()) return emptyList()
    val byId = all.associateBy(Span::spanId)
    fun descendsFromThis(candidate: Span): Boolean {
        var cursor = byId[candidate.parentId]
        while (cursor != null) {
            if (cursor.spanId == spanId) return true
            cursor = byId[cursor.parentId]
        }
        return false
    }
    val descendantKeys: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    all.asSequence()
        .filter { it.spanId != spanId && descendsFromThis(it) }
        .flatMap { it.events.asSequence().filterIsInstance<ExceptionEvent>() }
        .forEach { descendantKeys += it.lineageKey }
    return mine.filter { it.lineageKey !in descendantKeys }
}
