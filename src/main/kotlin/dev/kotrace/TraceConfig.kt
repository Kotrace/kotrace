package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val currentConfigThreadLocal = ThreadLocal<TraceConfig?>()

/**
 * The [TraceConfig] active in the current coroutine, or null — read straight from the `CoroutineContext`,
 * the source of truth. This is the getter a **suspend** caller uses; it is the config counterpart of
 * [currentSpan]. Non-suspend code off the coroutine frame (a synchronous factory or callback with no
 * `coroutineContext` handle) cannot call a suspend function, so it reads [currentThreadConfig] instead.
 */
suspend fun currentConfig(): TraceConfig? = currentCoroutineContext()[TraceConfig]

/**
 * The [TraceConfig] mirrored onto *this thread*, or null — the thread-local bridge for **non-suspend** code
 * running on a coroutine's thread (the OkHttp `Call.Factory`, a Room callback), which has no
 * `coroutineContext` reference. It reaches the live adapters for the trace it belongs
 * to. Maintained by [TraceConfig]'s [ThreadContextElement] mirror, same mechanism as [SpanContext]; the
 * config counterpart of [currentThreadSpan]. A genuine suspend caller should prefer [currentConfig], which
 * reads the context directly rather than the mirror.
 */
fun currentThreadConfig(): TraceConfig? = currentConfigThreadLocal.get()

/**
 * The immutable fan-out configuration carried ambiently in the `CoroutineContext`, seeded once at a
 * trace's root next to its [SpanCollector]. It holds the consumer's [adapters], so a log verb resolves
 * them without a mutable global — the var-free replacement for the old `KotraceLog` object.
 *
 * [liveAdapters] / [reportAdapters] are partitioned once here, so the per-event hot path checks a
 * precomputed list rather than filtering on every log call. There is no capture gate (ADR-002): the event
 * verbs store unconditionally and each adapter's [TracePolicy] filters once, at fan-out. A rejected event
 * never builds a [dev.kotrace.event.LogEvent]'s lazy message — the message resolves only when an adapter
 * accepts it (see [dev.kotrace.event.emit]). As a
 * [ThreadContextElement] it mirrors itself onto a ThreadLocal on every résumé and
 * restores the previous on the way out — that mirror is what [currentThreadConfig] reads for the non-suspend
 * call-site bridge, while a suspend caller reads this element directly via [currentConfig].
 */
class TraceConfig(
    val adapters: List<TraceAdapter>,
) : ThreadContextElement<TraceConfig?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<TraceConfig>

    val liveAdapters: List<LiveAdapter> = adapters.filterIsInstance<LiveAdapter>()
    val reportAdapters: List<ReportAdapter> = adapters.filterIsInstance<ReportAdapter>()

    override fun updateThreadContext(context: CoroutineContext): TraceConfig? {
        val previous = currentConfigThreadLocal.get()
        currentConfigThreadLocal.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: TraceConfig?) {
        currentConfigThreadLocal.set(oldState)
    }
}
