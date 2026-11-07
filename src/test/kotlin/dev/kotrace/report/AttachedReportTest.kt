package dev.kotrace.report

import dev.kotrace.Kotrace
import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.SpanContext
import dev.kotrace.TraceConfig
import dev.kotrace.TraceLink
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.currentSpan
import dev.kotrace.event.*
import dev.kotrace.span
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AttachedReportTest {
    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    /** [reports] captures each call separately; [policy] applies to every report. */
    private class Capture(override val policy: TracePolicy = object : TracePolicy {}) : ReportAdapter {
        val reports = mutableListOf<List<TraceRecord>>()
        val statuses = mutableListOf<TraceStatus>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            statuses += status
            reports += records.toList()
        }
        fun exceptions(index: Int = reports.lastIndex) = reports[index].filterIsInstance<ExceptionRecord>()
    }

    private fun collectorWithOwner(): Pair<SpanCollector, Span> {
        val collector = SpanCollector()
        val owner = Span("trace", "owner", null, "owner", startNanos = 1L)
        collector.add(owner)
        return collector to owner
    }

    private fun parallel(count: Int, block: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        try {
            val work = List(count) { index ->
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    block(index)
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            work.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `identity uniqueness never calls throwable equality`() {
        class EqualFailure : RuntimeException() {
            override fun equals(other: Any?) = error("throwable equality must not run")
            override fun hashCode() = error("throwable hashCode must not run")
        }
        val (collector, owner) = collectorWithOwner()
        val first = EqualFailure()
        val second = EqualFailure()
        collector.attach(owner, first, 2L)
        collector.attach(owner, second, 3L)
        collector.attach(owner, first, 4L)
        val held = collector.attachedSnapshot()
        assertEquals(2, held.size)
        assertSame(first, held[0].throwable)
        assertSame(second, held[1].throwable)
        assertEquals(listOf(2L, 3L), held.map { it.atNanos })
    }

    @Test fun `parallel children registering one identity keep the first successful owner`() {
        val (collector, root) = collectorWithOwner()
        val failure = IllegalStateException()
        parallel(12) { index ->
            runBlocking(collector + SpanContext(root)) {
                span("child$index") { attachException(failure) }
            }
        }
        val first = collector.attachedSnapshot().single()
        assertTrue(first.owner.parentId == root.spanId)
        collector.spans.forEach { collector.attach(it, failure, Long.MAX_VALUE) }
        assertSame(first, collector.attachedSnapshot().single())
        val capture = Capture()
        Kotrace.install(listOf(capture))
        collector.reportTrace(TraceStatus.OK)
        assertEquals(first.owner.spanId, capture.exceptions().single().spanId)
    }

    @Test fun `concurrent first registrations of distinct failures share one registry`() {
        val (collector, owner) = collectorWithOwner()
        val failures = List(16) { IllegalStateException("failure$it") }
        parallel(failures.size) { collector.attach(owner, failures[it], it.toLong()) }
        val held = collector.attachedSnapshot()
        assertEquals(failures.size, held.size)
        failures.forEach { failure -> assertEquals(1, held.count { it.throwable === failure }) }
    }

    @Test fun `uniqueness crosses two manual roots and the argument uses the earliest root`() = runTest {
        val collector = SpanCollector()
        val failure = IllegalStateException()
        withContext(collector) {
            span("first") { attachException(failure) }
            span("second") { attachException(failure) }
        }
        val capture = Capture()
        Kotrace.install(listOf(capture))
        collector.reportTrace(TraceStatus.OK, listOf(IllegalArgumentException()))
        assertEquals(listOf("first", "first"), capture.exceptions().map { it.operation })
        assertEquals(1, collector.attachedSnapshot().size)
    }

    @Test fun `the bound refuses the hundred and first identity but never counts a held identity`() {
        val (collector, owner) = collectorWithOwner()
        val held = List(100) { IllegalStateException() }
        held.forEach { collector.attach(owner, it, 2L) }
        collector.attach(owner, held.first(), 3L)
        assertNull(owner.attributes[Span.ATTACHED_DROPPED_ATTRIBUTE])
        val refused = IllegalArgumentException()
        collector.attach(owner, refused, 4L)
        collector.attach(owner, refused, 5L)
        assertEquals("2", owner.attributes[Span.ATTACHED_DROPPED_ATTRIBUTE])
        assertEquals(100, collector.attachedSnapshot().size)
        assertTrue(collector.attachedSnapshot().none { it.throwable === refused })
    }

    @Test fun `concurrent refusals count exactly on their owner and do not retain refused identities`() {
        val (collector, owner) = collectorWithOwner()
        repeat(100) { collector.attach(owner, IllegalStateException(), 2L) }
        val refused = IllegalArgumentException()
        parallel(12) { repeat(100) { collector.attach(owner, refused, 3L) } }
        assertEquals("1200", owner.attributes[Span.ATTACHED_DROPPED_ATTRIBUTE])
        assertEquals(100, collector.attachedSnapshot().size)
        val other = Span("trace", "other", owner.spanId, "other", startNanos = 1L)
        collector.add(other)
        collector.attach(other, refused, 4L)
        assertEquals("1", other.attributes[Span.ATTACHED_DROPPED_ATTRIBUTE])
    }

    @Test fun `a registration from onReport reaches neither adapter until the next report and reports do not drain`() {
        val (collector, owner) = collectorWithOwner()
        val early = IllegalStateException("early")
        val late = IllegalStateException("late")
        collector.attach(owner, early, 2L)
        val first = Capture()
        val second = Capture()
        val registers = object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                runBlocking(collector + SpanContext(owner)) { attachException(late) }
                first.onReport(status, records)
            }
        }
        Kotrace.install(listOf(registers, second))
        collector.reportTrace(TraceStatus.OK)
        assertEquals(listOf(early), first.exceptions().map { it.throwable })
        assertEquals(first.reports, second.reports)
        collector.reportTrace(TraceStatus.OK)
        collector.reportTrace(TraceStatus.OK)
        assertEquals(listOf(early, late), first.exceptions().map { it.throwable })
        assertEquals(first.reports, second.reports)
        assertEquals(first.reports[1], first.reports[2])
    }

    @Test fun `an argument copy fault keeps the registry and tree`() = runTest {
        val (collector, owner) = collectorWithOwner()
        val capture = Capture()
        val faults = mutableListOf<Pair<FaultPhase, String?>>()
        Kotrace.install(listOf(capture))
        Kotrace.installFaultHook { phase, message, _ -> faults += phase to message }
        withContext(TraceConfig(emptyList())) { owner.log { "tree" } }
        val registered = IllegalStateException("registry")
        collector.attach(owner, registered, 2L)
        val unreadable = object : AbstractList<Throwable>() {
            override val size get() = 1
            override fun get(index: Int): Throwable = error("argument copy fault")
        }
        collector.reportTrace(TraceStatus.ERROR, unreadable)
        assertEquals(listOf(FaultPhase.REPORT to "report.attached"), faults)
        assertEquals("tree", capture.reports.single().filterIsInstance<LogRecord>().single().message)
        assertSame(registered, capture.exceptions().single().throwable)
    }

    @Test fun `tree then registration order then argument preserve owner correlation and the two clocks`() = runTest {
        val collector = SpanCollector()
        val capture = Capture()
        Kotrace.install(listOf(capture))
        lateinit var child: Span
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        val before = System.nanoTime()
        withContext(collector + dev.kotrace.ScopeContext("scope")) {
            span("root") {
                currentSpan()!!.log { "root breadcrumb" }
                span("child") {
                    child = currentSpan()!!
                    attachException(first)
                    attachException(second)
                    child.log { "child breadcrumb" }
                }
            }
        }
        val after = System.nanoTime()
        val argument = IllegalStateException("argument")
        collector.reportTrace(TraceStatus.OK, listOf(argument))
        val records = capture.reports.single()
        assertEquals(listOf("root", "child", "child", "child", "root"), records.map { it.operation })
        val exceptions = capture.exceptions()
        assertEquals(listOf(first, second, argument), exceptions.map { it.throwable })
        val registered = exceptions.take(2)
        registered.forEach {
            assertEquals(child.traceId, it.traceId)
            assertEquals(child.spanId, it.spanId)
            assertEquals(child.parentId, it.parentId)
            assertEquals("scope", it.scopeId)
            assertTrue(it.atNanos in before..after)
        }
        assertTrue(exceptions.last().atNanos >= after)
        assertEquals(collector.attachedSnapshot().map { it.atNanos }, registered.map { it.atNanos })
        assertTrue(exceptions.all { it.origin == ExceptionOrigin.ATTACHED })
        assertTrue(exceptions.all { it.toJson().contains("\"exception_origin\":\"attached\"") })
        assertFalse(exceptions.first().toJson().contains("first"))
    }

    @Test fun `both origin gates preserve attached failures and span and sensitive gates are never consulted`() {
        val (collector, owner) = collectorWithOwner()
        collector.attach(owner, IllegalStateException(), 2L)
        val seen = mutableListOf<SpanEvent>()
        val capture = Capture(object : TracePolicy {
            override val acceptsPropagatedException = false
            override val acceptsExplicitException = false
            override fun acceptsSpan(span: Span): Boolean = error("span gate must not run")
            override val acceptsSensitive: Boolean get() = error("sensitive gate must not run")
            override fun acceptsEvent(event: SpanEvent): Boolean { seen += event; return true }
        })
        Kotrace.install(listOf(capture))
        collector.reportTrace(TraceStatus.OK)
        assertEquals(ExceptionOrigin.ATTACHED, capture.exceptions().single().origin)
        assertTrue(seen.single() is ExceptionEvent)
    }

    @Test fun `acceptsEvent can reject both attachment routes`() {
        val (collector, owner) = collectorWithOwner()
        collector.attach(owner, IllegalStateException(), 2L)
        val capture = Capture(object : TracePolicy { override fun acceptsEvent(event: SpanEvent) = false })
        Kotrace.install(listOf(capture))
        collector.reportTrace(TraceStatus.OK, listOf(IllegalArgumentException()))
        assertTrue(capture.reports.single().isEmpty())
    }

    @Test fun `attached records filter owner and link attributes including the refusal count`() {
        val collector = SpanCollector()
        val owner = Span("trace", "owner", null, "owner", startNanos = 1L, links = listOf(TraceLink("linked", mapOf("safe" to "yes", "private" to "no"))))
        collector.add(owner)
        owner.putAttribute("safe", "yes")
        owner.putAttribute("private", "no")
        repeat(101) { collector.attach(owner, IllegalStateException(), 2L) }
        val filtered = Capture(object : TracePolicy {
            override fun acceptsAttribute(key: String, value: String) = key == "safe" || key == Span.ATTACHED_DROPPED_ATTRIBUTE
        })
        val closed = Capture()
        Kotrace.install(listOf(filtered, closed))
        collector.reportTrace(TraceStatus.OK)
        filtered.exceptions().forEach {
            assertEquals(mapOf("safe" to "yes", Span.ATTACHED_DROPPED_ATTRIBUTE to "1"), it.spanAttributes)
            assertEquals(mapOf("safe" to "yes"), it.links.single().attributes)
        }
        closed.exceptions().forEach {
            assertTrue(it.spanAttributes.isEmpty())
            assertTrue(it.links.single().attributes.isEmpty())
        }
    }

    @Test fun `a nested registration survives OK ERROR and CANCELLED outcomes`() = runTest {
        val capture = Capture()
        Kotrace.install(listOf(capture))
        for (status in TraceStatus.entries) {
            span("root", returnedOutcome = { _: Unit -> dev.kotrace.TraceOutcome(status) }) {
                span("child") { attachException(IllegalStateException(status.name)) }
            }
            assertEquals(status, capture.statuses.last())
            assertEquals("child", capture.exceptions().single().operation)
            assertEquals(ExceptionOrigin.ATTACHED, capture.exceptions().single().origin)
        }
    }

    @Test fun `a nested registration survives genuine cancellation of the outer coroutine`() = runTest {
        val capture = Capture()
        Kotrace.install(listOf(capture))
        val task = launch {
            val caller = currentCoroutineContext().job
            span("root") {
                span("child") { attachException(IllegalStateException("rollback")) }
                caller.cancel()
                yield()
            }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(listOf(TraceStatus.CANCELLED), capture.statuses)
        assertEquals("rollback", capture.exceptions().single().throwable.message)
        assertEquals(ExceptionOrigin.ATTACHED, capture.exceptions().single().origin)
    }

    @Test fun `a nested registration survives an outer throw without replacing the escaping failure`() = runTest {
        val capture = Capture()
        Kotrace.install(listOf(capture))
        val escaping = IllegalArgumentException("escape")
        val actual = runCatching {
            span("root") {
                span("child") { attachException(IllegalStateException("rollback")) }
                throw escaping
            }
        }.exceptionOrNull()!!
        assertTrue(generateSequence(actual) { it.cause }.any { it === escaping })
        assertEquals(listOf(TraceStatus.ERROR), capture.statuses)
        assertEquals(listOf(ExceptionOrigin.BIRTHPLACE, ExceptionOrigin.ATTACHED), capture.exceptions().map { it.origin })
        assertEquals(listOf("root", "child"), capture.exceptions().map { it.operation })
    }

    @Test fun `registry argument overlap and argument multiplicity are preserved`() {
        val (collector, owner) = collectorWithOwner()
        val failure = IllegalStateException()
        collector.attach(owner, failure, 2L)
        val capture = Capture()
        Kotrace.install(listOf(capture))
        collector.reportTrace(TraceStatus.OK, listOf(failure, failure))
        assertEquals(3, capture.exceptions().size)
        assertTrue(capture.exceptions().all { it.throwable === failure && it.origin == ExceptionOrigin.ATTACHED })
    }

    @Test fun `the registry snapshot is immutable`() {
        val (collector, owner) = collectorWithOwner()
        collector.attach(owner, IllegalStateException(), 2L)
        val copy = collector.attachedSnapshot()
        assertTrue(runCatching { (copy as MutableList).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(1, collector.attachedSnapshot().size)
    }
}
