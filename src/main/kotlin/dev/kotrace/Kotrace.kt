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

    /** Memoizing holder: the provider resolves exactly once, on the first [defaultConfig] read (safe publication). */
    private val installed = AtomicReference<Lazy<TraceConfig>?>(null)

    /** Strict-uninstalled latch (ADR-011): when armed, a fan-out that resolves no config is a hard error. */
    private val strict = AtomicBoolean(false)

    /**
     * Publishes the process-wide fan-out [adapters] (live and report). Call once at startup. Throws
     * [IllegalStateException] on a second call — the config is read-only after install. An empty list
     * installs a no-op config (fan-out reaches nowhere).
     */
    fun install(adapters: List<TraceAdapter>) = install { adapters }

    /**
     * Publishes the process-wide fan-out config from a [provider] resolved **lazily**, exactly once, on the
     * first fan-out that reads it — so a consumer can register the config before its DI graph is ready and
     * defer building the adapters until first use. Same install-once contract as the list overload: a second
     * call throws.
     */
    fun install(provider: () -> List<TraceAdapter>) {
        val holder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TraceConfig(provider().toList()) }
        if (!installed.compareAndSet(null, holder)) {
            error("Kotrace config already installed; it is install-once (ADR-010)")
        }
    }

    /** The installed process-wide [TraceConfig], or null if none — the fallback when no context override exists. */
    internal fun defaultConfig(): TraceConfig? = installed.get()?.value

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
