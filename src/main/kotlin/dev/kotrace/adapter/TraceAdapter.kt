package dev.kotrace.adapter

import dev.kotrace.TraceStatus
import dev.kotrace.event.TraceRecord

/**
 * A consumer's fan-out destination: a [LiveAdapter], a [ReportAdapter], or both. kotrace offers it every record
 * its [TracePolicy] accepts; the adapter decides format and destination. Several adapters are independent routes
 * off one trace (a Logcat live watch, a crash report on failure, sinks split by [TraceStatus]).
 */
sealed interface TraceAdapter {
    val policy: TracePolicy
}

/** Receives each [TraceRecord] the moment its event is logged, for a watch of traffic as it happens. */
interface LiveAdapter : TraceAdapter {
    /**
     * Offered [record] as it is appended; no trace outcome is known yet.
     *
     * **Runs synchronously on the logging thread, on the application's critical path:** a sink that blocks 1
     * millisecond adds 1 millisecond to that log call. kotrace never makes delivery asynchronous for you. If the sink
     * do I/O, enqueue [record] to a bounded worker, where you choose the backpressure (drop, block, coalesce), and
     * return.
     *
     * **Logging through kotrace from here is stored but not fanned live again** (ADR-025), so an adapter never
     * recurses into itself; a span-less emit made here is dropped. The guard is per-thread, so do not forward
     * kotrace's records back into kotrace from another thread.
     *
     * **I/O from a sink.** An HTTP call made inside `onLive` through a `TracingCallFactory` is not traced
     * (ADR-025), but a worker thread is not covered: it must call through the plain `OkHttpClient` (the app's own
     * is fine), never the factory, or failed uploads feed the sink forever. A write through a Room database built
     * with `kotrace-room`'s `tracing()` still logs a sensitive `SQL …` line per record onto the flow's span, so give
     * a sink its own, uninstrumented database.
     */
    fun onLive(record: TraceRecord)
}

/** Receives the whole trace once, at its end. */
interface ReportAdapter : TraceAdapter {
    /**
     * [records] is lazy, in tree order, and already filtered by this adapter's [policy]. [status] comes first, so
     * an adapter that skips an outcome never forces [records] and pays no walk (ADR-029).
     *
     * **Consume [records] within this call** (ADR-014): the fault guard covers only this invocation, so a sequence
     * kept and read later is unsupported. This runs on the trace-ending thread, on its critical path; a sink that
     * do I/O should hand the records to a bounded worker, as for [LiveAdapter.onLive].
     *
     * @sample dev.kotrace.samples.ReportAdapterSamples.onReportSelfGate
     */
    fun onReport(status: TraceStatus, records: Sequence<TraceRecord>)
}
