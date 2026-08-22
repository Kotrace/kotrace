package dev.kotrace

import dev.kotrace.event.exception
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The terminal-state safe-publication (D1 / ADR-006): [Span.status] / [Span.endNanos] read off one atomic
 * [State][Span] pair. Behavioural cover for the refactor — the race itself is not unit-testable; correctness
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
    @OptIn(NonSuspendTracingBridge::class)
    fun `end publishes status and endNanos together, throwable via events`() {
        val span = Span("t", "s", null, "op", startNanos = 0L)
        val boom = IllegalStateException("boom")

        span.end(SpanStatus.ERROR, boom)

        assertEquals("status marked ERROR", SpanStatus.ERROR, span.status)
        assertNotNull("endNanos stamped on completion", span.endNanos)
        assertEquals("throwable is the events source of truth, not the state pair", boom, span.exception)
    }
}
