package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.ExceptionOrigin
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.toJson
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exception-origin marking (ADR-019): the report classifies each `ExceptionRecord` BIRTHPLACE vs PROPAGATED,
 * and the birthplace dedup moves from the walk to a per-adapter opt-in gate (`acceptsPropagatedException`,
 * default false). Default view is birthplace-only (crash-safe, byte-identical JSON); an opt-in adapter sees
 * the whole marked climb. Uses thrown exceptions for the core cases (independent of ADR-018) plus one
 * returned-failure parity case.
 */
class ExceptionOriginMarkingTest {

    private class CollectingReport(override val policy: TracePolicy = object : TracePolicy {}) : ReportAdapter {
        val records = mutableListOf<TraceRecord>()
        var status: TraceStatus? = null
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            this.status = status
            this.records += records.toList()
        }
        val crashes get() = records.filterIsInstance<ExceptionRecord>()
    }

    private val optIn = object : TracePolicy { override val acceptsPropagatedException = true }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    /** Runs a three-deep root→mid→leaf trace whose leaf throws, swallowing the rethrow so the report can be read. */
    private suspend fun thrownClimb(boom: Throwable) {
        try {
            span("root") { span("mid") { span("leaf") { throw boom } } }
        } catch (_: Throwable) {
            // auto-root already reported ERROR before rethrow; the test asserts on the report
        }
    }

    // --- Default view: birthplace-only, byte-identical JSON ---

    @Test fun `the default policy keeps one birthplace record and omits exception_origin`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))

        thrownClimb(IllegalStateException("deep"))

        assertEquals(TraceStatus.ERROR, report.status)
        val crash = report.crashes.single()
        assertEquals(ExceptionOrigin.BIRTHPLACE, crash.origin)
        assertEquals("birthplace is the deepest span carrying the lineage", "leaf", crash.operation)
        assertFalse("a birthplace record's JSON has no exception_origin key", crash.toJson().contains("exception_origin"))
        assertEquals("a BIRTHPLACE record's JSON is byte-identical to an unclassified (pre-ADR-019 / live) one",
            crash.copy(origin = null).toJson(), crash.toJson())
    }

    // --- Opt-in view: the full marked climb ---

    @Test fun `an opt-in adapter receives every span on the climb, birthplace and propagated marked`() = runTest {
        val report = CollectingReport(optIn)
        Kotrace.install(listOf(report))

        thrownClimb(IllegalStateException("deep"))

        val byOp = report.crashes.associateBy { it.operation }
        assertEquals("one record per span on the path", setOf("root", "mid", "leaf"), byOp.keys)
        assertEquals(ExceptionOrigin.BIRTHPLACE, byOp["leaf"]!!.origin)
        assertEquals(ExceptionOrigin.PROPAGATED, byOp["mid"]!!.origin)
        assertEquals(ExceptionOrigin.PROPAGATED, byOp["root"]!!.origin)
        assertTrue("a PROPAGATED record serializes its origin", byOp["root"]!!.toJson().contains("\"exception_origin\":\"propagated\""))
        assertFalse("the BIRTHPLACE record does not", byOp["leaf"]!!.toJson().contains("exception_origin"))
    }

    @Test fun `report order is DFS - ancestor propagated precedes the deeper birthplace`() = runTest {
        val report = CollectingReport(optIn)
        Kotrace.install(listOf(report))

        thrownClimb(IllegalStateException("deep"))

        assertEquals("tree order (root→leaf), the opposite of the live deepest-first climb",
            listOf("root", "mid", "leaf"), report.crashes.map { it.operation })
    }

    // --- Two adapters, one walk, opposite shapes ---

    @Test fun `a default crash sink and an opt-in log sink see one and N off the same trace`() = runTest {
        val crash = CollectingReport()
        val log = CollectingReport(optIn)
        Kotrace.install(listOf(crash, log))

        thrownClimb(IllegalStateException("deep"))

        assertEquals("crash sink: birthplace only", 1, crash.crashes.size)
        assertEquals("log sink: the full climb", 3, log.crashes.size)
    }

    // --- The propagated gate runs before policy.accepts ---

    @Test fun `a default policy is never invoked on a dropped propagated copy`() = runTest {
        val exceptionAccepts = AtomicInteger(0)
        val policy = object : TracePolicy {
            override fun acceptsEvent(event: SpanEvent): Boolean {
                if (event is ExceptionEvent) exceptionAccepts.incrementAndGet()
                return true
            }
        }
        Kotrace.install(listOf(CollectingReport(policy)))

        thrownClimb(IllegalStateException("deep"))

        assertEquals("gate-first: policy.acceptsEvent sees only the surviving birthplace, not the 2 dropped copies",
            1, exceptionAccepts.get())
    }

    @Test fun `a throwing acceptsPropagatedException getter does not abort an OK self-gated adapter`() = runTest {
        // The flag is read lazily, only when the sequence is consumed; an adapter that returns for OK never
        // forces it — so a throwing getter cannot break an unrelated OK trace.
        val adapter = object : ReportAdapter {
            override val policy = object : TracePolicy {
                override val acceptsPropagatedException: Boolean get() = throw RuntimeException("must not be read")
            }
            var invoked = false
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                invoked = true
                if (status == TraceStatus.OK) return // consumes nothing
            }
        }
        Kotrace.install(listOf(adapter))

        span("op") { 1 } // OK trace

        assertTrue("onReport ran without the getter throwing", adapter.invoked)
    }

    // --- acceptsEvent still dominates ---

    @Test fun `a policy dropping the exception event drops it regardless of origin or the propagated gate`() = runTest {
        val report = CollectingReport(object : TracePolicy {
            override val acceptsPropagatedException = true // opt in to the climb…
            override fun acceptsEvent(event: SpanEvent) = event !is ExceptionEvent // …but drop all exceptions
        })
        Kotrace.install(listOf(report))

        thrownClimb(IllegalStateException("deep"))

        assertTrue("acceptsEvent=false wins: no exception records survive", report.crashes.isEmpty())
    }

    // --- attached orphans ---

    @Test fun `an attached orphan is a BIRTHPLACE and rides through the default gate`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        val orphan = IllegalStateException("saga rollback")

        // A normal-return auto-root whose returnedOutcome attaches a trace-level orphan (ADR-012).
        span("op", returnedOutcome = { TraceOutcome(TraceStatus.ERROR, attached = listOf(orphan)) }) { 1 }

        val crash = report.crashes.single()
        assertEquals(ExceptionOrigin.BIRTHPLACE, crash.origin)
        assertEquals("keyed to the root operation", "op", crash.operation)
    }

    // --- live records are unclassified ---

    @Test fun `live exception records carry origin null and omit exception_origin`() = runTest {
        val live = object : LiveAdapter {
            override val policy = object : TracePolicy {}
            val records = mutableListOf<TraceRecord>()
            override fun onLive(record: TraceRecord) { records += record }
        }
        Kotrace.install(listOf(live))

        thrownClimb(IllegalStateException("deep"))

        val liveCrashes = live.records.filterIsInstance<ExceptionRecord>()
        assertEquals("a live watch sees every copy of the climb as it is thrown", 3, liveCrashes.size)
        assertEquals(setOf("root", "mid", "leaf"), liveCrashes.map { it.operation }.toSet())
        assertTrue("every live exception record is unclassified — the tree is incomplete at live time",
            liveCrashes.all { it.origin == null })
        assertTrue("and its JSON omits the key", liveCrashes.none { it.toJson().contains("exception_origin") })
    }

    // --- parity with ADR-018 returned failures ---

    @Test fun `a returned-failure climb is marked identically to a thrown one`() = runTest {
        val report = CollectingReport(optIn)
        Kotrace.install(listOf(report), failureDetector = { (it as? Result<*>)?.exceptionOrNull() })
        val boom = IllegalStateException("returned deep")

        span("root") { span("mid") { span("leaf") { Result.failure<Int>(boom) } } }

        val byOp = report.crashes.associateBy { it.operation }
        assertEquals(setOf("root", "mid", "leaf"), byOp.keys)
        assertEquals(ExceptionOrigin.BIRTHPLACE, byOp["leaf"]!!.origin)
        assertEquals(ExceptionOrigin.PROPAGATED, byOp["mid"]!!.origin)
        assertEquals(ExceptionOrigin.PROPAGATED, byOp["root"]!!.origin)
    }

    // --- Snapshot consistency (deterministic, index-level) ---

    @Test fun `TraceTreeIndex snapshots events once - a late append is unseen by eventsOf and classification`() {
        val root = Span("t", "0000000000000001", null, "root", 0)
        val first = ExceptionEvent(IllegalStateException("first"), 1)
        root.events += first

        val index = TraceTreeIndex(listOf(root), root) // snapshot taken here, in the constructor

        root.events += ExceptionEvent(IllegalStateException("late"), 2) // appended AFTER indexing

        assertEquals("eventsOf returns the index-time snapshot, not the live list", listOf<SpanEvent>(first), index.eventsOf(root))
        assertEquals("classification used that same snapshot — the late event is invisible to it",
            listOf(first), index.birthplaceExceptionsOf(root))
    }

    @Test fun `multiple independent failing branches keep multiple birthplaces under the default policy`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report), failureDetector = { (it as? Result<*>)?.exceptionOrNull() })
        val a = IllegalStateException("branch A")
        val b = IllegalStateException("branch B")

        span("root") {
            span("a") { Result.failure<Int>(a) }
            span("b") { Result.failure<Int>(b) }
            Result.success(0) // root aggregates to success → its own value is not a failure
        }

        val byOp = report.crashes.associateBy { it.operation }
        assertEquals("two independent lineages → two birthplaces, not one", setOf("a", "b"), byOp.keys)
        assertTrue("each is a BIRTHPLACE", byOp.values.all { it.origin == ExceptionOrigin.BIRTHPLACE })
    }
}
