package dev.kotrace.room

import dev.kotrace.event.AttributedEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.ReportAdapter
import dev.kotrace.SpanCollector
import dev.kotrace.event.SpanEvent
import dev.kotrace.TraceConfig
import dev.kotrace.TracePolicy
import dev.kotrace.event.TraceRecord
import dev.kotrace.TraceStatus
import dev.kotrace.reportTrace
import dev.kotrace.span
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The callback logs the executed SQL onto the active span (resolved via the `currentThreadSpan()` ThreadLocal,
 * which is live on the query thread) and never logs the bound values. Severity rides as a caller-supplied
 * `"level"` attribute — kotrace holds no level taxonomy — which the consumer's capture policy ranks.
 */
class TracingQueryCallbackTest {

    private val debug = mapOf("level" to "DEBUG")

    /** A report adapter whose policy accepts every event, incl. DEBUG. */
    private val captureAll = object : ReportAdapter {
        override val policy = object : TracePolicy {
            override fun acceptsEvent(event: SpanEvent) = true
        }
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) = Unit
    }

    /** A collecting report adapter whose policy drops DEBUG (keeps INFO/WARN/ERROR) — the typical production default. */
    private val dropDebug = object : ReportAdapter {
        val records = mutableListOf<TraceRecord>()
        override val policy = object : TracePolicy {
            override fun acceptsEvent(event: SpanEvent) =
                (event as? AttributedEvent)?.attributes?.get("level")?.let { it in setOf("INFO", "WARN", "ERROR") } ?: true
        }
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            this.records += records
        }
    }

    @Test
    fun `logs SQL text to the active span, never the bind args`() = runTest {
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(captureAll))) {
            span("account.get") {
                TracingQueryCallback(debug).onQuery("SELECT * FROM account WHERE mail = ?", listOf("user@example.com"))
            }
        }

        val event = collector.spans.single().events.filterIsInstance<LogEvent>().single()
        assertEquals("DEBUG", event.attributes["level"])
        assertTrue("logs the SQL template", event.message.contains("SELECT * FROM account WHERE mail = ?"))
        assertFalse("never logs the bound value", event.message.contains("user@example.com"))
    }

    @Test
    fun `DEBUG SQL is stored but a policy keeping only INFO and above drops it at fan-out`() = runTest {
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(dropDebug))) {
            span("account.get") {
                TracingQueryCallback(debug).onQuery("SELECT 1", emptyList())
            }
            collector.reportTrace(TraceStatus.OK)
        }
        assertFalse("DEBUG event is stored — no capture gate (ADR-002)", collector.spans.single().events.isEmpty())
        assertTrue("but the policy drops it at fan-out", dropDebug.records.isEmpty())
    }
}
