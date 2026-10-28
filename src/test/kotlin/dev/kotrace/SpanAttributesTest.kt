package dev.kotrace

import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.end
import dev.kotrace.bridge.startSpan
import dev.kotrace.event.LogRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.log
import dev.kotrace.event.toJson
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accepts every attribute, for tests that read attributes off records (ADR-039). */
private val acceptingAttributes = object : TracePolicy {
    override fun acceptsAttribute(key: String, value: String) = true
}

/**
 * The `attributes` a span opens with (ADR-032) seed [Span.attributes]: every record of the span carries them as
 * `spanAttributes` from its first event, a later [Span.putAttribute] adds to them, and the caller's map is
 * snapshotted. They stay payload: [Span.traits] remain the only per-span filter input.
 */
@OptIn(NonSuspendTracingBridge::class)
class SpanAttributesTest {

    /** Accepts every attribute, since these tests read the span's attributes off its records (ADR-039). */
    private class CollectingLive(override val policy: TracePolicy = acceptingAttributes) : LiveAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onLive(record: TraceRecord) {
            records += record
        }
    }

    @Test fun `span attributes ride the first live record and the JSON`() = runTest {
        val live = CollectingLive()

        withContext(TraceConfig(listOf(live))) {
            span("checkout", attributes = mapOf("flow" to "checkout")) {
                currentSpan()!!.log { "first event" }
            }
        }

        val first = live.records.filterIsInstance<LogRecord>().single()
        assertEquals("present before any putAttribute", mapOf("flow" to "checkout"), first.spanAttributes)
        assertTrue(first.toJson().contains("\"span_attributes\":{\"flow\":\"checkout\"}"))
    }

    @Test fun `startSpan takes attributes too`() = runTest {
        val live = CollectingLive()

        withContext(TraceConfig(listOf(live))) {
            val span = startSpan("http", attributes = mapOf("flow" to "pricing"))
            span.log { "sent" }
            span.end()
        }

        assertEquals(mapOf("flow" to "pricing"), live.records.filterIsInstance<LogRecord>().single().spanAttributes)
    }

    @Test fun `a later putAttribute adds to the opening attributes`() = runTest {
        val live = CollectingLive()

        withContext(TraceConfig(listOf(live))) {
            span("http", attributes = mapOf("flow" to "pricing")) {
                currentSpan()!!.putAttribute("http.status", "200")
                currentSpan()!!.log { "received" }
            }
        }

        assertEquals(
            mapOf("flow" to "pricing", "http.status" to "200"),
            live.records.filterIsInstance<LogRecord>().single().spanAttributes,
        )
    }

    @Test fun `the caller's map is snapshotted when the span opens`() = runTest {
        val live = CollectingLive()
        val attrs = mutableMapOf("flow" to "checkout")

        withContext(TraceConfig(listOf(live))) {
            span("checkout", attributes = attrs) {
                attrs["flow"] = "mutated"
                currentSpan()!!.log { "after the caller's write" }
            }
        }

        assertEquals(mapOf("flow" to "checkout"), live.records.filterIsInstance<LogRecord>().single().spanAttributes)
    }

    @Test fun `opening attributes are payload, never a filter input`() = runTest {
        // A policy that would drop the span if attributes were a filter key; only traits reach acceptsSpan.
        val live = CollectingLive(object : TracePolicy {
            override fun acceptsSpan(span: Span) = span.traits["layer"] != "repo"
        })

        withContext(TraceConfig(listOf(live))) {
            span("store", attributes = mapOf("layer" to "repo")) {
                currentSpan()!!.log { "kept" }
            }
        }

        assertEquals("the breadcrumb is delivered", 1, live.records.size)
    }
}
