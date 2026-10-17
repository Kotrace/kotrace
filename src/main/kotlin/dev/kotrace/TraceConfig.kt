package dev.kotrace

import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TraceAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.bridge.TraceHandoff
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
 * The configuration every fan-out uses: the per-flow override, else the process-wide [Kotrace] configuration (ADR-010).
 * Null when neither exists, which throws instead once [Kotrace.strictWhenNotInstalled] is armed (ADR-011).
 */
internal fun resolvedThreadConfig(): TraceConfig? =
    (currentThreadConfig() ?: Kotrace.defaultConfig())
        ?: if (Kotrace.isStrictWhenNotInstalled()) {
            throw StrictNotInstalledException(
                "kotrace: an emit resolved no fan-out configuration while strict-not-installed is armed (ADR-011). " +
                    "Call Kotrace.install(...) once at startup before the first emit, or install(emptyList()) " +
                    "to disable telemetry deliberately.",
            )
        } else {
            null
        }

/**
 * The strict-not-installed failure (ADR-011): the one fault of kotrace's own that is meant to reach the traced code,
 * so a debug build sees a missing install. Its own type lets [span] tell it apart from any other fault, which it
 * contains (ADR-034). An [IllegalStateException], as before, so a caller catching that still catches it.
 */
internal class StrictNotInstalledException(message: String) : IllegalStateException(message)

/**
 * The [TraceConfig.maxEventsPerSpan] a span opening now takes: the per-flow override's, else the installed one's,
 * else [Span.MAX_EVENTS]. Unlike [resolvedThreadConfig] it never forces the [Kotrace.install] provider and never
 * trips the strict latch: opening a span is not a fan-out.
 */
internal fun resolvedMaxEventsPerSpan(): Int =
    currentThreadConfig()?.maxEventsPerSpan ?: Kotrace.maxEventsPerSpan() ?: Span.MAX_EVENTS

/**
 * An immutable fan-out configuration: the consumer's [adapters]. It is either the process-wide default
 * ([Kotrace.install]) or a **per-flow override** in the `CoroutineContext`, which wins for its flow (ADR-010).
 * Overlay one only when a flow needs different sinks.
 *
 * [maxEventsPerSpan] bounds each span's timeline, not the trace's (ADR-029/033): a trace of ten spans can hold ten
 * times as many events. A span reads it once, when it opens, so a configuration overlaid later never changes the
 * cap of a span already open. It must be at least 1. Raise it with care: every append copies the span's timeline,
 * so a span that fills a larger cap costs quadratically more.
 *
 * Every event is stored; each adapter's [TracePolicy] filters it at fan-out (ADR-002), and a
 * [dev.kotrace.event.LogEvent]'s lazy message is built only once an adapter accepts it. As a
 * [ThreadContextElement] it mirrors itself onto the thread for [currentThreadConfig].
 */
class TraceConfig(
    val adapters: List<TraceAdapter>,
    val maxEventsPerSpan: Int = Span.MAX_EVENTS,
) : ThreadContextElement<TraceConfig?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<TraceConfig>

    init {
        require(maxEventsPerSpan >= 1) { "maxEventsPerSpan must be at least 1, was $maxEventsPerSpan" }
    }

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
