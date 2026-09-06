package dev.kotrace

import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogRecord
import dev.kotrace.event.NamedRecord
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addException
import dev.kotrace.event.emitException
import dev.kotrace.event.emitLog
import dev.kotrace.event.emitNamed
import dev.kotrace.event.log
import dev.kotrace.event.toJson
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Span-less live fan-out ([emitLog]/[emitNamed]/[emitException]) and the [withScope] ambient `scope_id`
 * (ADR-010). Every path resolves the same config — a per-flow [TraceConfig] override if present, else the
 * process-wide [Kotrace] config — so span and span-less, suspend and non-suspend fan to the same adapters.
 * A scope stamps a correlation umbrella above `trace_id` onto every record under it, opening no reportable
 * tree.
 */
@OptIn(NonSuspendTracingBridge::class)
class SpanlessScopeTest {

    private class CollectingLive(override val policy: TracePolicy = object : TracePolicy {}) : LiveAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onLive(record: TraceRecord) { records += record }
    }

    private class CollectingReport(override val policy: TracePolicy = object : TracePolicy {}) : ReportAdapter {
        var reports = 0
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) { reports++ }
    }

    /** A policy that rejects named events named [drop]. */
    private fun dropNamed(drop: String) = object : TracePolicy {
        override fun acceptsEvent(event: SpanEvent) =
            !(event is dev.kotrace.event.NamedEvent && event.name == drop)
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    // --- Step 1: scope_id on the wire ---

    @Test fun `toJson emits scope_id only when present`() {
        val scoped = LogRecord("t", "s", null, "op", 0L, scopeId = "sess-1", info = emptyMap(), links = emptyList(), attributes = emptyMap(), message = "m")
        assertTrue("scope_id renders when set", scoped.toJson().contains("\"scope_id\":\"sess-1\""))

        val unscoped = LogRecord("t", "s", null, "op", 0L, info = emptyMap(), links = emptyList(), attributes = emptyMap(), message = "m")
        assertFalse("no scope_id key when null — today's shape", unscoped.toJson().contains("scope_id"))
    }

    // --- Step 2: the process-wide config ---

    @Test fun `install publishes the config once and reads back`() {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        assertEquals(listOf(live), Kotrace.defaultConfig()!!.liveAdapters)
    }

    @Test(expected = IllegalStateException::class)
    fun `a second install is a hard error`() {
        Kotrace.install(emptyList())
        Kotrace.install(listOf(CollectingLive()))
    }

    @Test fun `an empty config is a no-op fan-out`() {
        Kotrace.install(emptyList())
        emitNamed("orphan") // must not throw, reaches nothing
        assertTrue(Kotrace.defaultConfig()!!.liveAdapters.isEmpty())
    }

    @Test fun `install provider resolves lazily, exactly once, on first fan-out`() {
        var calls = 0
        val live = CollectingLive()
        Kotrace.install { calls++; listOf(live) }
        assertEquals("not resolved at install time", 0, calls)
        emitNamed("a")
        emitNamed("b")
        assertEquals("resolved once, memoized after", 1, calls)
        assertEquals("both emits reached the adapter", 2, live.records.size)
    }

    // --- Step 3: span-less emit verbs + exception info ---

    @Test fun `an orphan emit reaches a registered live adapter`() {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        emitNamed("signup", mapOf("plan" to "pro"))

        val rec = live.records.single() as NamedRecord
        assertEquals("signup", rec.name)
        assertNull("no trace — span-less", rec.traceId)
        assertNull("no scope outside withScope", rec.scopeId)
    }

    @Test fun `a policy that rejects the orphan drops it`() {
        val live = CollectingLive(policy = dropNamed("noise"))
        Kotrace.install(listOf(live))
        emitNamed("noise")
        emitNamed("kept")
        assertEquals(listOf("kept"), live.records.filterIsInstance<NamedRecord>().map { it.name })
    }

    @Test fun `an orphan emit with no default installed is a safe no-op`() {
        emitLog { "nobody listening" } // no install → must not throw
    }

    @Test fun `emitException carries record-level info and the raw throwable`() {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        val cause = IllegalStateException("boom")
        emitException(cause, mapOf("report" to "explicit"))

        val rec = live.records.single() as ExceptionRecord
        assertSame("the raw throwable rides the record", cause, rec.throwable)
        assertEquals("info populates ExceptionRecord.info", mapOf("report" to "explicit"), rec.info)
    }

    @Test fun `in-span addException carries info on its record too`() = runTest {
        val live = CollectingLive()
        val cause = IllegalStateException("boom")
        withContext(SpanCollector() + TraceConfig(listOf(live))) {
            span("op") { currentSpan()!!.addException(cause, mapOf("report" to "explicit")) }
        }
        val rec = live.records.filterIsInstance<ExceptionRecord>().first()
        assertSame(cause, rec.throwable)
        assertEquals("explicit", rec.info["report"])
    }

    // --- Step 4: withScope ambient scope_id ---

    @Test fun `an operation inside a scope carries both trace_id and scope_id`() = runTest {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        withScope("sess-1") {
            span("op") { currentSpan()!!.log { "inside" } }
        }
        val rec = live.records.filterIsInstance<LogRecord>().single { it.message == "inside" }
        assertEquals("op", rec.operation)
        assertTrue("carries its trace", rec.traceId != null)
        assertEquals("and the scope umbrella above it", "sess-1", rec.scopeId)
    }

    @Test fun `an orphan emit inside a scope carries scope_id only`() = runTest {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        withScope("sess-1") { emitNamed("orphan") }
        val rec = live.records.filterIsInstance<NamedRecord>().single()
        assertNull("still no trace", rec.traceId)
        assertEquals("but the scope correlates it", "sess-1", rec.scopeId)
    }

    @Test fun `a nested scope's innermost wins`() = runTest {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        withScope("outer") {
            emitNamed("a")
            withScope("inner") { emitNamed("b") }
            emitNamed("c")
        }
        val byName = live.records.filterIsInstance<NamedRecord>().associate { it.name to it.scopeId }
        assertEquals("outer", byName["a"])
        assertEquals("innermost wins, no stack", "inner", byName["b"])
        assertEquals("outer restored on exit", "outer", byName["c"])
    }

    @Test fun `outside any scope scope_id is null`() {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        emitNamed("bare")
        assertNull(live.records.filterIsInstance<NamedRecord>().single().scopeId)
    }

    // --- Step 5: policy parity, scope is not a trace ---

    @Test fun `a bare scope never produces a report fan-out`() = runTest {
        val report = CollectingReport()
        Kotrace.install(listOf(report))
        withScope("sess-1") {
            runCatching { span("op") { throw IllegalStateException("fail") } }
        }
        assertEquals("no SpanCollector, no reportTrace — live-only by design", 0, report.reports)
    }

    // --- Global config feeds every path; a context override wins ---

    @Test fun `a non-suspend root fans live through the global config`() {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        // no coroutine, no context config — the global config is the source of truth
        val span = startSpan("op")
        span.log { "off-coroutine" }
        span.end()
        val rec = live.records.filterIsInstance<LogRecord>().single { it.message == "off-coroutine" }
        assertEquals("op", rec.operation)
        assertTrue("a real span still carries its trace", rec.traceId != null)
    }

    @Test fun `a context override wins over the global config`() = runTest {
        val global = CollectingLive()
        val override = CollectingLive()
        Kotrace.install(listOf(global))
        withContext(TraceConfig(listOf(override))) {
            span("op") { currentSpan()!!.log { "scoped" } }
        }
        assertTrue("the per-flow override sees it", override.records.any { it is LogRecord })
        assertTrue("the global does not — override replaces it for this flow", global.records.isEmpty())
    }
}
