package dev.kotrace.report

import dev.kotrace.Kotrace
import dev.kotrace.SpanCollector
import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.UnredactedTraceRead
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.collectTrace
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.span
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A manual [SpanCollector] that holds more than one tree (ADR-026): two top-level spans (two traces), or a
 * collector seeded inside a traced flow whose spans hang off a parent it does not hold. The report walks every
 * root; before, it walked only the first parentless span and silently dropped the rest.
 */
class ReportRootsTest {

    private class CollectingReport : ReportAdapter {
        override val policy = object : TracePolicy {}
        val records = mutableListOf<TraceRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            this.records += records
        }
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    @Test fun `a manual collector with two roots reports both trees`() = runTest {
        val report = CollectingReport()
        val collector = SpanCollector()

        withContext(collector) { span("first") { } }
        withContext(collector) { runCatching { span("second") { throw IllegalStateException("second failed") } } }
        withContext(TraceConfig(listOf(report))) { collector.reportTrace(TraceStatus.ERROR) }

        val failure = report.records.filterIsInstance<ExceptionRecord>().single()
        assertEquals("the second tree's failure is reported, not dropped", "second", failure.operation)
        val second = collector.spans.single { it.name == "second" }
        assertEquals("the record keeps its own trace", second.traceId, failure.traceId)
    }

    @Test fun `a manual collector nested in a traced flow reports its subtree`() = runTest {
        val report = CollectingReport()
        val inner = SpanCollector()

        val outerSpans = collectTrace {
            span("outer") {
                // A helper that seeds its own collector (the README pattern), called from inside a traced flow:
                // its span is a child of "outer", which the inner collector does not hold.
                withContext(inner) { runCatching { span("helper") { throw IllegalStateException("helper failed") } } }
                withContext(TraceConfig(listOf(report))) { inner.reportTrace(TraceStatus.ERROR) }
            }
        }

        val failure = report.records.filterIsInstance<ExceptionRecord>().single()
        assertEquals("helper", failure.operation)
        val outer = outerSpans.single { it.name == "outer" }
        assertEquals("the subtree keeps its real parent, so it still correlates with the outer trace", outer.spanId, failure.parentId)
        assertEquals(outer.traceId, failure.traceId)
    }

    @OptIn(UnredactedTraceRead::class)
    @Test fun `renderTree shows every root, not only the first`() = runTest {
        val collector = SpanCollector()
        withContext(collector) { span("first") { } }
        withContext(collector) { span("second") { } }

        val rendered = collector.spans.renderTree()

        assertTrue(rendered, rendered.contains("└─ first") && rendered.contains("└─ second"))
        assertEquals("one header per tree", 2, rendered.lines().count { it.startsWith("✓ trace") })
    }
}
