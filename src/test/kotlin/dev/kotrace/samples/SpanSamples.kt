package dev.kotrace.samples

import dev.kotrace.currentSpan
import dev.kotrace.event.log
import dev.kotrace.span

/**
 * Dokka `@sample` targets for the core `span {}` verb — compiled against the real API by `check` (ADR-007),
 * so the example cannot drift. Not published: test source.
 */
@Suppress("unused")
internal object SpanSamples {

    /**
     * The core idiom: open a span, drop a breadcrumb on it, nest a child. Severity is a plain `"level"`
     * attribute — kotrace ranks nothing. On a throw, [span] marks the span ERROR and rethrows unchanged.
     */
    suspend fun spanUsage() {
        span("checkout") {
            currentSpan()?.log(mapOf("level" to "INFO")) { "checkout started" }
            span("payment.charge") {
                currentSpan()?.log(mapOf("level" to "INFO")) { "charging card" }
            }
        }
    }
}
