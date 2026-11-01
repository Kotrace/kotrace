package dev.kotrace.report

import dev.kotrace.Kotrace
import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.SpanStatus
import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.end
import dev.kotrace.bridge.startSpan
import dev.kotrace.currentSpan
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogRecord
import dev.kotrace.event.SpanRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.log
import dev.kotrace.span
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A report hands an adapter that asks for them one [SpanRecord] per span, before that span's events (ADR-044): every
 * span of the tree, an eventless one included, with its timing and status, through the same policy as every record.
 */
@OptIn(NonSuspendTracingBridge::class)
class SpanRecordReportTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    /** A report adapter keeping every record its [policy] lets through. */
    private class CollectingReport(override val policy: TracePolicy) : ReportAdapter {
        val records = CopyOnWriteArrayList<TraceRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            this.records += records
        }
    }

    /** The one line a span exporter's policy adds. */
    private val spanExporter = object : TracePolicy {
        override val acceptsSpanRecords = true
    }

    @Test fun `an adapter that does not ask receives no span record`() = runTest {
        val report = CollectingReport(object : TracePolicy {})

        withContext(TraceConfig(listOf(report))) { span("checkout") { currentSpan()!!.log { "priced" } } }

        assertEquals(listOf("priced"), report.records.map { (it as LogRecord).message })
    }

    @Test fun `every span reaches the report before its events, in tree order, an eventless one included`() = runTest {
        val report = CollectingReport(spanExporter)

        withContext(TraceConfig(listOf(report))) {
            span("checkout") {
                span("cart.validate") { } // logs nothing, so no other record would show it
                span("pay") { currentSpan()!!.log { "charged" } }
            }
        }

        val delivered = report.records.map { (if (it is SpanRecord) "span " else "log ") + it.operation }
        assertEquals(listOf("span checkout", "span cart.validate", "span pay", "log pay"), delivered)
    }

    @Test fun `a span record carries the span's identity, timing and status`() = runTest {
        val report = CollectingReport(spanExporter)
        val collector = SpanCollector()

        withContext(collector + TraceConfig(listOf(report))) {
            runCatching { span("checkout") { span("pay") { error("declined") } } }
            collector.reportTrace(TraceStatus.ERROR)
        }

        val pay = collector.spans.single { it.name == "pay" }
        val expected = SpanRecord(
            traceId = pay.traceId,
            spanId = pay.spanId,
            parentId = pay.parentId,
            operation = "pay",
            atNanos = pay.startNanos,
            spanAttributes = emptyMap(),
            links = emptyList(),
            endNanos = pay.endNanos,
            status = SpanStatus.ERROR,
        )
        assertEquals(expected, report.records.filterIsInstance<SpanRecord>().single { it.operation == "pay" })
    }

    @Test fun `acceptsSpan rejects a span's record, while its exception still arrives`() = runTest {
        val report = CollectingReport(object : TracePolicy {
            override val acceptsSpanRecords = true
            override fun acceptsSpan(span: Span) = span.traits["layer"] != "data"
        })

        withContext(TraceConfig(listOf(report))) {
            runCatching { span("checkout") { span("db.load", traits = mapOf("layer" to "data")) { error("disk") } } }
        }

        assertEquals(listOf("checkout"), report.records.filterIsInstance<SpanRecord>().map { it.operation })
        assertTrue(
            "a crash passes acceptsEvent alone, so the tree it hangs from can be partial",
            report.records.filterIsInstance<ExceptionRecord>().any { it.operation == "db.load" },
        )
    }

    @Test fun `a span record carries only the attributes the adapter admits`() = runTest {
        val report = CollectingReport(object : TracePolicy {
            override val acceptsSpanRecords = true
            override fun acceptsAttribute(key: String, value: String) = key == "http.status"
        })

        withContext(TraceConfig(listOf(report))) {
            span("call") {
                currentSpan()!!.putAttribute("http.status", "200")
                currentSpan()!!.putAttribute("account.plan", "gold")
            }
        }

        assertEquals(mapOf("http.status" to "200"), report.records.filterIsInstance<SpanRecord>().single().spanAttributes)
    }

    @Test fun `a span still open when the report begins stays open in it`() = runTest {
        val collector = SpanCollector()
        lateinit var late: Span
        var delivered: SpanRecord? = null
        val report = object : ReportAdapter {
            override val policy = spanExporter
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                late.end() // ends after the report began, before its records are read
                delivered = records.filterIsInstance<SpanRecord>().single()
            }
        }

        withContext(collector + TraceConfig(listOf(report))) {
            late = startSpan("http.get", parent = null, collector = collector)
            collector.reportTrace(TraceStatus.OK)
        }

        assertNull("the report never learns of an end after it began", delivered!!.endNanos)
        assertEquals(SpanStatus.OK, delivered!!.status)
        assertNotNull("the span itself did end", late.endNanos)
    }

    @Test fun `a live adapter never receives a span record`() = runTest {
        val live = CopyOnWriteArrayList<TraceRecord>()
        val reported = CopyOnWriteArrayList<TraceRecord>()
        val both = object : LiveAdapter, ReportAdapter {
            override val policy = spanExporter
            override fun onLive(record: TraceRecord) {
                live += record
            }
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                reported += records
            }
        }

        withContext(TraceConfig(listOf(both))) { span("checkout") { currentSpan()!!.log { "priced" } } }

        assertTrue("live: the log only", live.none { it is SpanRecord } && live.size == 1)
        assertTrue("report: the span too", reported.any { it is SpanRecord })
    }
}
