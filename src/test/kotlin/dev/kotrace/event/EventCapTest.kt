// Tests inspect the raw span timeline (ADR-028); nothing here reaches a sink.
@file:OptIn(dev.kotrace.UnredactedTraceRead::class)

package dev.kotrace.event

import dev.kotrace.Span
import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.collectTrace
import dev.kotrace.currentSpan
import dev.kotrace.span
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A span's timeline is bounded (ADR-029): past [Span.MAX_EVENTS] it discards the oldest named events, then the
 * oldest logs, and an exception only when nothing else is left; it counts every discard.
 */
class EventCapTest {

    @Test fun `a span keeps the newest breadcrumbs and never drops an exception`() = runTest {
        val boom = IllegalStateException("boom")
        val spans = collectTrace {
            span("session") {
                val span = currentSpan()!!
                repeat(10) { i -> span.log { "early $i" } }
                span.addException(boom)
                repeat(1_490) { i -> span.log { "late $i" } }
            }
        }

        val span = spans.single()
        val logs = span.events.filterIsInstance<LogEvent>().map { it.message }
        assertEquals("bounded at the cap", Span.MAX_EVENTS, span.events.size)
        assertTrue("the exception survives", span.events.any { it is ExceptionEvent && it.throwable === boom })
        assertEquals("the newest breadcrumb is kept", "late 1489", logs.last())
        assertTrue("the oldest breadcrumbs went first", logs.none { it.startsWith("early") })
        assertEquals(501, span.droppedEvents)
        assertEquals(
            "the drop count rides the span's attributes", "501", span.attributes[Span.EVENTS_DROPPED_ATTRIBUTE],
        )
    }

    @Test fun `a reported record says its span dropped events`() = runTest {
        val collected = mutableListOf<TraceRecord>()
        val report = object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                collected += records
            }
        }

        withContext(TraceConfig(listOf(report))) {
            span("session") { repeat(Span.MAX_EVENTS + 5) { i -> currentSpan()!!.log { "line $i" } } }
        }

        val logs = collected.filterIsInstance<LogRecord>()
        assertEquals(Span.MAX_EVENTS, logs.size)
        assertTrue("every record of the span carries the drop count", logs.all { it.spanAttributes[Span.EVENTS_DROPPED_ATTRIBUTE] == "5" })
    }

    @Test fun `a span recording only exceptions is bounded too, keeping the newest`() = runTest {
        val spans = collectTrace {
            span("worker") {
                val span = currentSpan()!!
                repeat(20_000) { i -> span.addException(IllegalStateException("handled $i")) }
            }
        }

        val span = spans.single()
        assertEquals("bounded even with nothing but exceptions", Span.MAX_EVENTS, span.events.size)
        assertEquals(19_000, span.droppedEvents)
        assertEquals("the newest exception is kept", "handled 19999", (span.events.last() as ExceptionEvent).throwable.message)
    }

    @Test fun `analytics events are dropped before the logs a failure report needs`() = runTest {
        val collected = mutableListOf<TraceRecord>()
        val report = object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                collected += records
            }
        }

        runCatching {
            withContext(TraceConfig(listOf(report))) {
                span("screen") {
                    val span = currentSpan()!!
                    repeat(10) { i -> span.log { "step $i" } }
                    repeat(Span.MAX_EVENTS) { i -> span.addNamed("impression", mapOf("i" to "$i")) }
                    error("boom")
                }
            }
        }

        assertEquals(
            "every breadcrumb survives; the named events made room",
            (0 until 10).map { "step $it" },
            collected.filterIsInstance<LogRecord>().map { it.message },
        )
    }

    @Test fun `concurrent appends hold the cap exactly and count every drop`() {
        val span = Span("t", "s", null, "shared", startNanos = 0L)
        val threads = 8
        val perThread = 20_000
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (1..threads).map {
            kotlin.concurrent.thread {
                start.await()
                repeat(perThread) { i -> span.log { "line $i" } }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals("appends are serialized, so no race passes the cap", Span.MAX_EVENTS, span.events.size)
        assertEquals("every append is either kept or counted", threads * perThread, span.events.size + span.droppedEvents)
        assertEquals("the attribute ends on the final count", span.droppedEvents.toString(), span.attributes[Span.EVENTS_DROPPED_ATTRIBUTE])
    }
}
