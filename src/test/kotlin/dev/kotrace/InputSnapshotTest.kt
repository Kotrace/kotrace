package dev.kotrace

import dev.kotrace.event.LogRecord
import dev.kotrace.event.NamedRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addNamed
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A caller's map is snapshotted where it enters kotrace (ADR-028), like [Span.attributes] always was
 * (ADR-001): mutating it afterwards changes nothing kotrace reports, so the live and report paths always see
 * the same values — and a report walk never races a caller's write.
 */
class InputSnapshotTest {

    private class CollectingReport : ReportAdapter {
        override val policy = object : TracePolicy {}
        val records = mutableListOf<TraceRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            this.records += records
        }
    }

    @Test fun `event attributes and link attributes are snapshotted at the verb`() = runTest {
        val report = CollectingReport()
        val logAttrs = mutableMapOf("level" to "WARN")
        val linkAttrs = mutableMapOf("relation" to "reports")

        withContext(TraceConfig(listOf(report))) {
            span("op", links = listOf(TraceLink("a".repeat(32), linkAttrs))) {
                val span = currentSpan()!!
                span.log(logAttrs) { "breadcrumb" }
                logAttrs["level"] = "DEBUG" // mutated after the verb returned
                linkAttrs["relation"] = "mutated"
            }
        }

        val log = report.records.filterIsInstance<LogRecord>().single()
        assertEquals("the log keeps the attributes it was given", mapOf("level" to "WARN"), log.attributes)
        assertEquals("the link keeps its attributes too", mapOf("relation" to "reports"), log.links.single().attributes)
    }

    @Test fun `a named event's attributes are snapshotted at the verb`() = runTest {
        val attrs = mutableMapOf("plan" to "pro")
        val spans = collectTrace {
            span("op") {
                currentSpan()!!.addNamed("signup", attrs)
                attrs["plan"] = "free"
            }
        }

        @OptIn(UnredactedTraceRead::class)
        val named = spans.single().events.single() as dev.kotrace.event.NamedEvent
        assertEquals(mapOf("plan" to "pro"), named.attributes)
    }
}
