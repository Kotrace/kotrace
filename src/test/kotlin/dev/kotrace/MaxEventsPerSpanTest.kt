// Tests inspect the raw span timeline (ADR-028); nothing here reaches a sink.
@file:OptIn(UnredactedTraceRead::class, KotraceTestApi::class, NonSuspendTracingBridge::class)

package dev.kotrace

import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.end
import dev.kotrace.bridge.startSpan
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * [TraceConfig.maxEventsPerSpan] sets each span's cap (ADR-033): read once when the span opens, from the per-flow
 * override, else the installed configuration, else [Span.MAX_EVENTS]. It bounds every span on its own, never the
 * trace as a whole.
 */
class MaxEventsPerSpanTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    @Test fun `a span opened under a per-flow configuration takes its cap`() = runTest {
        val spans = collectTrace {
            withContext(TraceConfig(emptyList(), maxEventsPerSpan = 10)) {
                span("sync") { repeat(15) { i -> currentSpan()!!.log { "line $i" } } }
            }
        }

        val span = spans.single()
        assertEquals(10, span.maxEvents)
        assertEquals(10, span.events.size)
        assertEquals(5, span.droppedEvents)
    }

    @Test fun `the cap bounds each span, not the trace`() = runTest {
        val spans = collectTrace {
            withContext(TraceConfig(emptyList(), maxEventsPerSpan = 3)) {
                span("root") {
                    repeat(3) { i -> currentSpan()!!.log { "root $i" } }
                    span("child") { repeat(3) { i -> currentSpan()!!.log { "child $i" } } }
                }
            }
        }

        assertEquals("each span keeps its own 3", listOf(3, 3), spans.map { it.events.size })
        assertEquals(listOf(0, 0), spans.map { it.droppedEvents })
    }

    @Test fun `the cap is fixed when the span opens`() = runTest {
        val spans = collectTrace {
            withContext(TraceConfig(emptyList(), maxEventsPerSpan = 10)) {
                span("outer") {
                    val outer = currentSpan()!!
                    withContext(TraceConfig(emptyList(), maxEventsPerSpan = 2)) {
                        repeat(15) { i -> outer.log { "line $i" } }
                        span("inner") {}
                    }
                }
            }
        }

        val outer = spans.single { it.name == "outer" }
        assertEquals("a later overlay leaves an open span's cap alone", 10, outer.events.size)
        assertEquals("a span opened under the overlay takes its cap", 2, spans.single { it.name == "inner" }.maxEvents)
    }

    @Test fun `startSpan takes the cap too`() = runTest {
        withContext(TraceConfig(emptyList(), maxEventsPerSpan = 4)) {
            val span = startSpan("http")
            span.end()
            assertEquals(4, span.maxEvents)
        }
    }

    @Test fun `the installed cap applies without resolving the provider`() = runTest {
        var providerCalls = 0
        Kotrace.install(maxEventsPerSpan = 3) {
            providerCalls++
            emptyList()
        }

        val spans = collectTrace { span("op") {} }

        assertEquals(3, spans.single().maxEvents)
        assertEquals("opening a span is not a fan-out", 0, providerCalls)
    }

    @Test fun `with nothing configured a span takes the default cap`() = runTest {
        val spans = collectTrace { span("op") {} }

        assertEquals(Span.MAX_EVENTS, spans.single().maxEvents)
    }

    @Test fun `a cap below 1 is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TraceConfig(emptyList(), maxEventsPerSpan = 0) }
        assertThrows(IllegalArgumentException::class.java) { Kotrace.install(emptyList(), maxEventsPerSpan = 0) }
        assertEquals("a rejected install installs nothing", null, Kotrace.maxEventsPerSpan())
    }
}
