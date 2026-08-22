package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val currentSpanThreadLocal = ThreadLocal<Span?>()

/**
 * The [Span] active in the current coroutine, or null — read straight from the [SpanContext] carried in
 * the `CoroutineContext`, the source of truth. This is the getter a **suspend** caller uses. Non-suspend
 * code off the coroutine frame (a synchronous factory or callback with no `coroutineContext` handle)
 * cannot call a suspend function, so it reads [currentThreadSpan] instead.
 */
suspend fun currentSpan(): Span? = currentCoroutineContext()[SpanContext]?.span

/**
 * The [Span] mirrored onto *this thread*, or null — the thread-local bridge for **non-suspend** code
 * running on a coroutine's thread (a synchronous factory or callback invoked mid-flow, which has no
 * `coroutineContext` reference). Maintained by [SpanContext] as a [ThreadContextElement]; a genuine
 * suspend caller should prefer [currentSpan], which reads the context directly rather than the mirror.
 */
fun currentThreadSpan(): Span? = currentSpanThreadLocal.get()

/**
 * The current [Span], carried ambiently in the `CoroutineContext` so a child [span] finds its parent
 * without a parameter — context propagation, hand-rolled on `CoroutineContext`.
 *
 * As a [ThreadContextElement] it also mirrors the span into a ThreadLocal on every resume and restores
 * the previous value on the way out — that mirror is what makes [currentThreadSpan] resolve for the
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
