package dev.kotrace

import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The **process-wide fan-out config** (ADR-010): the [TraceConfig] kotrace uses whenever the `CoroutineContext`
 * holds no per-flow override. Only per-flow state (the [SpanCollector], the current [Span], the [ScopeContext])
 * lives in the context; which sinks and policies apply is static, so it is installed here once, at startup. A flow
 * that needs different sinks overlays its own [TraceConfig] (`withContext(collector + TraceConfig(...))`).
 *
 * The same config feeds every path: span and span-less, live and report, suspend and non-suspend. Only the report
 * needs a per-flow [SpanCollector], so a non-suspend flow with no collector is live-only.
 *
 * **Install once, read-only after.** A second [install] is an error, never a silent replace. Installing is
 * optional: with nothing installed and no override, every fan-out is a no-op. Under a DI graph, the [install]
 * provider overload registers early and resolves the adapters on first fan-out.
 */
object Kotrace {

    /**
     * The config and the [FailureClassifier], published together so they are never seen torn (ADR-010/018). The
     * config resolves once, on the first [defaultConfig] read; the classifier is read directly, without forcing it.
     */
    private class Installed(val config: Lazy<TraceConfig>, val failureClassifier: FailureClassifier?)

    private val installed = AtomicReference<Installed?>(null)

    /** Strict-uninstalled latch (ADR-011): when armed, a fan-out that resolves no config is a hard error. */
    private val strict = AtomicBoolean(false)

    /**
     * Whether this thread is running the [install] provider (ADR-025). `lazy`'s monitor is reentrant, so without it
     * an emit from inside the provider re-ran it, recursing into a [StackOverflowError].
     */
    private val resolvingProvider = ThreadLocal.withInitial { false }

    /** What a fan-out sees while its own thread resolves the provider: installed, but reaching no adapter. */
    private val resolvingConfig = TraceConfig(emptyList())

    /**
     * Installs the process-wide [adapters], with an optional [faultHook] (ADR-014) and [failureClassifier]
     * (ADR-018/020). Call once at startup; a second call throws [IllegalStateException]. An empty list installs a
     * no-op config. The list is copied now.
     */
    fun install(
        adapters: List<TraceAdapter>,
        faultHook: AdapterFaultHook? = null,
        failureClassifier: FailureClassifier? = null,
    ) {
        val snapshot = adapters.toList()
        install(faultHook, failureClassifier) { snapshot }
    }

    /**
     * Installs the process-wide config from a [provider] resolved **lazily**, once, on the first fan-out, so a
     * consumer can register before its DI graph is ready. Same install-once contract.
     *
     * An emit made on the provider's thread while it runs reaches no adapter, rather than re-entering it (ADR-025).
     * Another thread emitting meanwhile waits, so the provider must not block on a thread that emits.
     */
    fun install(
        faultHook: AdapterFaultHook? = null,
        failureClassifier: FailureClassifier? = null,
        provider: () -> List<TraceAdapter>,
    ) {
        val config = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            resolvingProvider.set(true)
            val adapters = try {
                provider()
            } catch (t: Throwable) {
                // A throwing provider resolves to a no-op config (ADR-011): `lazy` does not memoize a throw, so it
                // would otherwise re-run on every fan-out. A setup fault, not an adapter fault: no faultHook.
                if (t.isJvmFatal()) throw t
                emptyList()
            } finally {
                resolvingProvider.set(false)
            }
            TraceConfig(adapters.toList(), faultHook)
        }
        if (!installed.compareAndSet(null, Installed(config, failureClassifier))) {
            error("Kotrace config already installed; it is install-once (ADR-010)")
        }
    }

    /** The installed config, or null if none; empty while this thread runs the provider (ADR-025). */
    internal fun defaultConfig(): TraceConfig? {
        val config = installed.get()?.config ?: return null
        if (resolvingProvider.get()) return resolvingConfig
        return config.value
    }

    /**
     * The installed [FailureClassifier], or null if none. Read without forcing the config provider, so classifying
     * a return never builds adapters or trips the strict latch (ADR-018).
     */
    internal fun failureClassifier(): FailureClassifier? = installed.get()?.failureClassifier

    /**
     * Arms the **strict-uninstalled** check (ADR-011): from now on, a fan-out that resolves no config (no per-flow
     * override, nothing installed) throws [IllegalStateException] instead of doing nothing. It makes a missing or
     * late [install] loud at the first emit.
     *
     * Call it before [install], at the earliest startup point, and only in a debug build, so no monitoring call
     * can crash production. Idempotent. To disable telemetry on purpose, `install(emptyList())`.
     */
    fun strictWhenUninstalled() {
        strict.set(true)
    }

    internal fun isStrictWhenUninstalled(): Boolean = strict.get()

    /** Clears the install-once and strict latches so each test starts fresh ([KotraceTestApi]). */
    @TestOnly
    @KotraceTestApi
    fun resetForTest() {
        installed.set(null)
        strict.set(false)
    }
}
