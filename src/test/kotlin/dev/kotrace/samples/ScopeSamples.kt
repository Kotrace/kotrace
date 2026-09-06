package dev.kotrace.samples

import dev.kotrace.currentSpan
import dev.kotrace.event.emitException
import dev.kotrace.event.emitNamed
import dev.kotrace.event.log
import dev.kotrace.span
import dev.kotrace.withScope

/**
 * Dokka `@sample` targets for the span-less emit verbs and `withScope` (ADR-010) — compiled against the
 * real API by `check` (ADR-007), so the examples cannot drift. Not published: test source.
 */
@Suppress("unused")
internal object ScopeSamples {

    /**
     * A span-less emit — an occurrence outside any span (here, a push-notification callback with no open
     * trace). It fans **live-only** through the resolved config (context override, else the process-wide
     * `Kotrace` config); `emitException(cause, info)` rides
     * record-level `info` a consumer reads as an explicit-report marker.
     */
    fun spanlessEmit(cause: Throwable) {
        emitNamed("push_received", mapOf("channel" to "promo"))
        emitException(cause, mapOf("report" to "explicit"))
    }

    /**
     * A scope — a live-only correlation umbrella. Everything inside it, spans and orphan emits alike,
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
}
