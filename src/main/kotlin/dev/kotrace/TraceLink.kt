package dev.kotrace

/**
 * A **cross-trace causal edge** — a reference from the span carrying it to another *trace*, by that trace's
 * [traceId]. Distinct from [Span.parentId], the **in-tree** edge (one `trace_id`, caller → callee): a link
 * joins two *separate* trees, the OTel *span link* shape. Use it when two flows are deliberately separate
 * traces, yet one is caused-by / about the other — e.g., a user-report trace pointing at the trace whose
 * failure is being reported.
 *
 * **Trace-level, not span-level: a link carries [traceId] only, no `span_id`** (ADR-009). The linked
 * trace's identity is settled at *its* birth (`traceId` minted when its root opens), and a consumer captures
 * it then — at which point only that trace's root span exists, so any `span_id` would be the root's, which
 * is redundant with `traceId` (a backend holding `traceId` finds the root by `parentId == null`). Targeting
 * the real failure node instead would force waiting for the linked trace to finish, breaking the property
 * that a link is fixed when the *linking* span is created. A backend correlates by [traceId] and, if it
 * wants the failure, walks the linked tree to its birthplace [dev.kotrace.event.ExceptionRecord] itself.
 *
 * [attributes] are **static symbols only**, the same PII invariant as [Span.attributes]: a link rides the
 * general log / backend egress ([dev.kotrace.event.toJson]), so it must carry no user data. [traceId] is a
 * random hex id, not user content.
 *
 * @sample dev.kotrace.samples.LinkSamples.linkUsage
 */
data class TraceLink(
    val traceId: String,
    val attributes: Map<String, String> = emptyMap(),
)
