package dev.kotrace

/**
 * A **cross-trace edge** from the span carrying it to another trace. [Span.parentId] links spans within one tree;
 * a link joins two separate traces where one is about the other, such as a user-report trace pointing at the trace
 * whose failure it reports.
 *
 * It carries [traceId] only, no span id (ADR-009): a link is captured when the linking span opens, when only the
 * linked trace's root may exist. A backend that wants the failure walks the linked tree itself.
 *
 * [attributes] are **static symbols only**, like [Span.traits]: links reach [dev.kotrace.event.toJson].
 *
 * @sample dev.kotrace.samples.LinkSamples.linkUsage
 */
data class TraceLink(
    val traceId: String,
    val attributes: Map<String, String> = emptyMap(),
)
