package dev.kotrace

import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.emitLog
import dev.kotrace.currentSpan
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-adapter fault isolation across every fan-out phase (ADR-014): a throwing adapter, policy, or shared
 * record construction is contained and routed to an opt-in [AdapterFaultHook]; JVM-fatal errors are
 * rethrown; the hook is per-thread reentrancy-guarded and its own non-fatal throw is swallowed.
 */
class AdapterFaultIsolationTest {

    private class ThrowingLive(override val policy: TracePolicy = object : TracePolicy {}) : LiveAdapter {
        override fun onLive(record: TraceRecord): Unit = throw RuntimeException("live boom")
    }

    private class CollectingLive(override val policy: TracePolicy = object : TracePolicy {}) : LiveAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onLive(record: TraceRecord) { records += record }
    }

    private class ThrowingReport(override val policy: TracePolicy = object : TracePolicy {}) : ReportAdapter {
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>): Unit = throw RuntimeException("report boom")
    }

    private class CollectingReport(override val policy: TracePolicy = object : TracePolicy {}) : ReportAdapter {
        var reports = 0
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) { reports++; records.toList() }
    }

    private class RecordingHook : AdapterFaultHook {
        data class Fault(val phase: FaultPhase, val adapter: TraceAdapter?, val cause: Throwable)
        val faults = mutableListOf<Fault>()
        override fun onAdapterFault(phase: FaultPhase, adapter: TraceAdapter?, cause: Throwable) {
            faults += Fault(phase, adapter, cause)
        }
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    @Test fun `a throwing live adapter is contained, sibling delivered, hook fires LIVE`() = runTest {
        val throwing = ThrowingLive()
        val collecting = CollectingLive()
        val hook = RecordingHook()
        withContext(TraceConfig(listOf(throwing, collecting), hook)) {
            span("op") { currentSpan()?.log { "x" } }
        }
        assertEquals("sibling still delivered", 1, collecting.records.size)
        assertEquals(1, hook.faults.size)
        assertEquals(FaultPhase.LIVE, hook.faults[0].phase)
        assertSame(throwing, hook.faults[0].adapter)
    }

    @Test fun `a throwing report adapter is contained, sibling reports, hook fires REPORT`() = runTest {
        val throwing = ThrowingReport()
        val collecting = CollectingReport()
        val hook = RecordingHook()
        withContext(TraceConfig(listOf(throwing, collecting), hook)) {
            span("op") { }
        }
        assertEquals("sibling report still ran", 1, collecting.reports)
        assertTrue(hook.faults.any { it.phase == FaultPhase.REPORT && it.adapter === throwing })
    }

    @Test fun `a throwing span-less live adapter is contained, hook fires SPANLESS_LIVE`() = runTest {
        val throwing = ThrowingLive()
        val collecting = CollectingLive()
        val hook = RecordingHook()
        withContext(TraceConfig(listOf(throwing, collecting), hook)) {
            emitLog { "x" }
        }
        assertEquals(1, collecting.records.size)
        assertTrue(hook.faults.any { it.phase == FaultPhase.SPANLESS_LIVE && it.adapter === throwing })
    }

    @Test fun `a throwing policy is contained as a reject, sibling delivered`() = runTest {
        val boomPolicy = object : TracePolicy {
            override fun acceptsEvent(event: SpanEvent): Boolean = throw RuntimeException("policy boom")
        }
        val rejecting = CollectingLive(boomPolicy)
        val collecting = CollectingLive()
        val hook = RecordingHook()
        withContext(TraceConfig(listOf(rejecting, collecting), hook)) {
            span("op") { currentSpan()?.log { "x" } }
        }
        assertEquals("policy fault ⇒ that adapter rejects", 0, rejecting.records.size)
        assertEquals("sibling still delivered", 1, collecting.records.size)
        assertTrue(hook.faults.any { it.phase == FaultPhase.LIVE && it.adapter === rejecting })
    }

    @Test fun `a shared record-construction fault aborts the event for all adapters, hook adapter is null`() = runTest {
        val a = CollectingLive()
        val b = CollectingLive()
        val hook = RecordingHook()
        withContext(TraceConfig(listOf(a, b), hook)) {
            span("op") { currentSpan()?.log { throw RuntimeException("message boom") } }
        }
        assertEquals("no delivery to any accepting adapter", 0, a.records.size + b.records.size)
        assertTrue("shared fault attributed to null adapter", hook.faults.any { it.adapter == null })
    }

    @Test fun `a JVM-fatal error from an adapter is rethrown, not routed to the hook`() = runTest {
        val fatal = object : LiveAdapter {
            override val policy: TracePolicy = object : TracePolicy {}
            override fun onLive(record: TraceRecord): Unit = throw OutOfMemoryError("oom")
        }
        val hook = RecordingHook()
        val thrown = runCatching {
            withContext(TraceConfig(listOf(fatal), hook)) { span("op") { currentSpan()?.log { "x" } } }
        }.exceptionOrNull()
        assertTrue("fatal propagates", thrown is OutOfMemoryError)
        assertEquals("fatal never routed to the hook", 0, hook.faults.size)
    }

    @Test fun `a hook that itself throws is swallowed`() = runTest {
        val throwing = ThrowingLive()
        val collecting = CollectingLive()
        val badHook = AdapterFaultHook { _, _, _ -> throw RuntimeException("hook boom") }
        withContext(TraceConfig(listOf(throwing, collecting), badHook)) {
            span("op") { currentSpan()?.log { "x" } }
        }
        assertEquals("operation unaffected by a throwing hook", 1, collecting.records.size)
    }

    @Test fun `no hook installed - fault swallowed, sibling delivered`() = runTest {
        val throwing = ThrowingLive()
        val collecting = CollectingLive()
        withContext(TraceConfig(listOf(throwing, collecting))) {
            span("op") { currentSpan()?.log { "x" } }
        }
        assertEquals(1, collecting.records.size)
    }

    @Test fun `a report policy fault during in-call sequence consumption is contained`() = runTest {
        val boomPolicy = object : TracePolicy {
            override fun acceptsEvent(event: SpanEvent): Boolean = throw RuntimeException("report policy boom")
        }
        val consuming = object : ReportAdapter {
            override val policy: TracePolicy = boomPolicy
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) { records.toList() }
        }
        val sibling = CollectingReport()
        val hook = RecordingHook()
        withContext(TraceConfig(listOf(consuming, sibling), hook)) {
            span("op") { currentSpan()?.log { "x" } } // a reportable log entry for the view to filter
        }
        assertEquals("sibling report still ran", 1, sibling.reports)
        assertTrue(hook.faults.any { it.phase == FaultPhase.REPORT && it.adapter === consuming })
    }

    @Test fun `a report message fault during in-call sequence consumption is contained`() = runTest {
        val consuming = object : ReportAdapter {
            override val policy: TracePolicy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) { records.toList() }
        }
        val hook = RecordingHook()
        // No live adapter ⇒ the message resolves only at report, inside onReport (guarded), never at emit.
        val thrown = runCatching {
            withContext(TraceConfig(listOf(consuming), hook)) {
                span("op") { currentSpan()?.log { throw RuntimeException("report msg boom") } }
            }
        }.exceptionOrNull()
        assertNull("a report-time message fault never reaches the traced operation", thrown)
        assertTrue(hook.faults.any { it.phase == FaultPhase.REPORT })
    }

    @Test fun `a fault hook installed on the process-wide config observes faults`() = runTest {
        val throwing = ThrowingLive()
        val hook = RecordingHook()
        Kotrace.install(listOf(throwing), hook) // global install path carries the hook (ADR-014)
        span("op") { currentSpan()?.log { "x" } }
        assertTrue(hook.faults.any { it.phase == FaultPhase.LIVE && it.adapter === throwing })
    }

    @Test fun `a JVM-fatal error thrown by the hook itself propagates`() = runTest {
        val throwing = ThrowingLive()
        val fatalHook = AdapterFaultHook { _, _, _ -> throw OutOfMemoryError("hook oom") }
        val thrown = runCatching {
            withContext(TraceConfig(listOf(throwing), fatalHook)) { span("op") { currentSpan()?.log { "x" } } }
        }.exceptionOrNull()
        assertTrue("a fatal from the hook is rethrown, not swallowed", thrown is OutOfMemoryError)
    }

    @Test fun `a fault raised from within a running hook does not re-enter it`() = runTest {
        val throwing = ThrowingLive()
        var count = 0
        val reentrantHook = AdapterFaultHook { _, _, _ ->
            count++
            emitLog { "nested" } // hits the same throwing adapter → nested fault must be dropped, not re-notified
        }
        withContext(TraceConfig(listOf(throwing), reentrantHook)) {
            span("op") { currentSpan()?.log { "x" } }
        }
        assertEquals("hook not re-entered by a nested fault", 1, count)
    }

    @Test fun `the reentrancy guard is per-thread, so concurrent faults on different threads are all observed`() {
        val throwing = ThrowingLive()
        val observed = AtomicInteger()
        val bothInside = CountDownLatch(2)
        val release = CountDownLatch(1)
        // Each invocation blocks until both are inside at once: a process-global guard could not admit the
        // second thread while the first holds the flag, so bothInside would never reach zero.
        val hook = AdapterFaultHook { _, _, _ ->
            observed.incrementAndGet()
            bothInside.countDown()
            release.await()
        }
        Kotrace.install(listOf(throwing), hook) // span-less emits on raw threads resolve the global config
        val threads = (1..2).map { thread { emitLog { "boom" } } }
        val bothEntered = bothInside.await(5, TimeUnit.SECONDS)
        release.countDown()
        threads.forEach { it.join() }
        assertTrue("both threads entered the hook concurrently (guard is per-thread, not global)", bothEntered)
        assertEquals(2, observed.get())
    }
}
