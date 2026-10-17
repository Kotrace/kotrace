package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val currentCollectorThreadLocal = ThreadLocal<SpanCollector?>()

/**
 * The [SpanCollector] in the current coroutine, or null. Non-suspend code reads [currentThreadCollector] instead.
 */
suspend fun currentCollector(): SpanCollector? = currentCoroutineContext()[SpanCollector]

/**
 * The [SpanCollector] mirrored onto this thread, or null: for **non-suspend** code running on a coroutine's thread
 * (the OkHttp `Call.Factory`). A suspend caller should prefer [currentCollector].
 */
fun currentThreadCollector(): SpanCollector? = currentCollectorThreadLocal.get()

/**
 * Holds every [Span] of a trace until it is reported, so the whole tree can be walked. Seeded at the root; each
 * span registers itself as it opens, concurrently for parallel children. As a [ThreadContextElement] it mirrors
 * itself onto the thread for [currentThreadCollector].
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
