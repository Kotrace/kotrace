package dev.kotrace.bridge

import dev.kotrace.Kotrace
import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.TraceConfig
import dev.kotrace.currentThreadCollector
import dev.kotrace.currentThreadConfig
import dev.kotrace.currentThreadScopeId
import dev.kotrace.currentThreadSpan
import dev.kotrace.internal.liveFanOutRunning
import dev.kotrace.resolvedThreadConfig
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
 * - [scopeId] is the [withScope] id current at capture.
 *
 * How a bridge uses it:
 * - [parent], [collector] and [scopeId] go to [dev.kotrace.bridge.startSpan], which opens a span **per execution**
 *   under them on any thread.
 * - [withCapturedConfig] runs the bridge's emits against the flow's configuration, so they reach the same adapters.
 *
 * Immutable and thread-safe: capture once, use from any thread.
 */
@NonSuspendTracingBridge
class TraceHandoff private constructor(
    val parent: Span?,
    val collector: SpanCollector?,
    private val config: TraceConfig?,
    val scopeId: String?,
) {
    /** Runs [block] so every emit inside resolves the flow's configuration, as the flow's own code would. */
    fun <T> withCapturedConfig(block: () -> T): T = withMirroredConfig(config, block)

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
            return TraceHandoff(parent, collector, config, currentThreadScopeId())
        }
    }
}
