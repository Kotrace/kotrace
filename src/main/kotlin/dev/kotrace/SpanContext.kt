package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val currentSpanThreadLocal = ThreadLocal<Span?>()

/** The [Span] in the current coroutine, or null. Non-suspend code reads [currentThreadSpan] instead. */
suspend fun currentSpan(): Span? = currentCoroutineContext()[SpanContext]?.span

/**
 * The [Span] mirrored onto this thread, or null: for **non-suspend** code running on a coroutine's thread (a
 * synchronous factory or callback). A suspend caller should prefer [currentSpan].
 */
fun currentThreadSpan(): Span? = currentSpanThreadLocal.get()

/**
 * Carries the current [Span] in the `CoroutineContext`, so a child [span] finds its parent. As a
 * [ThreadContextElement] it mirrors the span onto the thread for [currentThreadSpan].
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
