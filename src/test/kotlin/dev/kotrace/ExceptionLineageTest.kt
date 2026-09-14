package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.addException
import dev.kotrace.event.lineageKeyOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Birthplace dedup by a stable, fail-open lineage key (ADR-015). Verifies the recover-and-rethrow-different
 * fix (B01) and the surrounding matrix: a single climb still collapses to one record (surviving coroutine
 * stacktrace-recovery copies under `runTest`), unrelated failures each report, a consumer-recorded exception
 * is its own lineage, and object reuse is one lineage.
 */
class ExceptionLineageTest {

    private val acceptAll = object : TracePolicy {
        override fun acceptsSpan(span: Span) = true
        override fun acceptsEvent(event: SpanEvent) = true
        override val acceptsSensitive = true
    }

    private inner class CollectingReport : ReportAdapter {
        override val policy: TracePolicy = acceptAll
        val records = mutableListOf<dev.kotrace.event.TraceRecord>()
        override fun onReport(status: TraceStatus, records: kotlin.sequences.Sequence<dev.kotrace.event.TraceRecord>) {
            this.records += records
        }
    }

    /** Runs [block] as a root trace under a [CollectingReport], swallowing the escape, and returns the crashes. */
    private suspend fun crashesOf(block: suspend () -> Unit): Pair<List<ExceptionRecord>, CollectingReport> {
        val report = CollectingReport()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            runCatching { block() }
            collector.reportTrace(TraceStatus.ERROR)
        }
        return report.records.filterIsInstance<ExceptionRecord>() to report
    }

    private fun crashPairs(crashes: List<ExceptionRecord>) =
        crashes.map { it.operation to it.throwable.javaClass.simpleName }

    @Test
    fun `recover-and-rethrow-different reports both failures, each at its span (B01)`() = runTest {
        val (crashes, _) = crashesOf {
            span("root") {
                runCatching { span("child") { throw IllegalArgumentException("A") } }
                throw IllegalStateException("B")
            }
        }
        assertEquals(
            "both the escaping B (root) and the recovered A (child) report",
            setOf("root" to "IllegalStateException", "child" to "IllegalArgumentException"),
            crashPairs(crashes).toSet(),
        )
    }

    @Test
    fun `wrap-and-rethrow a different class keeps the semantic cause distinct`() = runTest {
        // throw B(cause = A): B's cause is the recovered A. A semantic cause is NOT a recovery wrapper, so B
        // keeps its own lineage and both report — guards against the deepest-cause regression.
        val (crashes, _) = crashesOf {
            span("root") {
                val a = runCatching { span("child") { throw IllegalArgumentException("A") } }.exceptionOrNull()
                throw IllegalStateException("B", a)
            }
        }
        assertEquals(
            setOf("root" to "IllegalStateException", "child" to "IllegalArgumentException"),
            crashPairs(crashes).toSet(),
        )
    }

    @Test
    fun `a single throwable climbing many spans collapses to one record at its birthplace`() = runTest {
        // runTest enables coroutine stacktrace recovery, so the climbing object is a different instance at each
        // withContext boundary. The lineage key survives that copy, so the climb still dedups to one record.
        val (crashes, _) = crashesOf {
            span("a") { span("b") { span("c") { throw IllegalStateException("deep") } } }
        }
        assertEquals("one crash record at the deepest span", listOf("c" to "IllegalStateException"), crashPairs(crashes))
    }

    @Test
    fun `two sibling branches each failing are two birthplaces`() = runTest {
        val (crashes, _) = crashesOf {
            span("root") {
                runCatching { span("left") { throw IllegalArgumentException("L") } }
                runCatching { span("right") { throw IllegalStateException("R") } }
            }
        }
        assertEquals(
            setOf("left" to "IllegalArgumentException", "right" to "IllegalStateException"),
            crashPairs(crashes).toSet(),
        )
    }

    @Test
    fun `a consumer-recorded exception is its own lineage, never deduped against a climb`() = runTest {
        val shared = IllegalStateException("shared")
        val (crashes, _) = crashesOf {
            span("root") {
                currentSpan()!!.addException(shared) // explicit consumer record — fresh lineage key
                span("child") { throw shared }        // climb records the same object at child (and root)
            }
        }
        // The explicit record on root survives (fresh key) even though a descendant carries the same object;
        // the climb itself still collapses to the child birthplace.
        assertEquals(
            setOf("root" to "IllegalStateException", "child" to "IllegalStateException"),
            crashPairs(crashes).toSet(),
        )
        assertEquals("exactly the explicit record plus the child birthplace", 2, crashes.size)
    }

    @Test
    fun `reusing one throwable instance collapses to a single record (documented limitation)`() = runTest {
        val boom = IllegalStateException("boom")
        val (crashes, _) = crashesOf {
            span("root") {
                runCatching { span("child") { throw boom } }
                throw boom // same instance rethrown — one lineage by identity
            }
        }
        assertEquals("object reuse is one lineage", 1, crashes.size)
    }

    @Test
    fun `wrap-and-rethrow the same class but a different message keeps them distinct`() = runTest {
        // Same runtime class as its cause, but a different message — the message conjunct stops this semantic
        // wrap from being mistaken for a recovery copy, so the escaping B is not collapsed into A.
        val (crashes, _) = crashesOf {
            span("root") {
                val a = runCatching { span("child") { throw IllegalStateException("A") } }.exceptionOrNull()
                throw IllegalStateException("B", a)
            }
        }
        assertEquals("both report despite sharing a class", 2, crashes.size)
        assertEquals(
            setOf("root" to "IllegalStateException", "child" to "IllegalStateException"),
            crashPairs(crashes).toSet(),
        )
    }

    @Test
    fun `two unrelated failures of identical class and message both report`() = runTest {
        // No cause link between them: identical class + message must not be read as one lineage.
        val (crashes, _) = crashesOf {
            span("root") {
                runCatching { span("child") { throw IllegalStateException("dup") } }
                throw IllegalStateException("dup")
            }
        }
        assertEquals("identity, not class+message, decides lineage", 2, crashes.size)
    }

    // Each hostile throwable makes exactly one accessor throw, and is shaped so key derivation actually
    // reaches that accessor: `cause` is read first; `message` is only read once `cause` is non-null and of the
    // same class; `stackTrace` only once class and message also match. So one throwable per accessor.
    private class ThrowingCause : RuntimeException("x") {
        override val cause: Throwable? get() = throw IllegalStateException("no cause")
    }
    private class ThrowingMessage(cause: Throwable?) : RuntimeException("m", cause) {
        override val message: String get() = throw IllegalStateException("no message")
    }
    private class ThrowingStack(cause: Throwable?) : RuntimeException("s", cause) {
        override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException("no stack")
    }

    @OptIn(NonSuspendTracingBridge::class)
    @Test
    fun `end(ERROR) with a throwable whose accessors throw records it without propagating`() {
        // lineageKeyOf must be total: Span.end(error) has no strict-mode guard, so a hostile cause/message/
        // stackTrace accessor must fail open, not escape after the span is marked complete. Cover all three
        // accessor paths, each reached by a distinctly shaped throwable.
        val hostiles = listOf(
            ThrowingCause(),
            ThrowingMessage(ThrowingMessage(null)), // same-class cause so the message conjunct is reached
            ThrowingStack(ThrowingStack(null)),     // same-class + equal message so the stackTrace conjunct is reached
        )
        hostiles.forEach { hostile ->
            val span = Span(traceId = "t", spanId = "s", parentId = null, name = "op", startNanos = 0)
            span.end(SpanStatus.ERROR, hostile) // must not throw
            val recorded = span.events.filterIsInstance<ExceptionEvent>().single()
            assertTrue("the hostile throwable is still recorded", recorded.throwable === hostile)
            assertEquals(SpanStatus.ERROR, span.status)
        }
    }

    @Test
    fun `a single climb re-recorded on each enclosing span shares one lineage key`() = runTest {
        // The report already dedups to one record; assert the mechanism directly — every re-recording of the
        // climbing throwable canonicalizes to the same identity, whether recovery copied it or not.
        val spans = collectTrace {
            span("a") { span("b") { span("c") { throw IllegalStateException("deep") } } }
        }
        val events = spans.flatMap { it.events.filterIsInstance<ExceptionEvent>() }
        assertEquals("the throwable is re-recorded on every enclosing span", 3, events.size)
        val distinctKeys = events.map { System.identityHashCode(it.lineageKey) }.distinct()
        assertEquals("all re-recordings share one lineage key", 1, distinctKeys.size)
    }

    @Test
    fun `two unrelated null-message failures both report`() = runTest {
        val (crashes, _) = crashesOf {
            span("root") {
                runCatching { span("child") { throw IllegalStateException() } }
                throw IllegalStateException()
            }
        }
        assertEquals("null messages must not merge distinct lineages", 2, crashes.size)
    }

    @Test
    fun `two explicit exception events on one span both report`() = runTest {
        val (crashes, _) = crashesOf {
            span("only") {
                currentSpan()!!.addException(IllegalArgumentException("explicit"))
                throw IllegalStateException("escape")
            }
        }
        assertEquals(
            "the explicit record and the escaping record both belong to the one span",
            listOf("only" to "IllegalArgumentException", "only" to "IllegalStateException"),
            crashPairs(crashes).sortedBy { it.second },
        )
    }

    @Test
    fun `parallel children each failing are two birthplaces`() = runTest {
        val (crashes, _) = crashesOf {
            span("root") {
                coroutineScope {
                    listOf(
                        async { runCatching { span("a") { throw IllegalArgumentException("A") } } },
                        async { runCatching { span("b") { throw IllegalStateException("B") } } },
                    ).awaitAll()
                }
            }
        }
        assertEquals(
            setOf("a" to "IllegalArgumentException", "b" to "IllegalStateException"),
            crashPairs(crashes).toSet(),
        )
    }

    @Test
    fun `attached failures with the same object twice each survive`() = runTest {
        val report = CollectingReport()
        val collector = SpanCollector()
        val orphan = IllegalStateException("orphan")
        withContext(collector + TraceConfig(listOf(report))) {
            span("root") { /* no throwable of its own */ }
            collector.reportTrace(TraceStatus.ERROR, attached = listOf(orphan, orphan))
        }
        val crashes = report.records.filterIsInstance<ExceptionRecord>()
        assertEquals("attached rides past the dedup — both entries survive (ADR-012)", 2, crashes.size)
    }

    // --- lineageKeyOf unit tests: recovery-wrapper recognition, driven by a forged boundary frame so they
    // --- need no live coroutine recovery. StackTraceElement/setStackTrace are public.
    private fun framed(marker: String) = arrayOf(StackTraceElement(marker, "_", "f", -1))

    @Test
    fun `lineageKeyOf steps through a recognized boundary wrapper to the original`() {
        val original = IllegalStateException("m")
        val wrapper = IllegalStateException("m", original).apply { stackTrace = framed("_COROUTINE._BOUNDARY") }
        assertSame("a same-class, same-message, boundary-framed copy canonicalizes to its cause", original, lineageKeyOf(wrapper))
    }

    @Test
    fun `lineageKeyOf does not step through a _CREATION frame`() {
        val original = IllegalStateException("m")
        val wrapper = IllegalStateException("m", original).apply { stackTrace = framed("_COROUTINE._CREATION") }
        assertSame("only the boundary frame evidences a recovery copy, not the creation frame", wrapper, lineageKeyOf(wrapper))
    }

    @Test
    fun `lineageKeyOf does not step when the message differs (cause-only-constructor fallback)`() {
        val original = IllegalStateException("original-message")
        val wrapper = IllegalStateException("different-message", original).apply { stackTrace = framed("_COROUTINE._BOUNDARY") }
        assertSame("a differing message fails the conjunct — fail open to a distinct key", wrapper, lineageKeyOf(wrapper))
    }

    @Test
    fun `lineageKeyOf terminates on a cause cycle`() {
        val holder = arrayOfNulls<Throwable>(2)
        class Loop(val idx: Int) : RuntimeException("loop") {
            override val cause: Throwable? get() = holder[1 - idx]
            override fun getStackTrace(): Array<StackTraceElement> = framed("_COROUTINE._BOUNDARY")
        }
        holder[0] = Loop(0)
        holder[1] = Loop(1)
        val key = lineageKeyOf(holder[0]!!) // must not hang
        assertTrue("cycle detection returns a node from the cycle", key === holder[0] || key === holder[1])
    }

    @Test
    fun `lineageKeyOf stops at the depth bound on a very long wrapper chain`() {
        val chain = (0..150).map { IllegalStateException("m") }
        for (i in 0 until 150) {
            chain[i].initCause(chain[i + 1])
            chain[i].stackTrace = framed("_COROUTINE._BOUNDARY")
        }
        val key = lineageKeyOf(chain[0]) // must not walk the whole 150-deep chain
        assertSame("the walk stops at the depth bound (100 steps)", chain[100], key)
        assertNotSame("it did not run to the tail", chain[150], key)
    }

    @OptIn(UnredactedTraceRead::class)
    @Test
    fun `renderTree shows every birthplace failure, a span with two distinct failures shows both`() = runTest {
        val spans = collectTrace {
            span("root") {
                currentSpan()!!.addException(IllegalArgumentException("explicit"))
                throw IllegalStateException("escape")
            }
        }
        val tree = spans.renderTree()
        assertTrue("explicit failure rendered", tree.contains("error: IllegalArgumentException: explicit"))
        assertTrue("escaping failure rendered", tree.contains("error: IllegalStateException: escape"))
    }
}
