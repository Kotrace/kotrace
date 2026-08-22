package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.CopyOnWriteArrayList

private val currentCollectorThreadLocal = ThreadLocal<SpanCollector?>()

/**
 * The [SpanCollector] active in the current coroutine, or null — read straight from the `CoroutineContext`,
 * the source of truth. This is the getter a **suspend** caller uses; it is the collector counterpart of
 * [currentSpan]. Non-suspend code off the coroutine frame reads the [currentThreadCollector] mirror instead.
 */
suspend fun currentCollector(): SpanCollector? = currentCoroutineContext()[SpanCollector]

/**
 * The [SpanCollector] active on *this thread*, or null — the collector counterpart of [currentThreadSpan]. A
 * non-suspend call running on a coroutine's thread (the OkHttp `Call.Factory`, which has no
 * `coroutineContext` handle) reads it to register a child span into the trace it belongs to. Maintained
 * by [SpanCollector]'s [ThreadContextElement] mirror, same mechanism as [SpanContext]. A genuine suspend
 * caller should prefer [currentCollector], which reads the context directly rather than the mirror.
 */
fun currentThreadCollector(): SpanCollector? = currentCollectorThreadLocal.get()

/**
 * Trace-wide buffer: every [Span] in one trace, so the whole tree can be rendered or fanned out rather
 * than only walking one span's ancestry. Not a sink — the adapters are; this only holds the spans until
 * report time. Seeded once at the root; children register themselves via [add] as they open.
 *
 * Backed by a [CopyOnWriteArrayList] so a traced fan-out — parallel `async` children each opening a
 * child span — can [add] concurrently without corrupting the list. Traces hold a handful of spans, so
 * the copy-on-write cost is negligible. Note this hardens only the list: each [Span] is still mutated
 * by its own `trace`, and a parent only after its children join, so no span object is written from two
 * threads at once.
 *
 * As a [ThreadContextElement] it mirrors itself onto a ThreadLocal on every resume and restores the
 * previous on the way out — that mirror is what [currentThreadCollector] reads for the non-suspend call-site
 * bridge (OkHttp/Room opening a child span off the coroutine's own frame).
 */
class SpanCollector :
    ThreadContextElement<SpanCollector?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<SpanCollector>

    private val entries = CopyOnWriteArrayList<Span>()

    /** A snapshot of every span recorded so far, safe to iterate while [add] runs concurrently. */
    val spans: List<Span> get() = entries.toList()

    internal fun add(span: Span) {
        entries.add(span)
    }

    override fun updateThreadContext(context: CoroutineContext): SpanCollector? {
        val previous = currentCollectorThreadLocal.get()
        currentCollectorThreadLocal.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: SpanCollector?) {
        currentCollectorThreadLocal.set(oldState)
    }
}
