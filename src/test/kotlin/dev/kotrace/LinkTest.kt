package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.log
import dev.kotrace.event.recordOf
import dev.kotrace.event.toJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-trace links (ADR-009). A [TraceLink] is a birth-set, span-scoped reference to another *trace* by
 * `trace_id` only — it rides onto every record lifted off the linking span and surfaces in `toJson` as a
 * nested `links` array. A span with no links is unchanged on the wire.
 */
class LinkTest {

    @Test
    fun `a span defaults to no links`() {
        val span = Span("t", "s", null, "op", startNanos = 0L)
        assertTrue("no links unless supplied", span.links.isEmpty())
    }

    @Test
    fun `span opens its root carrying the supplied links`() = runTest {
        val link = TraceLink(traceId = "aaaa1111", attributes = mapOf("reason" to "user_report"))
        val spans = collectTrace {
            span("user_report_error", links = listOf(link)) {
                currentSpan()?.log(mapOf("level" to "INFO")) { "reporting" }
            }
        }
        val root = spans.single { it.parentId == null }
        assertEquals("the root carries the birth-set link", listOf(link), root.links)
    }

    @Test
    fun `recordOf stamps the span's links onto every record kind`() {
        val link = TraceLink(traceId = "aaaa1111")
        val span = Span("t", "s", null, "op", startNanos = 0L, links = listOf(link))

        val log = span.recordOf(LogEvent(emptyMap(), { "m" }, atNanos = 0L))
        val named = span.recordOf(NamedEvent("evt", emptyMap(), atNanos = 0L))
        val exc = span.recordOf(ExceptionEvent(IllegalStateException("x"), atNanos = 0L))

        assertEquals("log record carries the links", listOf(link), log.links)
        assertEquals("named record carries the links", listOf(link), named.links)
        assertEquals("exception record carries the links", listOf(link), exc.links)
    }

    @Test
    fun `toJson emits a nested links array with per-link attributes`() {
        val span = Span(
            "abc", "def", null, "user_report_error", startNanos = 0L,
            links = listOf(TraceLink(traceId = "aaaa1111", attributes = mapOf("reason" to "user_report"))),
        )
        val json = span.recordOf(LogEvent(emptyMap(), { "m" }, atNanos = 0L)).toJson()
        assertTrue(
            "links render as a nested array of trace_id + attributes objects",
            json.contains("\"links\":[{\"trace_id\":\"aaaa1111\",\"attributes\":{\"reason\":\"user_report\"}}]"),
        )
    }

    @Test
    fun `toJson omits a link's empty attributes object`() {
        val span = Span("abc", "def", null, "op", startNanos = 0L, links = listOf(TraceLink(traceId = "aaaa1111")))
        val json = span.recordOf(LogEvent(emptyMap(), { "m" }, atNanos = 0L)).toJson()
        assertTrue("bare trace_id, no attributes key", json.contains("\"links\":[{\"trace_id\":\"aaaa1111\"}]"))
    }

    @Test
    fun `toJson omits the links key entirely when a span has none`() {
        val span = Span("abc", "def", null, "op", startNanos = 0L)
        val json = span.recordOf(LogEvent(emptyMap(), { "m" }, atNanos = 0L)).toJson()
        assertFalse("no links key on an unlinked span", json.contains("\"links\""))
    }
}
