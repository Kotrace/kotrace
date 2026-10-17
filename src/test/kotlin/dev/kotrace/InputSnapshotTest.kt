package dev.kotrace

import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogRecord
import dev.kotrace.event.NamedRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addNamed
import dev.kotrace.event.emitException
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A caller's map is snapshotted where it enters kotrace (ADR-028), like [Span.traits] always was
 * (ADR-001): mutating it afterwards changes nothing kotrace reports, so the live and report paths always see
 * the same values, and a report walk never races a caller's write.
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

    @Test fun `traits are snapshotted and reject a cast-back mutation`() = runTest {
        val traits = mutableMapOf("layer" to "http", "flow" to "checkout")
        val spans = collectTrace {
            span("op", traits = traits) {
                traits["layer"] = "repo"
            }
        }

        val span = spans.single()
        assertEquals(mapOf("layer" to "http", "flow" to "checkout"), span.traits)
        @Suppress("UNCHECKED_CAST")
        val forced = span.traits as MutableMap<String, String>
        assertThrows(UnsupportedOperationException::class.java) { forced["layer"] = "repo" }
    }

    @Test fun `attributes are a live view that only putAttribute writes`() = runTest {
        val spans = collectTrace {
            span("op", attributes = mapOf("flow" to "checkout")) {
                currentSpan()!!.putAttribute("http.status", "200")
            }
        }

        val span = spans.single()
        assertEquals("the view reflects a later putAttribute", "200", span.attributes["http.status"])
        @Suppress("UNCHECKED_CAST")
        val forced = span.attributes as MutableMap<String, String>
        assertThrows(UnsupportedOperationException::class.java) { forced.remove("flow") }
        assertThrows(UnsupportedOperationException::class.java) { forced["flow"] = "mutated" }
        assertEquals(mapOf("flow" to "checkout", "http.status" to "200"), span.attributes)
    }

    @Test fun `links and their attributes reject a cast-back mutation`() = runTest {
        val links = listOf(TraceLink("a".repeat(32), mapOf("relation" to "reports", "kind" to "user")))
        val spans = collectTrace {
            span("op", links = links) {}
        }

        val span = spans.single()
        @Suppress("UNCHECKED_CAST")
        val forcedList = span.links as MutableList<TraceLink>
        assertThrows(UnsupportedOperationException::class.java) { forcedList += TraceLink("b".repeat(32)) }
        @Suppress("UNCHECKED_CAST")
        val forcedMap = span.links.single().attributes as MutableMap<String, String>
        assertThrows(UnsupportedOperationException::class.java) { forcedMap["relation"] = "mutated" }
        assertEquals(links, span.links)
    }

    @Test fun `a span-less exception's attributes are snapshotted at the verb`() = runTest {
        val live = object : LiveAdapter {
            override val policy = object : TracePolicy {}
            val records = mutableListOf<TraceRecord>()
            override fun onLive(record: TraceRecord) {
                records += record // retained past the call, as a queueing sink would
            }
        }
        val attrs = mutableMapOf("screen" to "checkout")

        withContext(TraceConfig(listOf(live))) {
            emitException(IllegalStateException("boom"), attrs)
        }
        attrs["screen"] = "mutated"

        val record = live.records.single() as ExceptionRecord
        assertEquals(mapOf("screen" to "checkout"), record.spanAttributes)
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
