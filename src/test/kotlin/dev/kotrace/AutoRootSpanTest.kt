package dev.kotrace

import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.currentSpan
import dev.kotrace.event.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Auto-root (ADR-013): a `span` opened with neither a current span nor a [SpanCollector] owns the trace and
 * reports at the outcome; every other context state is instrumentation-only. Also covers strict-mode
 * outcome precedence (ADR-013/ADR-011).
 */
class AutoRootSpanTest {

    private class CollectingReport(override val policy: TracePolicy = object : TracePolicy {}) : ReportAdapter {
        val statuses = mutableListOf<TraceStatus>()
        val records = mutableListOf<List<TraceRecord>>()
        val reports get() = statuses.size
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            statuses += status
            this.records += records.toList()
        }
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    // Coroutine stacktrace recovery copies a throwable across each `withContext` boundary (Trace.kt:37), so
    // "rethrown unchanged" (ADR-013) means *not swallowed or replaced by kotrace* — not object identity.
    // Assert through the cause chain instead of `===` on the top-level object.
    private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    // --- The four context states ---

    @Test fun `null span + null collector auto-roots and reports OK`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val out = span("op") { currentSpan()?.log { "hi" }; 42 }
        assertEquals(42, out)
        assertEquals("one report attempt", 1, report.reports)
        assertEquals(TraceStatus.OK, report.statuses.single())
    }

    @Test fun `auto-root ends the root before it reports`() = runTest {
        // The ordering guarantee (ADR-013): reportTrace runs after markEnd, so the walk never sees an
        // unfinished root. Capture the root inside the block, then read its endNanos from within onReport.
        val rootRef = AtomicReference<Span?>()
        var endedAtReport: Boolean? = null
        val report = object : ReportAdapter {
            override val policy: TracePolicy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                endedAtReport = rootRef.get()?.endNanos != null
            }
        }
        Kotrace.install(listOf(report))
        span("op") { rootRef.set(currentSpan()) }
        assertEquals("root was markEnd'd before onReport saw the trace", true, endedAtReport)
    }

    @Test fun `collector present but no span - manual root - does NOT auto-report`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val collector = SpanCollector()
        withContext(collector) {
            span("root") { currentSpan()?.log { "x" } }
        }
        assertEquals("auto-root does not fire when a collector is already present", 0, report.reports)
        // the manual owner is what reports:
        withContext(collector) { collector.reportTrace(TraceStatus.OK) }
        assertEquals(1, report.reports)
    }

    @Test fun `span present + collector present - child - no report`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        var childRan = false
        span("root") {
            span("child") { childRan = true }
        }
        assertTrue(childRan)
        assertEquals("only the outermost auto-root reports", 1, report.reports)
    }

    @Test fun `span present but no collector - identified-but-uncollected - no report`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val root = Span("t", "s", null, "root", 0L)
        withContext(SpanContext(root)) {
            span("child") { currentSpan()?.log { "x" } } // SpanContext present, no collector → child for identity only
        }
        assertEquals("no collector ⇒ no phantom report", 0, report.reports)
    }

    // --- Outcomes ---

    @Test fun `escaping failure reports ERROR and rethrows the same throwable`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val boom = IllegalStateException("boom")
        val thrown = runCatching { span("op") { throw boom } }.exceptionOrNull()
        assertTrue("the application throwable propagated, not swallowed/replaced", thrown!!.chain().any { it === boom })
        assertEquals(TraceStatus.ERROR, report.statuses.single())
        val hasCrash = report.records.single().any { it is ExceptionRecord && it.throwable.chain().any { c -> c === boom } }
        assertTrue("birthplace exception is in the report", hasCrash)
    }

    @Test fun `cancellation reports CANCELLED`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val thrown = runCatching { span("op") { throw CancellationException("cancelled") } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(TraceStatus.CANCELLED, report.statuses.single())
    }

    @Test fun `a recovered child failure leaves the root OK`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        span("root") {
            runCatching { span("child") { throw IllegalStateException("child") } }
        }
        assertEquals("root recovered ⇒ trace OK", TraceStatus.OK, report.statuses.single())
    }

    @Test fun `failure-as-data returned by the block reports OK`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val out = span("op") { Result.failure<Int>(IllegalStateException("domain")) }
        assertTrue(out.isFailure)
        assertEquals("nothing escaped ⇒ OK; failure-as-data uses the manual escape hatch", TraceStatus.OK, report.statuses.single())
    }

    // --- Config, scope, sequential/parallel ---

    @Test fun `an ambient per-flow config override is used at report`() = runTest {
        val global = CollectingReport()
        val override = CollectingReport()
        Kotrace.install(listOf(global))
        withContext(TraceConfig(listOf(override))) {
            span("op") { }
        }
        assertEquals("override sees the report", 1, override.reports)
        assertEquals("global does not", 0, global.reports)
    }

    @Test fun `withScope stamps scope_id on the auto-root report records`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        withScope("sess-1") {
            span("op") { currentSpan()?.log { "hi" } }
        }
        val scoped = report.records.single().filterIsInstance<LogRecord>().single()
        assertEquals("sess-1", scoped.scopeId)
    }

    @Test fun `N sequential top-level spans are N traces`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        repeat(3) { span("op-$it") { } }
        assertEquals(3, report.reports)
    }

    @Test fun `parallel structured children all reach the one trace`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        span("root") {
            coroutineScope {
                repeat(10) { i -> launch { span("child-$i") { currentSpan()?.log { "c$i" } } } }
            }
        }
        val logs = report.records.single().filterIsInstance<LogRecord>()
        assertEquals("all 10 structured children joined before the root reported", 10, logs.size)
    }

    // --- Strict-mode precedence (ADR-013 / ADR-011) ---

    @Test fun `strict-mode preserves the application throwable, does not replace it with the strict failure`() = runTest {
        Kotrace.strictWhenUninstalled() // armed, nothing installed → resolvedThreadConfig throws while recording + reporting
        val app = IllegalStateException("app")
        val thrown = runCatching { span("op") { throw app } }.exceptionOrNull()
        // The guarantee: the strict failure never replaces the application throwable. Without the guard, the
        // strict IllegalStateException thrown from addException/reportTrace would propagate instead, and its
        // chain would NOT contain `app`. (kotrace attaches the strict failure as suppressed; observing that
        // is coroutine-stacktrace-recovery-dependent — off in release, on under -ea — so it is not asserted.)
        assertTrue("application throwable wins the propagation", thrown!!.chain().any { it === app })
        assertTrue("propagated failure is app-derived, not the strict failure", thrown.chain().last() === app)
    }

    @Test fun `strict-mode failure on a normally-completing block propagates as itself`() = runTest {
        Kotrace.strictWhenUninstalled()
        val thrown = runCatching { span("op") { 42 } }.exceptionOrNull()
        assertTrue(
            "strict failure propagates when nothing escaped",
            thrown!!.chain().any { it is IllegalStateException && it.message?.contains("strict-uninstalled") == true },
        )
    }

    // --- startSpan is not auto-rooted ---

    @OptIn(NonSuspendTracingBridge::class)
    @Test fun `startSpan does not auto-root`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val s = startSpan("bridge")
        s.end()
        assertEquals("the non-suspend bridge never auto-reports", 0, report.reports)
        assertNull("rooted, uncollected", s.parentId)
    }
}
