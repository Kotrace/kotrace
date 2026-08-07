package dev.luxgroup.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val currentSpanThreadLocal = ThreadLocal<Span?>()

/**
 * The span active on *this thread*, or null. Maintained by [SpanContext] as a [ThreadContextElement],
 * so **non-suspend** code running on a coroutine's thread — e.g. a synchronous factory or callback
 * invoked mid-flow, which has no `coroutineContext` reference — can still read the active span. This is
 * the thread-local bridge.
 */
fun currentSpan(): Span? = currentSpanThreadLocal.get()

/**
 * The current [Span], carried ambiently in the `CoroutineContext` so a child [trace] finds its parent
 * without a parameter — context propagation, hand-rolled on `CoroutineContext`.
 *
 * As a [ThreadContextElement] it also mirrors the span into a ThreadLocal on every resume and restores
 * the previous value on the way out — that mirror is what makes [currentSpan] resolve for the
 * non-suspend call-site bridge.
 */
class SpanContext(val span: Span) :
    ThreadContextElement<Span?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<SpanContext>

    override fun updateThreadContext(context: CoroutineContext): Span? {
        val previous = currentSpanThreadLocal.get()
        currentSpanThreadLocal.set(span)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Span?) {
        currentSpanThreadLocal.set(oldState)
    }
}
