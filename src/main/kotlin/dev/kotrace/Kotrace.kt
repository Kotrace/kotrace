package dev.kotrace

import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.TraceAdapter
import dev.kotrace.bridge.runIsolated
import dev.kotrace.internal.threadFlag
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.annotations.TestOnly

/**
 * The **process-wide fan-out configuration** (ADR-010): the [TraceConfig] kotrace uses whenever the `CoroutineContext`
 * holds no per-flow override. Only per-flow state (the [SpanCollector], the current [Span], the [ScopeContext])
 * lives in the context; which sinks and policies apply is static, so it is installed here once, at startup. A flow
 * that needs different sinks overlays its own [TraceConfig] (`withContext(collector + TraceConfig(...))`).
 *
 * The same configuration feeds every path: span and span-less, live and report, suspend and non-suspend. Only the
 * report needs a per-flow [SpanCollector], so a non-suspend flow with no collector is live-only.
 *
 * **Install once, read-only after.** A second [install] is an error, never a silent replace. Installing is
 * optional: with nothing installed and no override, every fan-out is a no-op.
 *
 * **Prefer the adapter-list [install].** It needs the adapters built first, so a fault building them surfaces at
 * the call. Use the provider overload only when no adapter can be built before the first emit: they need objects
 * a dependency-injection graph creates later, building them emits through kotrace, or building them is too costly
 * for startup. A dependency-injection graph alone is no reason: when the adapters' dependencies are already
 * injected where [install] runs, the list overload fits. Either way, run [install] before the first emit; under a
 * dependency-injection singleton, resolve that singleton at startup.
 *
 * **The fault hook is separate** ([installFaultHook], ADR-037): one per process, installed once, before or after
 * [install]. Install it first, at the earliest startup point, so it also hears of a fault in the [install] provider
 * and of faults in a flow that has no configuration.
 */
object Kotrace {

    /**
     * The configuration, the [ReturnVerdictClassifier] and the span cap, published together so they are never seen
     * torn (ADR-010/018/033). The configuration resolves once, on the first [defaultConfig] read; the classifier
     * and the cap are read directly, without forcing it.
     */
    private class Installed(
        val config: Lazy<TraceConfig>,
        val returnVerdictClassifier: ReturnVerdictClassifier<Any?>?,
        val maxEventsPerSpan: Int,
    )

    private val installed = AtomicReference<Installed?>(null)

    /** Strict-not-installed latch (ADR-011): when armed, a fan-out that resolves no configuration is a hard error. */
    private val strict = AtomicBoolean(false)

    /**
     * Whether this thread is running the [install] provider (ADR-025). `lazy`'s monitor is reentrant, so without it
     * an emit from inside the provider re-ran it, recursing into a [StackOverflowError].
     */
    private val resolvingProvider = threadFlag()

    /** What a fan-out sees while its own thread resolves the provider: installed, but reaching no adapter. */
    private val resolvingConfig = TraceConfig(emptyList())

    /** The process-wide [FaultHook] (ADR-037), apart from the configuration so it can be installed first. */
    private val faultHook = AtomicReference<FaultHook?>(null)

    /**
     * Installs the process-wide [hook], told of each fault kotrace contains (ADR-014/037). Call it once, as early as
     * possible: before [install], it also hears of a fault in the [install] provider. A second call throws
     * [IllegalStateException], never a silent replace. Without a hook, a fault is still contained but nothing reports
     * it.
     */
    fun installFaultHook(hook: FaultHook) {
        if (!faultHook.compareAndSet(null, hook)) error("A FaultHook is already installed; it is install-once (ADR-037)")
    }

    /** The installed [FaultHook], or null if none. */
    internal fun faultHook(): FaultHook? = faultHook.get()

    /**
     * Installs the process-wide [adapters], with an optional [returnVerdictClassifier] (ADR-018/020) and
     * [maxEventsPerSpan] ([TraceConfig.maxEventsPerSpan], ADR-033). Call once at startup; a second call throws
     * [IllegalStateException]. An empty list installs a no-op configuration. The list is copied now. The fault hook is
     * installed apart, with [installFaultHook].
     *
     * The default choice: the adapters exist before this call, so a fault building them throws at the caller, and
     * the first emit has no adapter to build.
     */
    fun install(
        adapters: List<TraceAdapter>,
        returnVerdictClassifier: ReturnVerdictClassifier<Any?>? = null,
        maxEventsPerSpan: Int = Span.MAX_EVENTS,
    ) {
        val snapshot = adapters.toList()
        install(returnVerdictClassifier, maxEventsPerSpan) { snapshot }
    }

    /**
     * Installs the process-wide configuration from a [provider] resolved **lazily**, once, on the first fan-out, so a
     * consumer can register before its dependency-injection graph is ready. Same install-once contract.
     *
     * Use it only when the adapters cannot be built before the first emit; otherwise prefer the adapter-list
     * overload. Deferring costs:
     * - **A throwing [provider] installs a no-op configuration** (ADR-011): every fan-out reaches nothing and
     *   nothing throws, not even under [strictWhenNotInstalled], since a configuration is installed. A wiring fault
     *   the list overload would throw at startup turns into missing telemetry, seen only by a [FaultHook] installed
     *   before ([installFaultHook], [dev.kotrace.adapter.FaultPhase.INSTALL]).
     * - The first emit pays for building the adapters, on whatever thread it runs.
     * - An emit made on the provider's thread while it runs reaches no adapter, rather than re-entering it
     *   (ADR-025). Another thread emitting meanwhile waits, so the provider must not block on a thread that emits.
     */
    fun install(
        returnVerdictClassifier: ReturnVerdictClassifier<Any?>? = null,
        maxEventsPerSpan: Int = Span.MAX_EVENTS,
        provider: () -> List<TraceAdapter>,
    ) {
        // Checked now, not when the provider resolves: a bad cap is a startup error, not a first-emit one.
        require(maxEventsPerSpan >= 1) { "maxEventsPerSpan must be at least 1, was $maxEventsPerSpan" }
        val config = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            resolvingProvider.set(true)
            val adapters = try {
                // A throwing provider resolves to a no-op configuration (ADR-011): `lazy` does not memoize a throw, so
                // it would otherwise re-run on every fan-out. The hook hears of it, if installed already (ADR-037).
                runIsolated(FaultPhase.INSTALL, "install.provider", recover = { emptyList() }) { provider() }
            } finally {
                resolvingProvider.set(false)
            }
            TraceConfig(adapters.toList(), maxEventsPerSpan)
        }
        if (!installed.compareAndSet(null, Installed(config, returnVerdictClassifier, maxEventsPerSpan))) {
            error("Kotrace configuration already installed; it is install-once (ADR-010)")
        }
    }

    /** The installed configuration, or null if none; empty while this thread runs the provider (ADR-025). */
    internal fun defaultConfig(): TraceConfig? {
        val config = installed.get()?.config ?: return null
        if (resolvingProvider.get()) return resolvingConfig
        return config.value
    }

    /**
     * The installed [ReturnVerdictClassifier], or null if none. Read without forcing the configuration provider, so
     * classifying a return never builds adapters or trips the strict latch (ADR-018).
     */
    internal fun returnVerdictClassifier(): ReturnVerdictClassifier<Any?>? = installed.get()?.returnVerdictClassifier

    /** The installed [TraceConfig.maxEventsPerSpan], or null if none. Read without forcing the provider (ADR-033). */
    internal fun maxEventsPerSpan(): Int? = installed.get()?.maxEventsPerSpan

    /**
     * Arms the **strict-not-installed** check (ADR-011): from now on, a fan-out that resolves no configuration (no
     * per-flow override, nothing installed) throws [IllegalStateException] instead of doing nothing. It makes a missing
     * or late [install] loud at the first emit.
     *
     * Call it before [install], at the earliest startup point, and only in a debug build, so no monitoring call
     * can crash production. Idempotent. To disable telemetry on purpose, `install(emptyList())`.
     */
    fun strictWhenNotInstalled() {
        strict.set(true)
    }

    internal fun isStrictWhenNotInstalled(): Boolean = strict.get()

    /** Clears the install-once latches (configuration and fault hook) and the strict one, so each test starts fresh ([KotraceTestApi]). */
    @TestOnly
    @KotraceTestApi
    fun resetForTest() {
        installed.set(null)
        faultHook.set(null)
        strict.set(false)
    }
}
