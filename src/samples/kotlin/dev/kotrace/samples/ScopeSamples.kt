package dev.kotrace.samples

import dev.kotrace.Kotrace
import dev.kotrace.adapter.TraceAdapter
import dev.kotrace.currentSpan
import dev.kotrace.event.emitException
import dev.kotrace.event.emitNamed
import dev.kotrace.event.log
import dev.kotrace.span
import dev.kotrace.withScope

/**
 * Dokka `@sample` targets for the span-less emit verbs, `withScope` (ADR-010) and the fallback scope (ADR-038), compiled against the
 * real API by `check` (ADR-007), so the examples cannot drift. Not published: excluded from the jar.
 */
@Suppress("unused")
internal object ScopeSamples {

    /**
     * A span-less emit: an occurrence outside any span (here, a push-notification callback with no open
     * trace). It fans **live-only** through the resolved configuration (context override, else the process-wide
     * `Kotrace` configuration); `emitException(cause, attributes)` rides
     * record-level `spanAttributes` a consumer reads as an explicit-report marker.
     */
    fun spanlessEmit(cause: Throwable) {
        emitNamed("push_received", mapOf("channel" to "promo"))
        emitException(cause, mapOf("report" to "explicit"))
    }

    /**
     * A scope: a live-only correlation umbrella. Everything inside it, spans and orphan emits alike,
     * carries `scope_id = "session-42"`; the span additionally carries its own `trace_id`.
     */
    suspend fun scopeUsage() {
        withScope("session-42") {
            emitNamed("session_started")
            span("checkout") {
                currentSpan()?.log(mapOf("level" to "INFO")) { "checkout started" }
            }
        }
    }

    /**
     * A fallback scope: installed once, it scopes every record emitted outside any `withScope`, so a synchronous
     * emit from a click handler still correlates under the session. It is read at each record, so a rotated session
     * takes effect without re-installing. An ambient `withScope` wins over it.
     */
    suspend fun fallbackScopeUsage(adapters: List<TraceAdapter>, currentSessionId: () -> String?) {
        Kotrace.install(adapters, fallbackScopeId = currentSessionId)

        emitNamed("button_clicked")              // scope_id = the current session id
        span("sync") { /* … */ }                 // trace_id + the session's scope_id
        withScope("checkout-journey") {
            emitNamed("journey_started")         // scope_id = "checkout-journey": the ambient scope wins
        }
    }
}
