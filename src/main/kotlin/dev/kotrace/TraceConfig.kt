package dev.kotrace

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val currentConfigThreadLocal = ThreadLocal<TraceConfig?>()

/**
 * The per-flow [TraceConfig] in the current coroutine, or null. Non-suspend code reads [currentThreadConfig]
 * instead.
 */
suspend fun currentConfig(): TraceConfig? = currentCoroutineContext()[TraceConfig]

/**
 * The per-flow [TraceConfig] mirrored onto this thread, or null: for **non-suspend** code running on a
 * coroutine's thread (the OkHttp `Call.Factory`, a Room callback). A suspend caller should prefer
 * [currentConfig].
 */
fun currentThreadConfig(): TraceConfig? = currentConfigThreadLocal.get()

/**
 * Runs [block] with [config] mirrored onto this thread, then restores the previous value: the mirror for a thread
 * no coroutine resumed on ([TraceHandoff.withCapturedConfig]). A null [config] means "no override". When the
 * thread already mirrors [config] (no override on either side, or the flow's own thread), it touches nothing.
 */
internal fun <T> withMirroredConfig(config: TraceConfig?, block: () -> T): T {
    val previous = currentConfigThreadLocal.get()
    if (previous === config) return block()
    currentConfigThreadLocal.set(config)
    try {
        return block()
    } finally {
        currentConfigThreadLocal.set(previous)
    }
}

/**
 * The config every fan-out uses: the per-flow override, else the process-wide [Kotrace] config (ADR-010). Null
 * when neither exists, which throws instead once [Kotrace.strictWhenUninstalled] is armed (ADR-011).
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
 * An immutable fan-out configuration: the consumer's [adapters]. It is either the process-wide default
 * ([Kotrace.install]) or a **per-flow override** in the `CoroutineContext`, which wins for its flow (ADR-010).
 * Overlay one only when a flow needs different sinks.
 *
 * Every event is stored; each adapter's [TracePolicy] filters it at fan-out (ADR-002), and a
 * [dev.kotrace.event.LogEvent]'s lazy message is built only once an adapter accepts it. As a
 * [ThreadContextElement] it mirrors itself onto the thread for [currentThreadConfig].
 */
class TraceConfig(
    val adapters: List<TraceAdapter>,
    /** Told of each fault contained during fan-out (ADR-014); with none, faults are swallowed silently. */
    val faultHook: AdapterFaultHook? = null,
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
