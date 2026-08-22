package dev.kotrace.samples

import dev.kotrace.ReportAdapter
import dev.kotrace.TraceStatus
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.toJson

/**
 * Dokka `@sample` targets for KDoc — compiled against the real API by `check`, so an example that stops
 * matching the API breaks the build instead of drifting silently in prose (D3 / ADR-007). Not published:
 * this lives in test source.
 */
@Suppress("unused")
internal object ReportAdapterSamples {

    /**
     * A failure-only [ReportAdapter.onReport]: self-gate on the verdict, then walk the records. Returning
     * on a clean trace before touching [records] means the lazy sequence is never forced. Prefer
     * `status == TraceStatus.OK` over `!= TraceStatus.ERROR`, which would also skip a [TraceStatus.CANCELLED]
     * trace.
     */
    fun onReportSelfGate(status: TraceStatus, records: Sequence<TraceRecord>) {
        if (status == TraceStatus.OK) return
        records.forEach { record -> println(record.toJson()) }
    }
}
