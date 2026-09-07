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
 * The [TraceConfig] a fan-out path uses: the per-flow context override ([currentThreadConfig]) if one is
 * present, else the process-wide [Kotrace] config (ADR-010). This is what makes every path — span and
 * span-less, live and report, suspend and non-suspend — fan to the same adapters, differing only in the
 * coroutine mechanism that locates the flow. Null when neither is installed — a safe no-op, unless the
 * strict-uninstalled latch ([Kotrace.strictWhenUninstalled]) is armed, in which case that null resolution is
 * a hard error instead (ADR-011): the emit reached kotrace before any config was installed.
 */
internal fun resolvedThreadConfig(): TraceConfig? =
    (currentThreadConfig() ?: Kotrace.defaultConfig())
        ?: if (Kotrace.isStrictWhenUninstalled()) {
            error(
                "kotrace: an emit resolved no fan-out config while strict-uninstalled is armed (ADR-011). " +
                    "Call Kotrace.install(...) once at startup before the first emit, or install(emptyList()) " +
                    "to disable telemetry deliberately.",
            )
        } else {
            null
        }

/**
 * An immutable fan-out configuration — the consumer's [adapters]. It has two homes (ADR-010): the
 * process-wide default installed once via [Kotrace.install], and — optionally — a **per-flow override**
 * carried ambiently in the `CoroutineContext`, seeded at a trace's root next to its [SpanCollector]. Every
 * fan-out site resolves [resolvedThreadConfig] = `currentThreadConfig() ?: Kotrace.defaultConfig()`, so a
 * context override wins for its flow and the global covers everything else (span-less emits, non-suspend
 * roots, untraced flows). Overlay one only when a flow needs different sinks than the process default.
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
