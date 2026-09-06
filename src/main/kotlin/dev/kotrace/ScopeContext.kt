package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val currentScopeThreadLocal = ThreadLocal<String?>()

/**
 * The ambient [withScope] id ([scope_id][dev.kotrace.event.TraceRecord.scopeId]) in the current coroutine,
 * or null outside any scope — read straight from the [ScopeContext] carried in the `CoroutineContext`, the
 * source of truth. This is the getter a **suspend** caller uses; the scope counterpart of [currentSpan].
 * Non-suspend code off the coroutine frame reads the [currentThreadScopeId] mirror instead.
 */
suspend fun currentScopeId(): String? = currentCoroutineContext()[ScopeContext]?.scopeId

/**
 * The ambient [withScope] id mirrored onto *this thread*, or null outside any scope — the non-suspend
 * bridge a span-less emit ([dev.kotrace.event.emitLog]/[dev.kotrace.event.emitNamed]/[dev.kotrace.event.emitException])
 * reads to pick up its correlation umbrella (ADR-010). The scope counterpart of [currentThreadSpan];
 * maintained by [ScopeContext]'s [ThreadContextElement] mirror. A suspend caller should prefer
 * [currentScopeId], which reads the context directly rather than the mirror.
 */
fun currentThreadScopeId(): String? = currentScopeThreadLocal.get()

/**
 * Carries the ambient [scope_id][dev.kotrace.event.TraceRecord.scopeId] in the `CoroutineContext`, seeded
 * by [withScope]. `scope ⊇ trace ⊇ span` (ADR-010): a scope is a **live-only correlation umbrella**, not a
 * span — it opens no reportable tree and has no bounded lifetime, so it holds a plain [scopeId] string, not
 * a [Span]/[SpanCollector].
 *
 * As a [ThreadContextElement] it mirrors [scopeId] onto a ThreadLocal on every resume and restores the
 * previous on the way out — that mirror is what [currentThreadScopeId] reads for a non-suspend span-less emit.
 * One active scope per context: a nested [withScope] overlays a new element, so the innermost wins and the
 * outer is restored on exit (no scope stack — ADR-010).
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
