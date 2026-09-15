package dev.kotrace

import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The return-aware `span` overload (ADR-016): auto-root maps the **returned value** to the trace outcome via
 * `returnedOutcome`, while core keeps the escaping (throw/cancel) outcomes. Covers the value/attached mapping,
 * the mapper's "runs iff auto-rooting, exactly once" contract across the four context states, mapper-fault
 * isolation (non-fatal → OK fallback + hook; cancellation contained; JVM-fatal rethrown), strict precedence,
 * and overload resolution.
 */
class AutoRootReturnedOutcomeTest {

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

    private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    // A domain failure carried as a *value* — the failure-as-value model this overload exists for.
    private sealed interface Out<out T> {
        data class Ok<T>(val value: T) : Out<T>
        data class Fail(val cause: Throwable, val rollback: List<Throwable> = emptyList()) : Out<Nothing>
    }

    private fun <T> outcomeOf(result: Out<T>): TraceOutcome = when (result) {
        is Out.Ok -> TraceOutcome(TraceStatus.OK)
        is Out.Fail -> TraceOutcome(TraceStatus.ERROR, attached = result.rollback)
    }

    // --- Returned-value mapping ---

    @Test fun `a returned failure value maps to ERROR and rides its orphans as attached`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val cause = IllegalStateException("charge declined")
        val rollback = IllegalStateException("refund failed")
        val out = span("checkout", returnedOutcome = ::outcomeOf) {
            val r: Out<Int> = Out.Fail(cause, listOf(rollback))
            if (r is Out.Fail) currentSpan()?.addException(r.cause) // birthplace throwable stays on the tree
            r
        }
        assertTrue(out is Out.Fail)
        assertEquals(TraceStatus.ERROR, report.statuses.single())
        val crashes = report.records.single().filterIsInstance<ExceptionRecord>()
        // Exactly two, no duplication: the birthplace cause (walked from the root's own event) then the
        // attached orphan (appended after the walk, past the dedup) — order is deterministic.
        assertEquals("exactly the cause + the one orphan, in order", listOf(cause, rollback), crashes.map { it.throwable })
        // Both keyed to the root: same trace_id, operation = root span name.
        val rootTraceId = report.records.single().first().traceId
        assertTrue("both records keyed to the root operation", crashes.all { it.operation == "checkout" })
        assertTrue("both share the root trace_id", crashes.all { it.traceId == rootTraceId })
    }

    @Test fun `a returned success maps to OK with no attached`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val out = span("checkout", returnedOutcome = ::outcomeOf) { Out.Ok(42) }
        assertEquals(Out.Ok(42), out)
        assertEquals(TraceStatus.OK, report.statuses.single())
        assertTrue("no exception records for a clean success", report.records.single().none { it is ExceptionRecord })
    }

    @Test fun `the mapper may map a returned value to CANCELLED`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        span("op", returnedOutcome = { TraceOutcome(TraceStatus.CANCELLED) }) { 1 }
        assertEquals("core does not second-guess the returned-value verdict", TraceStatus.CANCELLED, report.statuses.single())
    }

    @Test fun `the mapper runs exactly once on a normal auto-root return`() = runTest {
        Kotrace.install(listOf(CollectingReport()))
        val calls = AtomicInteger(0)
        span("op", returnedOutcome = { calls.incrementAndGet(); TraceOutcome(TraceStatus.OK) }) { 1 }
        assertEquals(1, calls.get())
    }

    @Test fun `the root is already ended when the mapper and the report run`() = runTest {
        // ADR-016 ordering: markEnd (span's finally) → mapper → report. Capture the root in the block and
        // confirm it is ended by the time the mapper is consulted and by the time onReport sees the trace.
        val root = AtomicReference<Span?>()
        var endedAtMapper: Boolean? = null
        var endedAtReport: Boolean? = null
        val report = object : ReportAdapter {
            override val policy: TracePolicy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                endedAtReport = root.get()?.endNanos != null
            }
        }
        Kotrace.install(listOf(report))
        span("op", returnedOutcome = { endedAtMapper = root.get()?.endNanos != null; TraceOutcome(TraceStatus.OK) }) {
            root.set(currentSpan())
        }
        assertEquals("root ended before the mapper ran", true, endedAtMapper)
        assertEquals("root ended before onReport saw the trace", true, endedAtReport)
    }

    @Test fun `a successful mapper does not resolve config before the report`() = runTest {
        // Regression guard for the eager-resolution fix: the fault hook (config) must be resolved LAZILY, so a
        // successful mapper never triggers the lazy config provider ahead of the report. Install a lazy
        // provider counting its resolutions; assert it is still unresolved when the mapper runs, and resolved
        // by report time. (Under the old eager `quietFaultHook()` this would already read 1 at mapper time.)
        val report = CollectingReport()
        val providerResolutions = AtomicInteger(0)
        Kotrace.install { providerResolutions.incrementAndGet(); listOf(report) }
        var resolvedAtMapper = -1
        span("op", returnedOutcome = { resolvedAtMapper = providerResolutions.get(); TraceOutcome(TraceStatus.OK) }) { }
        assertEquals("lazy config not resolved before/at the mapper on success", 0, resolvedAtMapper)
        assertTrue("config resolved by report time", providerResolutions.get() >= 1)
        assertEquals(TraceStatus.OK, report.statuses.single())
    }

    @Test fun `a fatal config provider still lets the mapper run first, then propagates`() = runTest {
        // The mapper must run before config is resolved: install a provider that throws a JVM-fatal on
        // resolution (which only happens at report, after the mapper). The mapper runs (count 1) and only then
        // does the fatal from report-time resolution propagate. Under the old eager order the mapper would
        // never run (count 0) because resolution preceded it.
        val calls = AtomicInteger(0)
        val fatal = OutOfMemoryError("provider fatal")
        Kotrace.install { throw fatal }
        val thrown = runCatching {
            span("op", returnedOutcome = { calls.incrementAndGet(); TraceOutcome(TraceStatus.OK) }) { }
        }.exceptionOrNull()
        assertEquals("mapper ran before config resolution's fatal propagated", 1, calls.get())
        assertTrue("the fatal propagates from report-time resolution", thrown!!.chain().any { it === fatal })
    }

    // --- Escaping outcomes stay core's; the mapper is not consulted ---

    @Test fun `an escaping throwable reports ERROR, does not call the mapper, and rethrows unchanged`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val calls = AtomicInteger(0)
        val boom = IllegalStateException("boom")
        val thrown = runCatching {
            span<Int>("op", returnedOutcome = { calls.incrementAndGet(); TraceOutcome(TraceStatus.OK) }) { throw boom }
        }.exceptionOrNull()
        assertTrue("application throwable propagated", thrown!!.chain().any { it === boom })
        assertEquals("mapper not consulted for an escaping throwable", 0, calls.get())
        assertEquals(TraceStatus.ERROR, report.statuses.single())
    }

    @Test fun `an escaping CancellationException reports CANCELLED and does not call the mapper`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val calls = AtomicInteger(0)
        val thrown = runCatching {
            span<Int>("op", returnedOutcome = { calls.incrementAndGet(); TraceOutcome(TraceStatus.OK) }) {
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(0, calls.get())
        assertEquals(TraceStatus.CANCELLED, report.statuses.single())
    }

    // --- The mapper is inert in the three non-auto-root context states ---

    @Test fun `the mapper is not invoked on the child path`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val calls = AtomicInteger(0)
        span("root") {
            span("child", returnedOutcome = { calls.incrementAndGet(); TraceOutcome(TraceStatus.ERROR) }) { 1 }
        }
        assertEquals("a nested return-aware span is a child; mapper inert", 0, calls.get())
        // Only the enclosing auto-root reports, and its verdict is completion-derived (OK) — the child's
        // ERROR mapper had no say.
        assertEquals("exactly one report, from the enclosing root", 1, report.reports)
        assertEquals(TraceStatus.OK, report.statuses.single())
    }

    @Test fun `the mapper is not invoked on the manual-collector root path`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val calls = AtomicInteger(0)
        val collector = SpanCollector()
        withContext(collector) {
            span("root", returnedOutcome = { calls.incrementAndGet(); TraceOutcome(TraceStatus.ERROR) }) { 1 }
        }
        assertEquals("collector present ⇒ manual owner reports; mapper inert", 0, calls.get())
        assertEquals("and no auto-report fired", 0, report.reports)
    }

    @Test fun `the mapper is not invoked on the identified-but-uncollected path`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val calls = AtomicInteger(0)
        val root = Span("t", "s", null, "root", 0L)
        withContext(SpanContext(root)) {
            span("child", returnedOutcome = { calls.incrementAndGet(); TraceOutcome(TraceStatus.ERROR) }) { 1 }
        }
        assertEquals(0, calls.get())
        assertEquals(0, report.reports)
    }

    // --- Mapper-fault isolation ---

    private class CapturingHook : AdapterFaultHook {
        val phases = mutableListOf<FaultPhase>()
        val adapters = mutableListOf<TraceAdapter?>()
        val causes = mutableListOf<Throwable>()
        override fun onAdapterFault(phase: FaultPhase, adapter: TraceAdapter?, cause: Throwable) {
            phases += phase; adapters += adapter; causes += cause
        }
    }

    @Test fun `a non-fatal throwing mapper is contained - OK fallback, value unchanged, hook notified`() = runTest {
        val report = CollectingReport()
        val hook = CapturingHook()
        Kotrace.install(listOf(report), hook)
        val boom = RuntimeException("mapper blew up")
        val out = span("op", returnedOutcome = { throw boom }) { 99 }
        assertEquals("returned value is unchanged by a mapper fault", 99, out)
        assertEquals("contained ⇒ fallback OK, never an invented failure", TraceStatus.OK, report.statuses.single())
        assertEquals("routed with the new phase", listOf(FaultPhase.RETURNED_OUTCOME), hook.phases)
        assertNull("mapper fault has no single adapter owner", hook.adapters.single())
        assertSame(boom, hook.causes.single())
    }

    @Test fun `a mapper-thrown CancellationException is contained, not trace cancellation`() = runTest {
        val report = CollectingReport()
        val hook = CapturingHook()
        Kotrace.install(listOf(report), hook)
        val out = span("op", returnedOutcome = { throw CancellationException("spurious") }) { 7 }
        assertEquals("block already returned; nothing to cancel", 7, out)
        assertEquals("contained → OK, not CANCELLED", TraceStatus.OK, report.statuses.single())
        assertEquals(listOf(FaultPhase.RETURNED_OUTCOME), hook.phases)
    }

    @Test fun `a JVM-fatal mapper fault is rethrown and no report is attempted`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val fatal = OutOfMemoryError("fatal")
        val thrown = runCatching { span("op", returnedOutcome = { throw fatal }) { 1 } }.exceptionOrNull()
        // Coroutine stacktrace recovery may copy the throwable across the withContext boundary, so assert
        // through the cause chain (as the escaping-throwable tests do) rather than on object identity.
        assertTrue("a JVM-fatal fault wins over the traced outcome", thrown!!.chain().any { it === fatal })
        assertEquals("no report attempted on a fatal mapper fault", 0, report.reports)
    }

    // --- Strict-mode precedence (ADR-013 / ADR-011) ---

    @Test fun `strict-mode failure on a normal return propagates as itself`() = runTest {
        Kotrace.strictWhenUninstalled() // armed, nothing installed → reportTrace's config resolution throws
        val thrown = runCatching {
            span("op", returnedOutcome = { TraceOutcome(TraceStatus.OK) }) { 42 }
        }.exceptionOrNull()
        assertTrue(
            "with nothing escaping, the strict failure propagates",
            thrown!!.chain().any { it is IllegalStateException && it.message?.contains("strict-uninstalled") == true },
        )
    }

    @Test fun `strict-mode preserves an escaping application throwable over the strict failure`() = runTest {
        Kotrace.strictWhenUninstalled()
        val app = IllegalStateException("app")
        val thrown = runCatching {
            span<Int>("op", returnedOutcome = { TraceOutcome(TraceStatus.OK) }) { throw app }
        }.exceptionOrNull()
        assertTrue("application throwable wins the propagation", thrown!!.chain().any { it === app })
    }

    @Test fun `strict-mode tripped by an in-block addException escapes before the mapper is consulted`() = runTest {
        // ADR-016 §strict: the block's own birthplace recording (addException → resolvedThreadConfig) can trip
        // strict *inside the block*, before the mapper sees the value. That throw escapes as an application
        // throwable, so the mapper is never consulted (the block did not return normally).
        Kotrace.strictWhenUninstalled()
        val calls = AtomicInteger(0)
        val thrown = runCatching {
            span("op", returnedOutcome = { calls.incrementAndGet(); TraceOutcome(TraceStatus.OK) }) {
                currentSpan()?.addException(IllegalStateException("domain cause")) // resolvedThreadConfig throws under strict
                Unit
            }
        }.exceptionOrNull()
        assertEquals("block threw before returning ⇒ mapper never runs", 0, calls.get())
        assertTrue(
            "the strict failure escapes",
            thrown!!.chain().any { it is IllegalStateException && it.message?.contains("strict-uninstalled") == true },
        )
    }

    // --- Overload resolution & multiplicity ---

    @Test fun `existing call shapes still bind the zero-config overload`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        // The actual compat hazard shape: block passed as the FOURTH positional argument, in parentheses
        // (not a trailing lambda). This is what would break had returnedOutcome been inserted before block on
        // one overload; with the original overload retained it still binds unambiguously and reports OK.
        span("positional", emptyMap(), emptyList(), { })
        // And the trailing-lambda form, which was never at risk.
        span("trailing") { }
        assertEquals(2, report.reports)
        assertTrue(report.statuses.all { it == TraceStatus.OK })
    }

    @Test fun `N sequential return-aware top-level spans are N traces`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        repeat(3) { span("op-$it", returnedOutcome = { TraceOutcome(TraceStatus.OK) }) { } }
        assertEquals(3, report.reports)
    }

    @Test fun `parallel structured children under a return-aware root all reach the one trace`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        span("root", returnedOutcome = { TraceOutcome(TraceStatus.ERROR) }) {
            coroutineScope {
                repeat(10) { i -> launch { span("child-$i") { currentSpan()?.addException(IllegalStateException("c$i")) } } }
            }
        }
        assertEquals("one report for the whole tree", 1, report.reports)
        assertEquals(TraceStatus.ERROR, report.statuses.single())
        val crashes = report.records.single().filterIsInstance<ExceptionRecord>()
        assertEquals("all 10 structured children joined before the root reported", 10, crashes.size)
        assertFalse(report.records.single().isEmpty())
    }
}
