// Tests inspect the raw span timeline (ADR-028); nothing here reaches a sink.
@file:OptIn(dev.kotrace.UnredactedTraceRead::class)

package dev.kotrace

import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.end
import dev.kotrace.event.exception
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal-state safe-publication (D1 / ADR-006): [Span.status] / [Span.endNanos] read off one atomic
 * [State][Span] pair. Behavioral cover for the refactor; the race itself is not unit-testable; correctness
 * is by construction (single atomic publish = happens-before + consistent pair).
 */
class SpanStateTest {

    @Test
    fun `a fresh span reads OK and open`() {
        val span = Span("t", "s", null, "op", startNanos = 0L)
        assertEquals("status defaults OK", SpanStatus.OK, span.status)
        assertNull("endNanos null while open", span.endNanos)
    }

    @Test
    fun `markStatus and markEnd leave an ended span unchanged`() {
        val span = Span("t", "s", null, "operation", startNanos = 0L)
        span.markStatus(SpanStatus.ERROR)
        span.markEnd(10L)

        span.markStatus(SpanStatus.OK)
        span.markEnd(20L)

        assertEquals(SpanStatus.ERROR, span.status)
        assertEquals(10L, span.endNanos)
    }

    @Test
    @OptIn(NonSuspendTracingBridge::class)
    fun `markStatus and markEnd leave a bridge completion unchanged`() {
        val span = Span("t", "s", null, "operation", startNanos = 0L)
        span.end(SpanStatus.CANCELLED)
        val firstEnd = span.endNanos

        span.markStatus(SpanStatus.ERROR)
        span.markEnd(20L)

        assertEquals(SpanStatus.CANCELLED, span.status)
        assertEquals(firstEnd, span.endNanos)
    }

    @Test
    @OptIn(NonSuspendTracingBridge::class)
    fun `a bridge end inside a span block wins over its later failure and finally`() = runTest {
        val collector = SpanCollector()
        val failure = IllegalStateException("failure")
        var firstEnd: Long? = null

        val thrown = runCatching {
            withContext(collector) {
                span("operation") {
                    val current = currentSpan()!!
                    current.end()
                    firstEnd = current.endNanos
                    throw failure
                }
            }
        }.exceptionOrNull()

        val ended = collector.spans.single()
        assertTrue(generateSequence(thrown) { it.cause }.any { it === failure })
        assertEquals(SpanStatus.OK, ended.status)
        assertEquals(firstEnd, ended.endNanos)
        assertTrue("the failure is still recorded", generateSequence(ended.exception) { it.cause }.any { it === failure })
    }

    @Test
    @OptIn(NonSuspendTracingBridge::class)
    fun `end publishes status and endNanos together, throwable via events`() {
        val span = Span("t", "s", null, "op", startNanos = 0L)
        val boom = IllegalStateException("boom")

        span.end(SpanStatus.ERROR, boom)

        assertEquals("status marked ERROR", SpanStatus.ERROR, span.status)
        assertNotNull("endNanos stamped on completion", span.endNanos)
        assertEquals("throwable is the events source of truth, not the state pair", boom, span.exception)
    }

    @Test
    @OptIn(NonSuspendTracingBridge::class)
    fun `end is exactly once - a second end can neither flip ERROR to OK nor re-record`() {
        val span = Span("t", "s", null, "op", startNanos = 0L)
        val boom = IllegalStateException("boom")

        span.end(SpanStatus.ERROR, boom)
        val firstEnd = span.endNanos
        span.end(SpanStatus.OK) // a retry reusing the span, a racing callback: ignored (ADR-023)
        span.end(SpanStatus.ERROR, IllegalStateException("second"))

        assertEquals("the first terminal transition wins", SpanStatus.ERROR, span.status)
        assertEquals("endNanos is not re-stamped", firstEnd, span.endNanos)
        assertEquals("only the winning end records its throwable", 1, span.events.size)
        assertEquals(boom, span.exception)
    }
}
