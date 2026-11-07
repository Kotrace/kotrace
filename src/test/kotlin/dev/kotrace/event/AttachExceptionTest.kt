package dev.kotrace.event

import dev.kotrace.Kotrace
import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.SpanContext
import dev.kotrace.SpanStatus
import dev.kotrace.TraceConfig
import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.currentSpan
import dev.kotrace.span
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

@OptIn(dev.kotrace.UnredactedTraceRead::class)
class AttachExceptionTest {
    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    @Test fun `the current span owns the failure without changing the result status or events`() = runTest {
        val collector = SpanCollector()
        val failure = IllegalStateException("rollback")
        val result = Any()
        lateinit var owner: Span
        val actual = withContext(collector) {
            span("outer") {
                span("wrapper") {
                    owner = currentSpan()!!
                    owner.log { "breadcrumb" }
                    val before = owner.events.toList()
                    attachException(failure)
                    assertEquals(before, owner.events)
                    assertEquals(SpanStatus.OK, owner.status)
                    result
                }
            }
        }
        assertSame(result, actual)
        val attached = collector.attachedSnapshot().single()
        assertSame(owner, attached.owner)
        assertSame(failure, attached.throwable)
        assertEquals(SpanStatus.OK, owner.status)
    }

    @Test fun `no span or no collector is a no-op`() = runTest {
        val collector = SpanCollector()
        val owner = Span("trace", "span", null, "owner", startNanos = 1L)
        val failure = IllegalStateException()
        attachException(failure)
        withContext(collector) { attachException(failure) }
        withContext(SpanContext(owner)) { attachException(failure) }
        assertTrue(collector.attachedSnapshot().isEmpty())
        assertTrue(owner.events.isEmpty())
        assertTrue(owner.attributes.isEmpty())
    }

    @Test fun `a foreign span consumes neither uniqueness nor capacity of the nested collector`() = runTest {
        val outer = SpanCollector()
        val inner = SpanCollector()
        val failure = IllegalStateException()
        withContext(outer) {
            span("outer") {
                withContext(inner) {
                    repeat(101) { attachException(failure) }
                    span("member") {
                        attachException(failure)
                        repeat(99) { attachException(IllegalStateException()) }
                    }
                }
            }
        }
        assertTrue(outer.attachedSnapshot().isEmpty())
        assertEquals(100, inner.attachedSnapshot().size)
        assertSame(failure, inner.attachedSnapshot().first().throwable)
        assertTrue(inner.spans.single().attributes.isEmpty())
        assertTrue(outer.spans.single().attributes.isEmpty())
    }

    @Test fun `registration needs no configuration and completes inline in an already cancelled context`() {
        Kotrace.strictWhenNotInstalled()
        val collector = SpanCollector()
        val owner = Span("trace", "span", null, "owner", startNanos = 1L)
        collector.add(owner)
        val cancelled = Job().also { it.cancel() }
        val failure = IllegalStateException()
        val block: suspend () -> Unit = { attachException(failure) }
        val completion = object : Continuation<Unit> {
            override val context: CoroutineContext = cancelled + collector + SpanContext(owner)
            override fun resumeWith(result: Result<Unit>) = error("registration must complete inline")
        }
        assertEquals(Unit, block.startCoroutineUninterceptedOrReturn(completion))
        assertSame(failure, collector.attachedSnapshot().single().throwable)
        assertTrue(cancelled.isCancelled)
    }

    @Test fun `attached failures are never delivered live`() = runTest {
        val live = mutableListOf<TraceRecord>()
        val adapter = object : LiveAdapter {
            override val policy = object : TracePolicy {}
            override fun onLive(record: TraceRecord) { live += record }
        }
        withContext(TraceConfig(listOf(adapter))) {
            span("outer") { span("wrapper") { attachException(IllegalStateException()) } }
        }
        assertTrue(live.isEmpty())
    }
}
