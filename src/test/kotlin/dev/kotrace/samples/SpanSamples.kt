package dev.kotrace.samples

import dev.kotrace.TraceOutcome
import dev.kotrace.TraceStatus
import dev.kotrace.currentSpan
import dev.kotrace.event.addException
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

    /** A domain result carried as a **value** (ADR-016): a failure is returned, not thrown. */
    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Failed(val cause: Throwable, val rollbackErrors: List<Throwable> = emptyList()) : Outcome<Nothing>
    }

    /**
     * The failure-as-value idiom: `returnedOutcome` maps a **returned** [Outcome.Failed] to
     * [TraceStatus.ERROR] and rides its rollback throwables up as [TraceOutcome.attached]; the technical
     * cause is recorded on the root span with [addException] so a crash sink receives it. A thrown failure
     * and cancellation are still core's to classify — the mapper never sees them. The [charge] closure is the
     * real use case (a repository/use-case call returning a domain result), so both branches are live.
     */
    suspend fun spanReturnedOutcomeUsage(charge: suspend () -> Outcome<Int>): Outcome<Int> =
        span(
            name = "checkout",
            returnedOutcome = { result ->
                when (result) {
                    is Outcome.Ok -> TraceOutcome(TraceStatus.OK)
                    is Outcome.Failed -> TraceOutcome(TraceStatus.ERROR, attached = result.rollbackErrors)
                }
            },
        ) {
            val result = charge()
            if (result is Outcome.Failed) currentSpan()?.addException(result.cause)
            result
        }
}
