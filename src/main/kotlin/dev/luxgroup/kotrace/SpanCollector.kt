package dev.luxgroup.kotrace

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Trace-wide sink: every [Span] in one trace, so a reporter can render the whole tree rather than only
 * walk one span's ancestry. Seeded once at the root; children register themselves via [add] as they
 * open.
 *
 * Backed by a [CopyOnWriteArrayList] so a traced fan-out — parallel `async` children each opening a
 * child span — can [add] concurrently without corrupting the list. Traces hold a handful of spans, so
 * the copy-on-write cost is negligible. Note this hardens only the list: each [Span] is still mutated
 * by its own `trace`, and a parent only after its children join, so no span object is written from two
 * threads at once.
 */
class SpanCollector : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<SpanCollector>

    private val entries = CopyOnWriteArrayList<Span>()

    /** A snapshot of every span recorded so far, safe to iterate while [add] runs concurrently. */
    val spans: List<Span> get() = entries.toList()

    internal fun add(span: Span) {
        entries.add(span)
    }
}
