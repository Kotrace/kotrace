package dev.kotrace.samples

import dev.kotrace.TraceLink
import dev.kotrace.currentSpan
import dev.kotrace.event.log
import dev.kotrace.span

/**
 * Dokka `@sample` targets for [TraceLink] cross-trace correlation (ADR-009) — compiled against the real API
 * by `check`, so an example that stops matching the API breaks the build instead of drifting (D3 / ADR-007).
 * Not published: test source.
 */
@Suppress("unused")
internal object LinkSamples {

    /**
     * Cross-trace link: a user-report flow is its own trace, yet it is *about* the trace that failed. Capture
     * the failed trace's `trace_id` when it is minted, then open the report span carrying a [TraceLink] to it.
     * The link is birth-set — fixed when the linking span opens — and rides onto every record lifted off it,
     * surfacing as a nested `links` array on the wire. [attributes] are static symbols only, no user data.
     */
    suspend fun linkUsage(failedTraceId: String) {
        val link = TraceLink(traceId = failedTraceId, attributes = mapOf("reason" to "user_report"))
        span("user_report_error", links = listOf(link)) {
            currentSpan()?.log(mapOf("level" to "INFO")) { "user reported a failed trace" }
        }
    }
}
