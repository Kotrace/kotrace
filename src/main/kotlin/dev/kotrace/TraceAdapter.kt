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
     *
     * **Runs synchronously on the logging coroutine's thread, on the application's critical path.**
     * Whatever `onLive` does — including a blocking sink — is charged directly to the traced code: a sink
     * that parks ~1 ms per record adds ~1 ms of latency to that log call (benchmarked). kotrace does **not**
     * move this call off-thread; it will not silently make delivery async. If your sink does I/O
     * (network, disk), enqueue [record] to a bounded worker and return promptly here — do not block in
     * `onLive`. A bounded queue is deliberate: it is where you choose your backpressure (drop, block, or
     * coalesce) rather than letting an unbounded buffer grow under a hot trace.
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
     * **Consume [records] synchronously, within this call** (ADR-014). Fault isolation guards the whole
     * `onReport` invocation, so a policy/message fault raised while you iterate is contained here; retaining
     * the [Sequence] and consuming it after `onReport` returns escapes that guard and is unsupported.
     *
     * Synchronous means this runs on the trace-ending coroutine's thread, on its critical path: the walk
     * and every sink write here delay that coroutine's completion. Consume within the call, but if your sink
     * does I/O, drain the forced records into a bounded worker and return — the same backpressure guidance as
     * [LiveAdapter.onLive]. kotrace will not off-thread this for you.
     *
     * @sample dev.kotrace.samples.ReportAdapterSamples.onReportSelfGate
     */
    fun onReport(status: TraceStatus, records: Sequence<TraceRecord>)
}
