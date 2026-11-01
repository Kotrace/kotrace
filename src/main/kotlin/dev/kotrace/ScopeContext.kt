package dev.kotrace

import dev.kotrace.adapter.FaultPhase
import dev.kotrace.bridge.runIsolated
import dev.kotrace.internal.threadFlag
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Runs [block] under a **scope**: a correlation key above traces (`scope ⊇ trace ⊇ span`, ADR-010), such as a
 * session or journey id, which kotrace never interprets. Every record emitted inside carries [scopeId]: a span's
 * alongside its `trace_id`, a span-less emit's ([dev.kotrace.event.emitLog]) alone.
 *
 * A scope is not a span: it opens no tree, has no outcome, and reports nothing. It holds no configuration either, so
 * the adapters in force are unchanged. A nested scope replaces this one until it exits.
 *
 * Outside any scope, a record takes the process-wide fallback installed with
 * `Kotrace.install(fallbackScopeId = …)`, if any (ADR-038). This scope always wins over it, and once it exits the
 * fallback applies again.
 *
 * @sample dev.kotrace.samples.ScopeSamples.scopeUsage
 * @sample dev.kotrace.samples.ScopeSamples.fallbackScopeUsage
 */
suspend fun <T> withScope(scopeId: String, block: suspend () -> T): T =
    withContext(ScopeContext(scopeId)) { block() }

private val scopeMirror = ThreadMirror<String>()

/**
 * The [withScope] id in the current coroutine, or null outside any scope. Non-suspend code reads
 * [currentThreadScopeId] instead.
 *
 * Ambient only: it never returns the installed fallback (`Kotrace.install(fallbackScopeId = …)`, ADR-038), which a
 * record emitted here would carry when this is null.
 */
suspend fun currentScopeId(): String? = currentCoroutineContext()[ScopeContext]?.scopeId

/**
 * The [withScope] id mirrored onto this thread, or null: what a span-less emit ([dev.kotrace.event.emitLog])
 * correlates under (ADR-010). A suspend caller should prefer [currentScopeId].
 *
 * Ambient only: it never returns the installed fallback (`Kotrace.install(fallbackScopeId = …)`, ADR-038), which a
 * record emitted here would carry when this is null.
 */
fun currentThreadScopeId(): String? = scopeMirror.get()

/** Whether this thread is running the fallback scope provider, so a record it emits does not re-enter it. */
private val resolvingFallbackScope = threadFlag()

/**
 * The scope a record opened or emitted here carries (ADR-010/038): the [ambient] [withScope] id, else the installed
 * fallback's value, else null. Every site that stamps a scope resolves through this. The provider runs only when
 * [ambient] is null; a throw from it yields null and goes to the [dev.kotrace.adapter.FaultHook] as [phase],
 * `scope.fallback`, so the record is still delivered (ADR-034). A record the provider itself emits on this thread
 * takes no fallback rather than re-entering it (ADR-025).
 */
internal fun resolveScopeId(ambient: String?, phase: FaultPhase): String? {
    if (ambient != null) return ambient
    val fallback = Kotrace.fallbackScopeId() ?: return null
    if (resolvingFallbackScope.get()) return null
    resolvingFallbackScope.set(true)
    return try {
        runIsolated(phase, "scope.fallback", recover = { null }) { fallback() }
    } finally {
        resolvingFallbackScope.set(false)
    }
}

/**
 * Carries the [scope_id][dev.kotrace.event.TraceRecord.scopeId] set by [withScope] (ADR-010). A scope is a
 * correlation key above traces (`scope ⊇ trace ⊇ span`), not a span: it opens no tree and reports nothing. A nested
 * [withScope] replaces it until it exits. As a [ThreadContextElement] it mirrors itself for [currentThreadScopeId].
 */
class ScopeContext(val scopeId: String) :
    ThreadContextElement<String?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ScopeContext>

    override fun updateThreadContext(context: CoroutineContext): String? = scopeMirror.swap(scopeId)

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) = scopeMirror.restore(oldState)
}
