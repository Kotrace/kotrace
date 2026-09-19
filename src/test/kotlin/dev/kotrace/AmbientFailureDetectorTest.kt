package dev.kotrace

import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ambient `failureDetector` (ADR-018): a returned failure value is treated like a thrown one — span-level
 * ERROR + birthplace via the propagation recorder — configured process-wide on [Kotrace] and overridable
 * per-call on [span]. Covers ambient detection + verdict, the returned-CancellationException=CANCELLED mirror
 * (option D), explicit-returnedOutcome precedence, climb dedup, the local opt-out, per-call override,
 * zero-config invisibility, and detector-fault isolation.
 */
class AmbientFailureDetectorTest {

    private class CollectingReport(override val policy: TracePolicy = object : TracePolicy {}) : ReportAdapter {
        val statuses = mutableListOf<TraceStatus>()
        val records = mutableListOf<List<TraceRecord>>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            statuses += status
            this.records += records.toList()
        }
        val status get() = statuses.single()
        val crashes get() = records.single().filterIsInstance<ExceptionRecord>()
    }

    /** The canonical failure-as-value detector: a `Result.failure` carries the throwable that makes it a failure. */
    private val resultDetector: FailureDetector = { (it as? Result<*>)?.exceptionOrNull() }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    // --- Ambient detection + verdict ---

    @Test fun `an installed detector turns a returned failure into ERROR + one birthplace record`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)
        val boom = IllegalStateException("declined")

        val out = span("op") { Result.failure<Int>(boom) }

        assertTrue(out.isFailure)
        assertEquals(TraceStatus.ERROR, report.status)
        assertSame("the value's own throwable, recorded at the birthplace", boom, report.crashes.single().throwable)
        assertEquals("op", report.crashes.single().operation)
    }

    @Test fun `a returned success stays OK with no exception record`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)

        val out = span("op") { Result.success(42) }

        assertEquals(42, out.getOrNull())
        assertEquals(TraceStatus.OK, report.status)
        assertTrue(report.crashes.isEmpty())
    }

    @Test fun `the detector marks the producing span ERROR and records the throwable on its timeline`() = runTest {
        Kotrace.install(emptyList(), failureDetector = resultDetector)
        val boom = IllegalStateException("declined")

        // Bind to a typed local so T = Result<Int>; a bare statement in a Unit-lambda would let Kotlin
        // coerce the span's T to Unit (the value discarded), which is not the failure-as-value shape.
        val spans = collectTrace {
            val r: Result<Int> = span("op") { Result.failure(boom) }
            check(r.isFailure)
        }

        val op = spans.single { it.name == "op" }
        assertEquals(SpanStatus.ERROR, op.status)
        assertSame("the throwable is on the span's timeline, like a thrown one", boom, op.exception)
    }

    // --- Option D: a returned CancellationException mirrors a thrown one ---

    @Test fun `a returned CancellationException reports CANCELLED and is still recorded`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)
        val cancel = CancellationException("cooperative")

        span("op") { Result.failure<Int>(cancel) }

        assertEquals("returned cancellation mirrors a thrown one: CANCELLED, not ERROR", TraceStatus.CANCELLED, report.status)
        assertSame("recorded like a thrown cancellation (a log sink sees it)", cancel, report.crashes.single().throwable)
    }

    // --- Verdict precedence: explicit returnedOutcome wins ---

    @Test fun `an explicit returnedOutcome overrides the detector-derived verdict but the span still records`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)
        val boom = IllegalStateException("declined")

        // returnedOutcome forces OK even though the detector fires on the root's returned failure.
        span("op", returnedOutcome = { TraceOutcome(TraceStatus.OK) }) { Result.failure<Int>(boom) }

        assertEquals("explicit returnedOutcome (step 2) beats the root-detector default (step 4)", TraceStatus.OK, report.status)
        assertSame("the span still detected + recorded the failure", boom, report.crashes.single().throwable)
    }

    // --- Climb dedups to the deepest observing span ---

    @Test fun `the same returned failure observed up the tree collapses to one birthplace`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)
        val boom = IllegalStateException("deep")

        span("root") {
            span("mid") {
                span("leaf") { Result.failure<Int>(boom) }
            }
        }

        assertEquals(TraceStatus.ERROR, report.status)
        val crash = report.crashes.single() // deduped to the deepest observing span, like a thrown climb
        assertSame(boom, crash.throwable)
        assertEquals("birthplace is the deepest span that observed the returned failure", "leaf", crash.operation)
    }

    // --- Per-call override + local opt-out ---

    @Test fun `a per-call detector works with no ambient detector installed`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report)) // no ambient detector
        val boom = IllegalStateException("declined")

        span("op", failureDetector = resultDetector) { Result.failure<Int>(boom) }

        assertEquals(TraceStatus.ERROR, report.status)
        assertSame(boom, report.crashes.single().throwable)
    }

    @Test fun `a per-call opt-out suppresses detection on that span`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)
        val boom = IllegalStateException("expected, not an error here")

        // { null } opts this span out; with returnedOutcome left default the verdict falls back to OK.
        span("find", failureDetector = { null }) { Result.failure<Int>(boom) }

        assertEquals(TraceStatus.OK, report.status)
        assertTrue("opted-out span records nothing", report.crashes.isEmpty())
    }

    // --- Zero-config: additive, no behavior change ---

    @Test fun `with no detector a returned failure is invisible`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report)) // no failureDetector

        span("op") { Result.failure<Int>(IllegalStateException("declined")) }

        assertEquals(TraceStatus.OK, report.status)
        assertTrue(report.crashes.isEmpty())
    }

    // --- Fault isolation ---

    @Test fun `a throwing detector never fails a succeeding return and is routed to the hook`() = runTest {
        val report = CollectingReport()
        val faultPhase = AtomicReference<FaultPhase?>()
        val hook = AdapterFaultHook { phase, _, _ -> faultPhase.set(phase) }
        Kotrace.install(listOf(report), faultHook = hook, failureDetector = { error("detector blew up") })

        val out = span("op") { Result.success(7) }

        assertEquals("the operation still returns its value", 7, out.getOrNull())
        assertEquals("contained → the trace is not turned into a failure", TraceStatus.OK, report.status)
        assertTrue("a contained detector fault records nothing", report.crashes.isEmpty())
        assertEquals(FaultPhase.FAILURE_DETECTOR, faultPhase.get())
    }

    @Test fun `the detector is never invoked when the block throws`() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = { calls.incrementAndGet(); null })
        val boom = IllegalStateException("thrown, not returned")

        try {
            span<Int>("op") { throw boom } // a throw takes the catch path, not the detector
        } catch (t: Throwable) {
            // identity-agnostic: coroutine stacktrace recovery may copy the throwable across withContext.
            assertEquals("thrown, not returned", t.message)
        }

        assertEquals("detection is a normal-return-only path; the catch owns a throw", 0, calls.get())
        assertEquals(TraceStatus.ERROR, report.status)
        assertEquals("the thrown throwable is the birthplace, via the catch", "thrown, not returned", report.crashes.single().throwable.message)
    }

    @Test fun `a detector that THROWS a CancellationException is contained, not turned into cancellation`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = { throw CancellationException("from detector") })

        span("op") { Result.success(1) }

        assertEquals("a detector-thrown cancellation is a config fault, not a trace cancellation", TraceStatus.OK, report.status)
        assertTrue(report.crashes.isEmpty())
    }

    // --- Topology: span marking, dedup, and the birthplace-per-branch semantic ---

    @Test fun `every span on a returned-failure climb is marked ERROR, deduped to one recorded birthplace`() = runTest {
        Kotrace.install(emptyList(), failureDetector = resultDetector)
        val boom = IllegalStateException("deep")

        val spans = collectTrace {
            // Bind so T = Result<Int> cascades through the nested trailing lambdas (no Unit coercion).
            val r: Result<Int> = span("root") { span("mid") { span("leaf") { Result.failure(boom) } } }
            check(r.isFailure)
        }

        assertEquals("all three spans on the path are ERROR, like a thrown climb",
            listOf(SpanStatus.ERROR, SpanStatus.ERROR, SpanStatus.ERROR),
            listOf("root", "mid", "leaf").map { n -> spans.single { it.name == n }.status })
        // Every span records its own detected failure (each detects its own returned value); the SAME instance
        // rides up unchanged, so dedup-to-birthplace is a *report-time* concern (asserted separately), not a
        // per-span-events one. Here: all three carry the same boom on their timeline.
        listOf("root", "mid", "leaf").forEach { n ->
            assertSame("returned failure has no recovery copy — same instance recorded", boom, spans.single { it.name == n }.exception)
        }
    }

    @Test fun `a recovered child leaves the trace OK while the child records its own failure`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)
        val boom = IllegalStateException("recovered upstream")

        val out = span("root") {
            val inner: Result<Int> = span("child") { Result.failure(boom) } // child detects → ERROR + records
            if (inner.isFailure) Result.success(0) else inner              // root recovers → returns success
        }

        assertTrue(out.isSuccess)
        assertEquals("recovery: the root returns success, so the trace verdict is OK", TraceStatus.OK, report.status)
        // The child's birthplace still reached the report (an ERROR-gated crash sink would skip it on an OK trace).
        assertSame(boom, report.crashes.single().throwable)
        assertEquals("child", report.crashes.single().operation)
    }

    @Test fun `a semantic re-wrap into a different throwable makes a second birthplace`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)
        val a = IllegalStateException("original")
        val b = IllegalStateException("re-wrapped")

        span("root") {
            val leaf: Result<Int> = span("leaf") { Result.failure(a) } // detects a
            if (leaf.isFailure) Result.failure<Int>(b) else leaf        // root returns a *different* failure b
        }

        assertEquals(TraceStatus.ERROR, report.status)
        val byOp = report.crashes.associateBy { it.operation }
        assertSame("a is the leaf's birthplace", a, byOp["leaf"]?.throwable)
        assertSame("b is the root's birthplace — a distinct lineage, not deduped", b, byOp["root"]?.throwable)
    }

    @Test fun `a detector that synthesizes a fresh throwable per call does not dedup (contract break)`() = runTest {
        val report = CollectingReport()
        // Violates the stable-throwable contract: a new RuntimeException each invocation → distinct lineages.
        Kotrace.install(listOf(report), failureDetector = { if (it is Result<*> && it.isFailure) RuntimeException("fresh") else null })

        span("root") { span("mid") { span("leaf") { Result.failure<Int>(IllegalStateException("x")) } } }

        assertEquals("one birthplace per layer — the documented cost of synthesizing per call", 3, report.crashes.size)
        assertEquals("a record at each span on the path (no dedup)", setOf("root", "mid", "leaf"), report.crashes.map { it.operation }.toSet())
        assertEquals("each is a distinct synthesized throwable", 3, report.crashes.map { it.throwable }.distinct().size)
    }

    @Test fun `a returned CancellationException marks the span ERROR and records, like a thrown one`() = runTest {
        Kotrace.install(emptyList(), failureDetector = resultDetector)
        val cancel = CancellationException("returned")

        val spans = collectTrace {
            val r: Result<Int> = span("op") { Result.failure(cancel) }
            check(r.isFailure)
        }

        val op = spans.single { it.name == "op" }
        assertEquals("recorded + ERROR at the span level, exactly like a thrown cancellation", SpanStatus.ERROR, op.status)
        assertSame(cancel, op.exception)
    }

    @Test fun `parallel siblings returning one shared failure keep one birthplace each`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = resultDetector)
        val shared = IllegalStateException("shared")

        span("root") {
            coroutineScope {
                val s1 = async { val r: Result<Int> = span("s1") { Result.failure(shared) }; r }
                val s2 = async { val r: Result<Int> = span("s2") { Result.failure(shared) }; r }
                awaitAll(s1, s2)
            }
        }

        val crashes = report.crashes
        assertEquals("one birthplace per sibling branch (ADR-015 ancestry-scoped), not one globally", 2, crashes.size)
        assertTrue("both are the shared throwable", crashes.all { it.throwable === shared })
        assertEquals(setOf("s1", "s2"), crashes.map { it.operation }.toSet())
    }

    // --- Fault-hook precedence (detector on Kotrace, faults routed by whole-config precedence) + strict ---

    @Test fun `a detector fault routes to the per-flow hook, or is swallowed when that config has none`() = runTest {
        val processPhase = AtomicReference<FaultPhase?>()
        val flowPhase = AtomicReference<FaultPhase?>()
        val detectorCalls = AtomicInteger(0)
        Kotrace.install(
            emptyList(),
            faultHook = { p, _, _ -> processPhase.set(p) },
            failureDetector = { detectorCalls.incrementAndGet(); error("boom") },
        )

        // A flow with its own faultHook: the detector fault routes there, not to the process hook.
        withContext(SpanCollector() + TraceConfig(emptyList(), faultHook = { p, _, _ -> flowPhase.set(p) })) {
            span("op") { 1 }
        }
        assertEquals(1, detectorCalls.get())
        assertEquals(FaultPhase.FAILURE_DETECTOR, flowPhase.get())
        assertNull("whole-config precedence: the flow's config won, process hook not used", processPhase.get())

        // A flow whose config sets faultHook = null swallows it — it does NOT inherit the process hook.
        processPhase.set(null)
        withContext(SpanCollector() + TraceConfig(emptyList(), faultHook = null)) {
            span("op") { 1 }
        }
        assertEquals("the detector DID run in the null-hook flow; its fault was just swallowed", 2, detectorCalls.get())
        assertNull("null per-flow hook swallows; no inherit from process", processPhase.get())
    }

    @Test fun `with strict armed and nothing installed, detection resolution does not throw`() = runTest {
        Kotrace.strictWhenUninstalled()
        val boom = IllegalStateException("declined")

        // Plain withContext (NOT collectTrace, whose runCatching would swallow a strict throw and pass
        // vacuously): if detector resolution tripped strict, this block would throw and fail the test.
        val collector = SpanCollector()
        var completed = false
        withContext(collector) {
            val r: Result<Int> = span("op") { Result.failure(boom) }
            completed = r.isFailure
        }

        assertTrue("detection resolved without tripping the strict latch", completed)
        val op = collector.spans.single { it.name == "op" }
        assertEquals("no detector installed → no detection → span stays OK", SpanStatus.OK, op.status)
    }

    @Test fun `a middle-span opt-out leaves a hole - ERROR root, OK opted-out middle, ERROR leaf`() = runTest {
        Kotrace.install(emptyList(), failureDetector = resultDetector)
        val boom = IllegalStateException("returned up unchanged")

        val spans = collectTrace {
            val r: Result<Int> = span("root") {           // inherits ambient → detects → ERROR
                span("mid", failureDetector = { null }) {  // local opt-out → does NOT detect → OK
                    span("leaf") { Result.failure(boom) }  // inherits ambient → detects → ERROR
                }
            }
            check(r.isFailure)
        }

        assertEquals("opt-out is local; the same failure returned up is re-detected by ancestors (a hole, not a stop)",
            listOf(SpanStatus.ERROR, SpanStatus.OK, SpanStatus.ERROR),
            listOf("root", "mid", "leaf").map { n -> spans.single { it.name == n }.status })
    }
}
