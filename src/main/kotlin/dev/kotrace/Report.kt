package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.exception
import dev.kotrace.event.recordOf

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
 * [dev.kotrace.event.LogEvent], and, at the birthplace only ([isBirthplaceAmong]), the span's
 * [dev.kotrace.event.ExceptionEvent]. Report membership is that one declared predicate, not an implicit
 * filter: a [dev.kotrace.event.NamedEvent] is not reportable — a product/analytics occurrence fanned out
 * live (see [LiveAdapter]), not tail-buffered for failure — and a future event kind must classify itself
 * there or fail to compile.
 * Each adapter filters the collected entries through its [TracePolicy] ([accepts]); the [dev.kotrace.event.ExceptionEvent]
 * is subject only to [TracePolicy.acceptsEvent], which defaults to keeping it, so a breadcrumb filter never
 * swallows the crash cause unless a policy explicitly covers exceptions.
 */
fun SpanCollector.reportTrace(status: TraceStatus) {
    val adapters = currentThreadConfig()?.reportAdapters.orEmpty()
    if (adapters.isEmpty()) return
    val all = spans
    val root = all.firstOrNull { it.parentId == null } ?: return

    val entries = ArrayList<WalkEntry>()
    fun walk(span: Span) {
        val children = all.childrenOf(span)
        val birthplace = span.isBirthplaceAmong(all)
        span.events.filter { it.reportable() }.sortedBy { it.atNanos }.forEach { event ->
            val collected = if (event is ExceptionEvent) birthplace else true
            if (collected) entries += WalkEntry(span, event)
        }
        children.forEach(::walk)
    }
    walk(root)

    adapters.forEach { adapter -> adapter.onReport(status, adapter.viewOf(entries)) }
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
 * A span is a **birthplace** iff it carries the throwable ([exception] != null) and no span in its subtree
 * does — the deepest throwable-bearing failure on its branch. Only there does the crash record belong;
 * enclosing spans re-record the same throwable as it climbs ([dev.kotrace.span]) but are not the origin.
 *
 * The test is throwable-presence, **not** "ERROR with no ERROR child" (ADR-005). A throwable-less ERROR leaf
 * is representable — a bridge span ended `end(ERROR, error = null)`, e.g. an OkHttp 500 that returned rather
 * than threw (`dev.kotrace.okhttp` `TracingInterceptor`). Under the old status-only predicate such a leaf
 * counted as the branch's birthplace and, having no [dev.kotrace.event.ExceptionEvent], emitted nothing —
 * while its ancestor's real throwable, no longer "leaf-most", was silently dropped from the report. Gating
 * on the throwable keeps birthplace aligned with the crash record actually emitted, and makes it agree with
 * every consumer reading the report's [dev.kotrace.event.ExceptionRecord]s. Shared by [reportTrace] and
 * [renderTree]; [all] is the whole span list, walked to test descendants.
 */
internal fun Span.isBirthplaceAmong(all: List<Span>): Boolean {
    if (exception == null) return false
    val byId = all.associateBy(Span::spanId)
    fun descendsFromThis(candidate: Span): Boolean {
        var cursor = byId[candidate.parentId]
        while (cursor != null) {
            if (cursor.spanId == spanId) return true
            cursor = byId[cursor.parentId]
        }
        return false
    }
    return all.none { it.spanId != spanId && it.exception != null && descendsFromThis(it) }
}
