package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.SpanStatus
import dev.kotrace.TraceLink

/**
 * A span itself, flattened for a report (ADR-044): what a span backend (OTLP, Tempo, Jaeger) needs to draw the tree,
 * one per span, a span with no event included. Only an adapter whose policy sets
 * [dev.kotrace.adapter.TracePolicy.acceptsSpanRecords] receives one, and only from a report, never live.
 *
 * [atNanos] is the span's start and [endNanos] its end, both from `System.nanoTime()`: they measure the span and
 * order it among the trace's records, but are no wall-clock time (see [TraceRecord]). A backend that needs a
 * timestamp converts at export with an anchor of its own. [endNanos] is null when the span was still open as the
 * report began, such as a bridge span whose call outlived the root; that report never learns of its end, so skip
 * such a span or show it open, and never invent an end. [status] is the span's [SpanStatus] at that same moment.
 *
 * It holds no traits (ADR-032), no event and no throwable: the span's events follow it as records of their own. Its
 * identity is never null, unlike a span-less record's.
 */
data class SpanRecord(
    override val traceId: String,
    override val spanId: String,
    override val parentId: String?,
    override val operation: String,
    override val atNanos: Long,
    override val scopeId: String? = null,
    override val spanAttributes: Map<String, String>,
    override val links: List<TraceLink>,
    val endNanos: Long?,
    val status: SpanStatus,
) : TraceRecord

/** Flattens this span into its [SpanRecord], with [lifecycle], its status and end frozen when the report began. */
internal fun Span.spanRecordOf(lifecycle: Span.State): SpanRecord =
    SpanRecord(traceId, spanId, parentId, name, startNanos, scopeId, attributes.toMap(), links, lifecycle.endNanos, lifecycle.status)
