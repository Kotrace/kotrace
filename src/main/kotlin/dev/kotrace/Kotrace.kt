package dev.kotrace

import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The **process-wide fan-out config** (ADR-010): the one [TraceConfig] — the consumer's adapters and their
 * policies — that kotrace uses whenever no per-flow [TraceConfig] override is present in the
 * `CoroutineContext`. It is the standard setup, installed once at startup.
 *
 * Config (which sinks, which policy) is **static and process-wide**, so it belongs here, not threaded
 * through context; only per-flow state (the [SpanCollector], the current [Span], the [ScopeContext]) is
 * genuinely contextual and stays in the `CoroutineContext`. A caller that needs a *different* config for one
 * trace still overlays a [TraceConfig] on the context (`withContext(collector + TraceConfig(...))`) and that
 * **overrides** this global for that flow — the fan-out paths resolve `currentThreadConfig() ?:
 * Kotrace.defaultConfig()` (see [dev.kotrace.event.emit], [reportTrace]).
 *
 * The same config feeds every path — span and span-less, live and report, suspend and non-suspend — so they
 * differ only in the coroutine mechanism that locates the flow, never in *which* adapters see a record. The
 * one path the mechanism still bounds is **report**: it needs a [SpanCollector] to buffer the tree, which is
 * per-flow context, so a non-suspend flow with no collector is live-only by construction.
 *
 * **Install once, at startup, read-only after.** [install] publishes the config through an
 * [AtomicReference] (safe publication across threads); a second install is a hard error, never a silent
 * replace — fail-closed, so a stray re-install surfaces instead of quietly swapping a live sink. Installing
 * is optional: with nothing installed and no context override, every fan-out path is a safe no-op.
 *
 * **DI vs. global.** Under a DI graph (Hilt/Koin), prefer resolving the adapters *from* the graph and
 * calling [install] once at app start — the [install] provider overload lets you register before the graph
 * is ready and resolve lazily on first fan-out. A flow that genuinely needs different sinks overlays a
 * per-flow [TraceConfig] on its context (`withContext(collector + TraceConfig(...))`), which overrides this
 * global for that flow. The global exists for framework-owned, non-suspend entrypoints (app lifecycle, a
 * push callback) where no context can be threaded in.
 */
object Kotrace {

    /**
     * The one immutable installed holder (ADR-010/018): the lazily-resolved [TraceConfig] **and** the
     * process-wide [FailureClassifier], published together through a single [AtomicReference] so they can never
     * be observed torn and a failed second install mutates neither. The `config` provider resolves exactly
     * once, on the first [defaultConfig] read (safe publication); the `failureClassifier` is a plain reference
     * read directly (it does **not** force the config provider — ADR-018 keeps classification off the lazy path).
     */
    private class Installed(val config: Lazy<TraceConfig>, val failureClassifier: FailureClassifier?)

    private val installed = AtomicReference<Installed?>(null)

    /** Strict-uninstalled latch (ADR-011): when armed, a fan-out that resolves no config is a hard error. */
    private val strict = AtomicBoolean(false)

    /**
     * Publishes the process-wide fan-out [adapters], with an optional [faultHook] (ADR-014) and an optional
     * process-wide [failureClassifier] (ADR-018/020, returned-failure classification). Call once at startup.
     * Throws [IllegalStateException] on a second call — the holder is read-only after install. An empty list
     * installs a no-op config; a null [failureClassifier] (the default) leaves returned-value classification
     * off (today's behavior). Snapshots the list now, not on first fan-out, so a later mutation can't change
     * the config.
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
     * Publishes the process-wide fan-out config from a [provider] resolved **lazily**, exactly once, on the
     * first fan-out that reads it — so a consumer can register before its DI graph is ready and defer building
     * the adapters until first use — with an optional [faultHook] (ADR-014) and process-wide [failureClassifier]
     * (ADR-018/020). Same install-once contract: a second call throws. The [failureClassifier] is stored eagerly
     * alongside the lazy config in the one [Installed] holder.
     */
    fun install(
        faultHook: AdapterFaultHook? = null,
        failureClassifier: FailureClassifier? = null,
        provider: () -> List<TraceAdapter>,
    ) {
        val config = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val adapters = try {
                provider()
            } catch (t: Throwable) {
                // A throwing provider must not turn the traced operation into a failure — and Kotlin `lazy`
                // does not memoize exceptions, so an uncaught throw would re-run on every fan-out. Contain it
                // as a resolved **no-op** config (empty adapters): telemetry that failed to wire simply
                // reaches nowhere, never crashing the process it observes (ADR-011). A JVM-fatal error still
                // propagates. This is a consumer setup fault, not an adapter fault, so it is not routed to
                // [faultHook] (ADR-014 keeps config resolution out of the adapter-fault channel).
                if (t.isFatalFault()) throw t
                emptyList()
            }
            TraceConfig(adapters.toList(), faultHook)
        }
        if (!installed.compareAndSet(null, Installed(config, failureClassifier))) {
            error("Kotrace config already installed; it is install-once (ADR-010)")
        }
    }

    /** The installed process-wide [TraceConfig], or null if none — the fallback when no context override exists. */
    internal fun defaultConfig(): TraceConfig? = installed.get()?.config?.value

    /**
     * The installed process-wide [FailureClassifier] (ADR-018/020), or null if none. Read directly — it does
     * **not** force the lazy config provider — so resolving a span's classifier on the normal-return path never
     * triggers adapter construction or the strict-uninstalled latch. Null ⇒ no returned-value classification.
     */
    internal fun failureClassifier(): FailureClassifier? = installed.get()?.failureClassifier

    /**
     * Arms the **strict-uninstalled** check (ADR-011): from now on, a fan-out that resolves to no config — no
     * per-flow [TraceConfig] override *and* nothing installed via [install] — is a hard [IllegalStateException]
     * ([resolvedThreadConfig]) instead of ADR-010's silent no-op. It turns two invisible wiring bugs loud —
     * `install` never called, or an emit that ran before `install` — at the first emit that hits them.
     *
     * **Arm before `install`, and off by default.** It is a separate call, not a parameter of [install]: to
     * catch an emit *before* install (and the case where install never runs at all), the latch must already
     * be set when that emit resolves. Call it once, at the earliest startup point, and only in a debug/dev
     * build — release leaves it off so the ADR-010 no-op stands and no monitoring call can crash production.
     * Idempotent (unlike [install]): re-arming is a no-op, never an error.
     *
     * **Deliberately disabling telemetry stays valid** — express it as `install(emptyList())` (a non-null,
     * empty config → no throw), not as never calling [install] (which now reads as "forgot to wire").
     */
    fun strictWhenUninstalled() {
        strict.set(true)
    }

    /** Whether the strict-uninstalled latch is armed (ADR-011) — read by [resolvedThreadConfig] on a null resolution. */
    internal fun isStrictWhenUninstalled(): Boolean = strict.get()

    /** Test-only: clears the install-once latch and the strict latch so a suite can start each case fresh. */
    @TestOnly
    fun resetForTest() {
        installed.set(null)
        strict.set(false)
    }
}
