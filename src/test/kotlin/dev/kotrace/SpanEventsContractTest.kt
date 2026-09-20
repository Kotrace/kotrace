package dev.kotrace

import dev.kotrace.event.LogEvent
import dev.kotrace.event.SpanEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.ConcurrentModificationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Contract shared by the current copy-on-write list and any append-optimized event-buffer candidate.
 * Exercises the internal [Span.eventBuffer] directly — the public [Span.events] view is intentionally
 * read-only, so the mutation semantics under test are the buffer's, not consumer-facing API.
 */
class SpanEventsContractTest {

    @Test
    fun `iterator is a stable snapshot and does not support mutation`() {
        val span = freshSpan()
        span.eventBuffer += event(1)
        val iterator = span.eventBuffer.iterator()

        span.eventBuffer += event(2)

        assertEquals(listOf(1L), iterator.asSequence().map(SpanEvent::atNanos).toList())
        assertThrows(UnsupportedOperationException::class.java) { iterator.remove() }
        assertEquals(listOf(1L, 2L), span.eventBuffer.map(SpanEvent::atNanos))
    }

    @Test
    fun `the public events view is read-only and rejects a cast-back mutation`() {
        val span = freshSpan()
        span.eventBuffer += event(1)

        // A consumer sees the timeline but cannot append — not directly, and not by casting the view back
        // to a MutableList (the only writer is a verb, via emit → eventBuffer).
        @Suppress("UNCHECKED_CAST")
        val forced = span.events as MutableList<SpanEvent>
        assertThrows(UnsupportedOperationException::class.java) { forced += event(2) }

        assertEquals(listOf(1L), span.events.map(SpanEvent::atNanos))
    }

    @Test
    fun `subList remains a mutable view of the parent list`() {
        val span = freshSpan()
        span.eventBuffer += listOf(event(0), event(1), event(2))
        val tail = span.eventBuffer.subList(1, 3)

        tail.removeAt(0)
        tail += event(3)

        assertEquals(listOf(0L, 2L, 3L), span.eventBuffer.map(SpanEvent::atNanos))
        assertEquals(listOf(2L, 3L), tail.map(SpanEvent::atNanos))
    }

    @Test
    fun `subList detects a parent mutation made outside the view`() {
        val span = freshSpan()
        span.eventBuffer += listOf(event(0), event(1), event(2))
        val tail = span.eventBuffer.subList(1, 3)

        span.eventBuffer += event(3)

        assertThrows(ConcurrentModificationException::class.java) { tail.size }
    }

    @Test
    fun `nested subList mutation invalidates its ancestor view`() {
        val span = freshSpan()
        span.eventBuffer += listOf(event(0), event(1), event(2), event(3))
        val middle = span.eventBuffer.subList(1, 4)
        val nested = middle.subList(1, 3)

        nested.removeAt(0)
        nested += event(4)

        assertEquals(listOf(0L, 1L, 3L, 4L), span.eventBuffer.map(SpanEvent::atNanos))
        assertThrows(ConcurrentModificationException::class.java) { middle.size }
        assertEquals(listOf(3L, 4L), nested.map(SpanEvent::atNanos))
    }

    @Test
    fun `Java bulk list operations remain supported`() {
        val span = freshSpan()
        span.eventBuffer += listOf(event(2), event(0), event(1))

        span.eventBuffer.sortWith(compareBy(SpanEvent::atNanos))
        span.eventBuffer.replaceAll { current -> event(current.atNanos + 10) }
        span.eventBuffer.removeIf { it.atNanos == 11L }

        assertEquals(listOf(10L, 12L), span.eventBuffer.map(SpanEvent::atNanos))
    }

    @Test
    fun `concurrent append loses no event and preserves each writer order`() {
        val span = freshSpan()
        val workers = 4
        val perWorker = 1_000
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        try {
            val futures = List(workers) { worker ->
                executor.submit {
                    start.await()
                    repeat(perWorker) { index ->
                        span.eventBuffer += event(index.toLong(), worker)
                    }
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
