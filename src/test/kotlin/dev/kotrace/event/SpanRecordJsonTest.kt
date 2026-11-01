package dev.kotrace.event

import dev.kotrace.SpanStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A [SpanRecord]'s `toJson` line (ADR-044): `"kind":"span"` with its status and its measured duration, and no
 * start or end, since a monotonic time means nothing outside the process.
 */
class SpanRecordJsonTest {

    private fun record(endNanos: Long?, status: SpanStatus) = SpanRecord(
        traceId = "4bf9",
        spanId = "00f0",
        parentId = null,
        operation = "checkout",
        atNanos = 100,
        spanAttributes = mapOf("http.status" to "200"),
        links = emptyList(),
        endNanos = endNanos,
        status = status,
    )

    @Test fun `an ended span renders its status and duration`() {
        assertEquals(
            """{"trace_id":"4bf9","span_id":"00f0","parent_span_id":null,"operation":"checkout","kind":"span",""" +
                """"span_status":"cancelled","duration_nanos":250,"span_attributes":{"http.status":"200"}}""",
            record(endNanos = 350, status = SpanStatus.CANCELLED).toJson(),
        )
    }

    @Test fun `an open span renders a null duration`() {
        assertEquals(
            """{"trace_id":"4bf9","span_id":"00f0","parent_span_id":null,"operation":"checkout","kind":"span",""" +
                """"span_status":"ok","duration_nanos":null,"span_attributes":{"http.status":"200"}}""",
            record(endNanos = null, status = SpanStatus.OK).toJson(),
        )
    }
}
