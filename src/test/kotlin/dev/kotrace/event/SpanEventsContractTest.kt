// Tests inspect the raw span timeline (ADR-028); nothing here reaches a sink.
@file:OptIn(dev.kotrace.UnredactedTraceRead::class)

package dev.kotrace.event

import dev.kotrace.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The contract of the internal [Span.eventBuffer], a copy-on-write `EventTimeline` (ADR-029): reads are stable
 * snapshots, the only writer is an append, and appends from many threads lose nothing and keep each writer's order.
 */
class SpanEventsContractTest {

    @Test
    fun `an iterator is a stable snapshot`() {
        val span = freshSpan()
        span.appendEvent(event(1))
        val iterator = span.eventBuffer.iterator()

        span.appendEvent(event(2))

        assertEquals(listOf(1L), iterator.asSequence().map(SpanEvent::atNanos).toList())
        assertEquals(listOf(1L, 2L), span.eventBuffer.map(SpanEvent::atNanos))
    }

    @Test
    fun `a snapshot never sees a later append, nor an eviction`() {
        val span = Span("trace", "span", null, "buffer-test", startNanos = 0, maxEvents = 2)
        span.appendEvent(event(1))
        span.appendEvent(event(2))
        val snapshot = span.eventBuffer.snapshot()

        span.appendEvent(event(3)) // past the cap: evicts event 1

        assertEquals(listOf(1L, 2L), snapshot.map(SpanEvent::atNanos))
        assertEquals(listOf(2L, 3L), span.eventBuffer.map(SpanEvent::atNanos))
    }

    @Test
    fun `the buffer and the public events view are read-only`() {
        val span = freshSpan()
        span.appendEvent(event(1))

        // A consumer sees the timeline but cannot append (the only writer is a verb, via emit → appendEvent): Kotlin
        // refuses to treat the buffer as a MutableList, and the view cast back to one throws on a write. Held as Any,
        // so the check runs on the buffer's class at run time rather than being decided by the compiler.
        val buffer: Any = span.eventBuffer
        assertFalse(buffer is MutableList<*>)
        @Suppress("UNCHECKED_CAST")
        val forced = span.events as MutableList<SpanEvent>
        assertThrows(UnsupportedOperationException::class.java) { forced += event(2) }

        assertEquals(listOf(1L), span.events.map(SpanEvent::atNanos))
    }

    @Test
    fun `an empty timeline shares one empty snapshot`() {
        assertTrue(freshSpan().eventBuffer.snapshot().isEmpty())
    }

    @Test
    fun `concurrent append loses no event and preserves each writer order`() {
        val span = freshSpan()
        val workers = 4
        val perWorker = 200
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        try {
            val futures = List(workers) { worker ->
                executor.submit {
                    start.await()
                    repeat(perWorker) { index -> span.appendEvent(event(index.toLong(), worker)) }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(workers * perWorker, span.eventBuffer.size)
        val perWriter = span.eventBuffer.groupBy { (it as LogEvent).attributes.getValue("writer") }
        repeat(workers) { worker ->
            assertEquals(
                (0 until perWorker).map(Int::toLong),
                perWriter.getValue(worker.toString()).map(SpanEvent::atNanos),
            )
        }
    }

    private fun freshSpan() = Span(
        traceId = "trace",
        spanId = "span",
        parentId = null,
        name = "buffer-test",
        startNanos = 0,
    )

    private fun event(index: Long, writer: Int = 0) = LogEvent(
        attributes = mapOf("writer" to writer.toString()),
        messageProvider = { "event-$index" },
        atNanos = index,
    )
}
