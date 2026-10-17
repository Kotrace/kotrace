// Tests inspect the raw span timeline (ADR-028); nothing here reaches a sink.
@file:OptIn(dev.kotrace.UnredactedTraceRead::class)

package dev.kotrace

import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.exception
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The ambient `returnVerdictClassifier` (ADR-018/020) classifies returned failures process-wide on [Kotrace] or
 * per-call on [span]. Every classification marks the span ERROR; a [ReturnVerdict.Failure] with a throwable
 * additionally records a birthplace, while one with a null throwable emits no event. Covers ambient classification and
 * verdict, the returned-CancellationException=CANCELLED mirror (option D), explicit-returnedOutcome
 * precedence, climb dedup, recovery, local opt-out, per-call override, zero-configuration invisibility, and
 * classifier-fault isolation.
 */
class AmbientReturnVerdictClassifierTest {

    private sealed interface DomainResult<out T> {
        data class Success<out T>(val value: T) : DomainResult<T>
        data class Failure(val code: String) : DomainResult<Nothing>
    }

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

    /** The canonical failure-as-value classifier: a `Result.failure` carries the throwable that makes it a failure. */
    private val resultClassifier: ReturnVerdictClassifier<Any?> = { value ->
        (value as? Result<*>)?.exceptionOrNull()?.let(ReturnVerdict::Failure) ?: ReturnVerdict.Success
    }

    /** A value-only domain failure has no throwable and therefore produces status, but no exception record. */
    private val domainClassifier: ReturnVerdictClassifier<Any?> = { value ->
        if (value is DomainResult.Failure) ReturnVerdict.Failure(null) else ReturnVerdict.Success
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    // --- Ambient classification + verdict ---

    @Test fun `a value-only failure marks the trace ERROR without inventing a record`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = domainClassifier)

        val out = span("LoginUseCase") { DomainResult.Failure("login.already_exists") }

        assertEquals(DomainResult.Failure("login.already_exists"), out)
        assertEquals(TraceStatus.ERROR, report.status)
        assertTrue("a failure with no throwable carries status only; it emits no machine record", report.records.single().isEmpty())
    }

    @Test fun `a value-only failure marks every returning span ERROR without closing it early`() = runTest {
        Kotrace.install(emptyList(), returnVerdictClassifier = domainClassifier)
        var childWasOpenWhileRunning = false

        val spans = collectTrace {
            val out: DomainResult<Unit> = span("root") {
                span("LoginUseCase") {
                    childWasOpenWhileRunning = currentSpan()?.endNanos == null
                    DomainResult.Failure("login.already_exists")
                }
            }
            check(out is DomainResult.Failure)
        }

        assertEquals(
            listOf(SpanStatus.ERROR, SpanStatus.ERROR),
            listOf("root", "LoginUseCase").map { name -> spans.single { it.name == name }.status },
        )
        assertTrue("the classifier does not close a suspend-owned span from inside its block", childWasOpenWhileRunning)
        assertTrue("normal span completion owns endNanos", spans.all { it.endNanos != null })
        assertTrue("a failure with no throwable adds no event to either span", spans.all { it.eventBuffer.isEmpty() })
    }

    @Test fun `an explicit returnedOutcome overrides a value-only verdict but the span still fails`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = domainClassifier)
        var root: Span? = null

        span(
            "LoginUseCase",
            returnedOutcome = { TraceOutcome(TraceStatus.OK) },
        ) {
            root = currentSpan()
            DomainResult.Failure("login.already_exists")
        }

        assertEquals("explicit returnedOutcome still owns the trace verdict", TraceStatus.OK, report.status)
        assertEquals("classification still describes the span itself", SpanStatus.ERROR, root?.status)
        assertTrue("a failure with no throwable still emits no machine record", report.records.single().isEmpty())
    }

    @Test fun `recovering a value-only child leaves the trace OK`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = domainClassifier)
        var child: Span? = null

        val out = span("root") {
            val failure: DomainResult<Int> = span("LoginUseCase") {
                child = currentSpan()
                DomainResult.Failure("login.already_exists")
            }
            if (failure is DomainResult.Failure) DomainResult.Success(0) else failure
        }

        assertEquals(DomainResult.Success(0), out)
        assertEquals("the root recovered to success", TraceStatus.OK, report.status)
        assertEquals("the producing child remains failed", SpanStatus.ERROR, child?.status)
        assertTrue("status-only classification emitted no records", report.records.single().isEmpty())
    }

    @Test fun `an installed classifier turns a returned failure into ERROR + one birthplace record`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)
        val boom = IllegalStateException("declined")

        val out = span("op") { Result.failure<Int>(boom) }

        assertTrue(out.isFailure)
        assertEquals(TraceStatus.ERROR, report.status)
        assertSame("the value's own throwable, recorded at the birthplace", boom, report.crashes.single().throwable)
        assertEquals("op", report.crashes.single().operation)
    }

    @Test fun `a returned success stays OK with no exception record`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)

        val out = span("op") { Result.success(42) }

        assertEquals(42, out.getOrNull())
        assertEquals(TraceStatus.OK, report.status)
        assertTrue(report.crashes.isEmpty())
    }

    @Test fun `the classifier marks the producing span ERROR and records the throwable on its timeline`() = runTest {
        Kotrace.install(emptyList(), returnVerdictClassifier = resultClassifier)
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

    // --- Option D (amended by ADR-022): a returned CancellationException mirrors a thrown one ---

    @Test fun `a returned CancellationException while the coroutine is active is a failure - ERROR, still recorded`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)
        val cancel = CancellationException("cooperative")

        span("op") { Result.failure<Int>(cancel) }

        assertEquals(
            "mirrors a thrown one: the coroutine is still active, so this is a failure, not a cancellation (ADR-022)",
            TraceStatus.ERROR,
            report.status,
        )
        assertSame("recorded like any returned throwable (a log sink sees it)", cancel, report.crashes.single().throwable)
    }

    @Test fun `runCatching around an expired withTimeout returns a failure the trace reports as ERROR`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)

        val r: Result<Unit> = span("op") { runCatching { withTimeout(20) { delay(1_000) } } }

        assertTrue(r.exceptionOrNull() is TimeoutCancellationException)
        assertEquals("a returned timeout is not hidden as CANCELLED", TraceStatus.ERROR, report.status)
    }

    // --- Verdict precedence: explicit returnedOutcome wins ---

    @Test fun `an explicit returnedOutcome overrides the classifier-derived verdict but the span still records`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)
        val boom = IllegalStateException("declined")

        // returnedOutcome forces OK even though the classifier fires on the root's returned failure.
        span("op", returnedOutcome = { TraceOutcome(TraceStatus.OK) }) { Result.failure<Int>(boom) }

        assertEquals("explicit returnedOutcome (step 2) beats the root-classifier default (step 4)", TraceStatus.OK, report.status)
        assertSame("the span still classified + recorded the failure", boom, report.crashes.single().throwable)
    }

    // --- Climb dedups to the deepest observing span ---

    @Test fun `the same returned failure observed up the tree collapses to one birthplace`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)
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

    @Test fun `a per-call classifier works with no ambient classifier installed`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report)) // no ambient classifier
        val boom = IllegalStateException("declined")

        span("op", returnVerdictClassifier = resultClassifier) { Result.failure<Int>(boom) }

        assertEquals(TraceStatus.ERROR, report.status)
        assertSame(boom, report.crashes.single().throwable)
    }

    @Test fun `a per-call opt-out suppresses classification on that span`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)
        val boom = IllegalStateException("expected, not an error here")

        // { null } opts this span out; with returnedOutcome left default the verdict falls back to OK.
        span("find", returnVerdictClassifier = { ReturnVerdict.Success }) { Result.failure<Int>(boom) }

        assertEquals(TraceStatus.OK, report.status)
        assertTrue("opted-out span records nothing", report.crashes.isEmpty())
    }

    // --- Zero-configuration: additive, no behavior change ---

    @Test fun `with no classifier a returned failure is invisible`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report)) // no returnVerdictClassifier

        span("op") { Result.failure<Int>(IllegalStateException("declined")) }

        assertEquals(TraceStatus.OK, report.status)
        assertTrue(report.crashes.isEmpty())
    }

    // --- Fault isolation ---

    @Test fun `a throwing classifier never fails a succeeding return and is routed to the hook`() = runTest {
        val report = CollectingReport()
        val faultPhase = AtomicReference<FaultPhase?>()
        val hook = FaultHook { phase, _, _ -> faultPhase.set(phase) }
        Kotrace.installFaultHook(hook)
        Kotrace.install(listOf(report), returnVerdictClassifier = { error("classifier blew up") })

        val out = span("op") { Result.success(7) }

        assertEquals("the operation still returns its value", 7, out.getOrNull())
        assertEquals("contained → the trace is not turned into a failure", TraceStatus.OK, report.status)
        assertTrue("a contained classifier fault records nothing", report.crashes.isEmpty())
        assertEquals(FaultPhase.RETURN_VERDICT_CLASSIFIER, faultPhase.get())
    }

    @Test fun `the classifier is never invoked when the block throws`() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = { calls.incrementAndGet(); ReturnVerdict.Success })
        val boom = IllegalStateException("thrown, not returned")

        try {
            span<Int>("op") { throw boom } // a throw takes the catch path, not the classifier
        } catch (t: Throwable) {
            // identity-agnostic: coroutine stacktrace recovery may copy the throwable across withContext.
            assertEquals("thrown, not returned", t.message)
        }

        assertEquals("classification is a normal-return-only path; the catch owns a throw", 0, calls.get())
        assertEquals(TraceStatus.ERROR, report.status)
        assertEquals("the thrown throwable is the birthplace, via the catch", "thrown, not returned", report.crashes.single().throwable.message)
    }

    @Test fun `a classifier that THROWS a CancellationException is contained, not turned into cancellation`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = { throw CancellationException("from classifier") })

        span("op") { Result.success(1) }

        assertEquals("a classifier-thrown cancellation is a configuration fault, not a trace cancellation", TraceStatus.OK, report.status)
        assertTrue(report.crashes.isEmpty())
    }

    // --- Topology: span marking, dedup, and the birthplace-per-branch semantic ---

    @Test fun `every span on a returned-failure climb is marked ERROR, deduped to one recorded birthplace`() = runTest {
        Kotrace.install(emptyList(), returnVerdictClassifier = resultClassifier)
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
            assertSame("returned failure has no recovery copy, so the same instance is recorded", boom, spans.single { it.name == n }.exception)
        }
    }

    @Test fun `a recovered child leaves the trace OK while the child records its own failure`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)
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
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)
        val a = IllegalStateException("original")
        val b = IllegalStateException("re-wrapped")

        span("root") {
            val leaf: Result<Int> = span("leaf") { Result.failure(a) } // detects a
            if (leaf.isFailure) Result.failure<Int>(b) else leaf        // root returns a *different* failure b
        }

        assertEquals(TraceStatus.ERROR, report.status)
        val byOp = report.crashes.associateBy { it.operation }
        assertSame("a is the leaf's birthplace", a, byOp["leaf"]?.throwable)
        assertSame("b is the root's birthplace: a distinct lineage, not deduped", b, byOp["root"]?.throwable)
    }

    @Test fun `a classifier that synthesizes a fresh throwable per call does not dedup (contract break)`() = runTest {
        val report = CollectingReport()
        // Violates the stable-throwable contract: a new RuntimeException each invocation → distinct lineages.
        Kotrace.install(listOf(report), returnVerdictClassifier = {
            if (it is Result<*> && it.isFailure) ReturnVerdict.Failure(RuntimeException("fresh")) else ReturnVerdict.Success
        })

        span("root") { span("mid") { span("leaf") { Result.failure<Int>(IllegalStateException("x")) } } }

        assertEquals("one birthplace per layer, the documented cost of synthesizing per call", 3, report.crashes.size)
        assertEquals("a record at each span on the path (no dedup)", setOf("root", "mid", "leaf"), report.crashes.map { it.operation }.toSet())
        assertEquals("each is a distinct synthesized throwable", 3, report.crashes.map { it.throwable }.distinct().size)
    }

    @Test fun `a returned CancellationException marks the span ERROR and records, like a thrown one`() = runTest {
        Kotrace.install(emptyList(), returnVerdictClassifier = resultClassifier)
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
        Kotrace.install(listOf(report), returnVerdictClassifier = resultClassifier)
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

    // --- The process-wide fault hook (ADR-037) + strict ---

    @Test fun `a classifier fault reaches the process-wide hook, whatever configuration the flow overlays`() = runTest {
        val phases = mutableListOf<FaultPhase>()
        val classifierCalls = AtomicInteger(0)
        Kotrace.installFaultHook { p, _, _ -> synchronized(phases) { phases += p } }
        Kotrace.install(emptyList(), returnVerdictClassifier = { classifierCalls.incrementAndGet(); error("boom") })

        withContext(SpanCollector() + TraceConfig(emptyList())) { span("op") { 1 } }
        withContext(SpanCollector()) { span("op") { 1 } }

        assertEquals(2, classifierCalls.get())
        assertEquals(
            "the hook belongs to the process, not to a configuration",
            listOf(FaultPhase.RETURN_VERDICT_CLASSIFIER, FaultPhase.RETURN_VERDICT_CLASSIFIER),
            phases,
        )
    }

    @Test fun `with strict armed and nothing installed, classification resolution does not throw`() = runTest {
        Kotrace.strictWhenNotInstalled()
        val boom = IllegalStateException("declined")

        // Plain withContext (NOT collectTrace, whose runCatching would swallow a strict throw and pass
        // vacuously): if classifier resolution tripped strict, this block would throw and fail the test.
        val collector = SpanCollector()
        var completed = false
        withContext(collector) {
            val r: Result<Int> = span("op") { Result.failure(boom) }
            completed = r.isFailure
        }

        assertTrue("classification resolved without tripping the strict latch", completed)
        val op = collector.spans.single { it.name == "op" }
        assertEquals("no classifier installed → no classification → span stays OK", SpanStatus.OK, op.status)
    }

    @Test fun `a middle-span opt-out leaves a hole - ERROR root, OK opted-out middle, ERROR leaf`() = runTest {
        Kotrace.install(emptyList(), returnVerdictClassifier = resultClassifier)
        val boom = IllegalStateException("returned up unchanged")

        val spans = collectTrace {
            val r: Result<Int> = span("root") {           // inherits ambient → detects → ERROR
                // local opt-out → does NOT detect → OK
                span("mid", returnVerdictClassifier = { ReturnVerdict.Success }) {
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
