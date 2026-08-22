package dev.kotrace

import dev.kotrace.event.TraceRecord

/**
 * A consumer's fan-out destination. Sealed to exactly two roles — an adapter is a [LiveAdapter], a
 * [ReportAdapter], or both; an adapter with no phase is meaningless. Each carries a [TracePolicy];
 * the role interface adds the routing hook. This is the whole
 * consumer surface for fan-out — kotrace walks the tree, the adapter decides format and destination.
 *
 * Many adapters = many independent routes off one trace (a Logcat live watch, a crash report on
 * failure, a success sink, and a failure sink split by [TraceStatus]) — kotrace offers each every record
 * its policy accepts, with no core change.
 */
sealed interface TraceAdapter {
    val policy: TracePolicy
}

/**
 * Receives each [TraceRecord] the moment its event is logged — the per-event path, for a debug watch
 * of traffic as it happens.
 */
interface LiveAdapter : TraceAdapter {
    /**
     * No trace outcome is known yet; [record] is offered as it is appended.
     */
    fun onLive(record: TraceRecord)
}

/**
 * Receives the whole trace once, at its end — the report path.
 */
interface ReportAdapter : TraceAdapter {
    /**
     * [records] is a lazy [Sequence] already walked (tree order, birthplace resolved) and filtered by
     * this adapter's [policy]. [status] is the trace's verdict, passed **before** [records] is forced, so an
     * adapter self-gates on it and pays no walk for an outcome it skips — the predicate is the adapter's to
     * choose. The point is the laziness: gating on [status] costs nothing because the sequence is never
     * forced.
     *
     * @sample dev.kotrace.samples.ReportAdapterSamples.onReportSelfGate
     */
    fun onReport(status: TraceStatus, records: Sequence<TraceRecord>)
}
