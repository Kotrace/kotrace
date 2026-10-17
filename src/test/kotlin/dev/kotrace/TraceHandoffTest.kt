package dev.kotrace

import dev.kotrace.event.TraceRecord
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * [TraceHandoff] (ADR-023): capture a flow's trace context on its coroutine thread, then open spans and emit
 * against it from a plain thread where no mirror is live — the shape the OkHttp interceptor relies on.
 */
@OptIn(NonSuspendTracingBridge::class)
class TraceHandoffTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    private class CollectingLive : LiveAdapter {
        override val policy = object : TracePolicy {}
        val records = CopyOnWriteArrayList<TraceRecord>()
        override fun onLive(record: TraceRecord) {
            records += record
        }
    }

    /** Runs [block] on a fresh thread — no coroutine, so every thread mirror reads null there. */
    private fun <T> onPlainThread(block: () -> T): T {
        var result: Result<T>? = null
        thread { result = runCatching(block) }.join()
        return result!!.getOrThrow()
    }

    /** Opens [name] under the captured context, as the OkHttp interceptor does. */
    private fun TraceHandoff.openSpan(name: String): Span =
        startSpan(name, parent = parent, collector = collector, scopeId = scopeId)

    @Test fun `nothing to consume a span - no span, no collector, no live adapter - captures null`() {
        assertNull(TraceHandoff.captureOrNull())
    }

    @Test fun `a process-wide live adapter is enough to capture`() {
        Kotrace.install(listOf(CollectingLive()))
        assertNotNull(TraceHandoff.captureOrNull())
    }

    @Test fun `a span opened from another thread is a collected child of the captured parent`() = runTest {
        val collector = SpanCollector()
        var parent: Span? = null
        val handoff = withContext(collector) {
            span("caller") {
                parent = currentSpan()
                TraceHandoff.captureOrNull()!!
            }
        }

        val child = onPlainThread { handoff.openSpan("bridge").also { it.end() } }

        assertEquals(parent!!.traceId, child.traceId)
        assertEquals(parent!!.spanId, child.parentId)
        assertTrue("registered into the captured collector", collector.spans.any { it === child })
    }

    @Test fun `withCapturedConfig routes emits to the flow's override, then restores the thread`() = runTest {
        val global = CollectingLive()
        val flowOverride = CollectingLive()
        Kotrace.install(listOf(global))
        val handoff = withContext(SpanCollector() + TraceConfig(listOf(flowOverride))) { TraceHandoff.captureOrNull()!! }

        val seenAfter = onPlainThread {
            handoff.withCapturedConfig { handoff.openSpan("bridge").log { "on the flow's config" } }
            currentThreadConfig()
        }

        assertEquals(1, flowOverride.records.size)
        assertTrue("nothing leaks to the process-wide adapter", global.records.isEmpty())
        assertNull("the thread's mirror is restored after the block", seenAfter)
    }

    @Test fun `with no override captured, emits resolve the process-wide config as usual`() = runTest {
        val global = CollectingLive()
        Kotrace.install(listOf(global))
        val handoff = withContext(SpanCollector()) { TraceHandoff.captureOrNull()!! }

        onPlainThread { handoff.withCapturedConfig { handoff.openSpan("bridge").log { "x" } } }

        assertEquals("x", (global.records.single() as dev.kotrace.event.LogRecord).message)
    }
}
