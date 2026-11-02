package dev.kotrace.report

import dev.kotrace.Kotrace
import dev.kotrace.SpanCollector
import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
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
import org.junit.Before
import org.junit.Test

class ReportSpanOrderTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    @Test fun `span and event views keep tree order with eventless spans and attached orphans`() = runTest {
        val delivered = mutableListOf<List<String>>()
        fun adapter(keepSpans: Boolean) = object : ReportAdapter {
            override val policy = object : TracePolicy { override val acceptsSpanRecords = keepSpans }
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                fun read() = records.map {
                    when (it) {
                        is SpanRecord -> "span ${it.operation}"
                        is LogRecord -> "log ${it.message}"
                        is ExceptionRecord -> "exception ${it.throwable.message}"
                        else -> error("unexpected record")
                    }
                }.toList()
                val first = read()
                assertEquals("a second read keeps the same order", first, read())
                delivered += first
            }
        }
        val collector = SpanCollector()

        withContext(collector + TraceConfig(listOf(adapter(false), adapter(true)))) {
            span("root") {
                currentSpan()!!.log { "root" }
                span("empty") { }
                span("child") { currentSpan()!!.log { "child" } }
            }
            span("other") { }
            collector.reportTrace(TraceStatus.ERROR, listOf(IllegalStateException("orphan")))
        }

        assertEquals(listOf("log root", "log child", "exception orphan"), delivered[0])
        assertEquals(
            listOf("span root", "log root", "span empty", "span child", "log child", "span other", "exception orphan"),
            delivered[1],
        )
    }

    @Test fun `a throwing span record gate is unread when an adapter skips the status`() = runTest {
        var statusSeen: TraceStatus? = null
        var gateReads = 0
        val report = object : ReportAdapter {
            override val policy = object : TracePolicy {
                override val acceptsSpanRecords: Boolean get() {
                    gateReads++
                    error("unread gate")
                }
            }
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                statusSeen = status
            }
        }

        withContext(TraceConfig(listOf(report))) { span("root") { } }

        assertEquals(TraceStatus.OK, statusSeen)
        assertEquals(0, gateReads)
    }
}
