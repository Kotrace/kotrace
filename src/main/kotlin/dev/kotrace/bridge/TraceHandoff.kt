package dev.kotrace.bridge

import dev.kotrace.Kotrace
import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.TraceConfig
import dev.kotrace.collectorMirror
import dev.kotrace.configMirror
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.currentThreadCollector
import dev.kotrace.currentThreadConfig
import dev.kotrace.currentThreadScopeId
import dev.kotrace.currentThreadSpan
import dev.kotrace.internal.liveFanOutRunning
import dev.kotrace.resolveScopeId
import dev.kotrace.resolvedThreadConfig
import dev.kotrace.scopeMirror
import dev.kotrace.spanMirror
import dev.kotrace.withMirroredConfig
import dev.kotrace.withScope

/**
 * The trace context of a coroutine's thread (the current span, [SpanCollector], per-flow [TraceConfig] and scope
 * id), captured so a **non-suspend bridge** can act on that flow later from another thread, where the thread
 * mirrors read null (ADR-023). OkHttp is the case: `newCall` runs on the coroutine's thread, the interceptor on
 * OkHttp's.
 *
 * What it captures:
 * - [parent] is the span current at capture, the parent of spans opened from here; null roots a new trace.
 * - [collector] is the collector current at capture, so spans opened from here reach the flow's report.
 * - [scopeId] is the scope resolved at capture: the [withScope] id current then, else the installed fallback's
 *   (ADR-038), else null.
 *
 * How a bridge uses it:
 * - [parent], [collector] and [scopeId] go to [dev.kotrace.bridge.startSpan], which opens a span **per execution**
 *   under them on any thread.
 * - [withCapturedConfig] runs the bridge's emits against the flow's configuration, so they reach the same adapters.
 * - [withCapturedContext] runs code that reads the thread mirrors itself, such as a library callback calling
 *   [currentThreadSpan], as if on the flow's own thread: the span, collector, configuration and ambient scope id are
 *   all mirrored.
 *
 * Immutable and thread-safe: capture once, use from any thread.
 */
@NonSuspendTracingBridge
class TraceHandoff private constructor(
    val parent: Span?,
    val collector: SpanCollector?,
    private val config: TraceConfig?,
    val scopeId: String?,
    private val ambientScopeId: String?,
) {
    /** Runs [block] so every emit inside resolves the flow's configuration, as the flow's own code would. */
    fun <T> withCapturedConfig(block: () -> T): T = withMirroredConfig(config, block)

    /**
     * Runs [block] with the whole captured context mirrored onto this thread, then restores what the thread held:
     * inside, [currentThreadSpan] is [parent] and the other thread reads return what they returned at capture. The
     * scope mirrored is the ambient [withScope] id, not [scopeId], so the installed fallback still applies only
     * where no scope is ambient. For code a bridge does not write, which reads the mirrors itself.
     */
    fun <T> withCapturedContext(block: () -> T): T =
        spanMirror.mirroring(parent) {
            collectorMirror.mirroring(collector) {
                configMirror.mirroring(config) { scopeMirror.mirroring(ambientScopeId, block) }
            }
        }

    companion object {
        /**
         * Captures this thread's trace context, or `null` when nothing would consume a span (no current span, no
         * collector, no live adapter), so a bridge can pass the call through untouched. With no span and no collector,
         * and nothing installed, it throws once [Kotrace.strictWhenNotInstalled] is armed, as an emit would (ADR-035):
         * a flow made only of bridged calls still surfaces a missing install.
         *
         * Also `null` **inside a live fan-out on this thread** (ADR-025): a sink uploading through a traced client
         * would otherwise feed its own calls' spans back into itself, looping when uploads fail.
         *
         * Call it on the coroutine's thread, where the mirrors are live. It may run a lazy `Kotrace.install` provider.
         */
        @NonSuspendTracingBridge
        fun captureOrNull(): TraceHandoff? {
            if (liveFanOutRunning.get()) return null
            val parent = currentThreadSpan()
            val collector = currentThreadCollector()
            val config = currentThreadConfig()
            if (parent == null && collector == null && resolvedThreadConfig()?.liveAdapters.isNullOrEmpty()) {
                return null
            }
            val ambientScopeId = currentThreadScopeId()
            return TraceHandoff(
                parent,
                collector,
                config,
                resolveScopeId(ambientScopeId, FaultPhase.SPAN_OPEN),
                ambientScopeId,
            )
        }
    }
}
