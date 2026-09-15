package dev.kotrace

/**
 * The verdict a **return-aware** [span] reports for a **normally returned** value, plus any trace-level orphan
 * failures to attach (ADR-016, ADR-012). Returned by the `returnedOutcome` mapper on the auto-root path.
 *
 * [status] may be **any** [TraceStatus] — `OK`, `ERROR`, or even `CANCELLED` for a value the consumer treats
 * as a cancellation outcome: the mapper owns the returned-value verdict fully, and core does not second-guess
 * it. (Core hard-codes a status only for the two *escaping* paths — an escaping `CancellationException` is
 * `CANCELLED`, any other escaping throwable is `ERROR` — which the mapper never sees.)
 *
 * [attached] carries **only trace-level orphan failures** — throwables that belong to the trace as a whole
 * but are the birthplace of no span, the canonical case being a saga's suppressed rollback throwables
 * collected on the failed result value. Each rides [reportTrace]'s `attached` channel (ADR-012), keyed to the
 * root past the birthplace dedup. Do **not** put a throwable a span already records here — the technical
 * birthplace throwable reaches the report through the span tree (record it on the span via
 * [dev.kotrace.event.addException]); re-supplying it in [attached] would emit a duplicate
 * [dev.kotrace.event.ExceptionRecord].
 */
data class TraceOutcome(val status: TraceStatus, val attached: List<Throwable> = emptyList())
