package dev.kotrace

/**
 * The calling thread's trace context — the ambient span (future parent), the [SpanCollector], the per-flow
 * [TraceConfig] override and the scope id — captured on a coroutine's thread so a **non-suspend bridge** can act
 * on that flow later, from a thread where the `ThreadContextElement` mirrors are dead (ADR-023).
 *
 * The OkHttp integration is the case this exists for: `Call.Factory.newCall` runs on the coroutine's thread and
 * captures a handoff; the interceptor runs on OkHttp's dispatcher thread, where [currentThreadSpan],
 * [currentThreadCollector] and [currentThreadConfig] all read null. Carrying only the parent [Span] across that
 * boundary (the pre-ADR-023 shape) lost the rest: emits there fanned to the process-wide config instead of the
 * flow's override, and a strict-uninstalled check could fire on a flow that did have a config.
 *
 * - [startSpan] opens a span **per execution** under the captured parent, registered into the captured collector
 *   — so a retried or cloned call gets its own span, and a call never executed opens none.
 * - [withCapturedConfig] runs the bridge's emits (`log`, [end], …) against the captured override, so they reach the
 *   same adapters the flow's suspend code does.
 *
 * Immutable and thread-safe; capture once, use from any thread. Opt-in ([NonSuspendTracingBridge]) like every
 * non-suspend bridge.
 */
@NonSuspendTracingBridge
class TraceHandoff private constructor(
    private val parent: Span?,
    private val collector: SpanCollector?,
    private val config: TraceConfig?,
    private val scopeId: String?,
) {
    /**
     * Opens a span as a child of the captured parent — or a **root** (fresh `traceId`) when none was active —
     * registered into the captured collector (none ⇒ not collected, live-only). The caller must close it with
     * [end]. Unlike [dev.kotrace.startSpan] it reads no thread mirror, so it is correct on any thread.
     */
    fun startSpan(name: String, attributes: Map<String, String> = emptyMap(), links: List<TraceLink> = emptyList()): Span {
        val opened = createSpan(parent, name, attributes, links, scopeId)
        collector?.add(opened)
        return opened
    }

    /**
     * Runs [block] with the captured per-flow [TraceConfig] override mirrored onto this thread, restoring the
     * previous mirror after. Every emit inside resolves the flow's config — the override when the flow had one,
     * else the process-wide [Kotrace] config, the same resolution the flow's suspend code gets.
     */
    fun <T> withCapturedConfig(block: () -> T): T = withThreadConfig(config, block)

    companion object {
        /**
         * Captures this thread's mirrored trace context, or returns `null` when nothing would consume a span —
         * no active span, no collector, and no live adapter in the config a fan-out here would use (the per-flow
         * override, else the process-wide one). A `null` lets a bridge pass the call through untouched.
         *
         * Call it on the coroutine's thread (inline in non-suspend code the coroutine calls), where the mirrors
         * are live. Resolving the process-wide config may run a lazy `Kotrace.install { … }` provider.
         */
        @NonSuspendTracingBridge
        fun captureOrNull(): TraceHandoff? {
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
