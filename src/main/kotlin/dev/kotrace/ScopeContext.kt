package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val currentScopeThreadLocal = ThreadLocal<String?>()

/**
 * The [withScope] id in the current coroutine, or null outside any scope. Non-suspend code reads
 * [currentThreadScopeId] instead.
 */
suspend fun currentScopeId(): String? = currentCoroutineContext()[ScopeContext]?.scopeId

/**
 * The [withScope] id mirrored onto this thread, or null: what a span-less emit ([dev.kotrace.event.emitLog])
 * correlates under (ADR-010). A suspend caller should prefer [currentScopeId].
 */
fun currentThreadScopeId(): String? = currentScopeThreadLocal.get()

/**
 * Carries the [scope_id][dev.kotrace.event.TraceRecord.scopeId] set by [withScope] (ADR-010). A scope is a
 * correlation key above traces (`scope ⊇ trace ⊇ span`), not a span: it opens no tree and reports nothing. A nested
 * [withScope] replaces it until it exits. As a [ThreadContextElement] it mirrors itself for [currentThreadScopeId].
 */
class ScopeContext(val scopeId: String) :
    ThreadContextElement<String?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ScopeContext>

    override fun updateThreadContext(context: CoroutineContext): String? {
        val previous = currentScopeThreadLocal.get()
        currentScopeThreadLocal.set(scopeId)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        currentScopeThreadLocal.set(oldState)
    }
}
