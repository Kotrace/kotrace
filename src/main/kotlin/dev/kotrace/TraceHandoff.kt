package dev.kotrace

/**
 * The trace context of a coroutine's thread (the current span, [SpanCollector], per-flow [TraceConfig] and scope
 * id), captured so a **non-suspend bridge** can act on that flow later from another thread, where the thread
 * mirrors read null (ADR-023). OkHttp is the case: `newCall` runs on the coroutine's thread, the interceptor on
 * OkHttp's.
 *
 * - [parent], [collector] and [scopeId] go to [dev.kotrace.startSpan], which opens a span **per execution** under
 *   them on any thread.
 * - [withCapturedConfig] runs the bridge's emits against the flow's config, so they reach the same adapters.
 *
 * Immutable and thread-safe: capture once, use from any thread.
 */
@NonSuspendTracingBridge
class TraceHandoff private constructor(
    /** The span current at capture, the parent of spans opened from here; null roots a new trace. */
    val parent: Span?,
    /** The collector current at capture, so spans opened from here reach the flow's report. */
    val collector: SpanCollector?,
    private val config: TraceConfig?,
    /** The [withScope] id current at capture. */
    val scopeId: String?,
) {
    /** Runs [block] so every emit inside resolves the flow's config, as the flow's own code would. */
    fun <T> withCapturedConfig(block: () -> T): T = withMirroredConfig(config, block)

    companion object {
        /**
         * Captures this thread's trace context, or `null` when nothing would consume a span (no current span, no
         * collector, no live adapter), so a bridge can pass the call through untouched.
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
            if (parent == null && collector == null && (config ?: Kotrace.defaultConfig())?.liveAdapters.isNullOrEmpty()) {
                return null
            }
            return TraceHandoff(parent, collector, config, currentThreadScopeId())
        }
    }
}
