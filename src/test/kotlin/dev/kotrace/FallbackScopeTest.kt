package dev.kotrace

import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogRecord
import dev.kotrace.event.NamedRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.emitException
import dev.kotrace.event.emitLog
import dev.kotrace.event.emitNamed
import dev.kotrace.event.log
import dev.kotrace.event.toJson
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The process-wide fallback scope (ADR-038): `Kotrace.install(fallbackScopeId = …)` gives every record emitted
 * outside any [withScope] a `scope_id`, so no record is unjoinable. An ambient [withScope] always wins; the provider
 * is read at each resolution; a throwing one costs the scope, never the record or the traced outcome (ADR-034).
 */
class FallbackScopeTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    private class CollectingLive : LiveAdapter {
        override val policy = object : TracePolicy {}
        val records = CopyOnWriteArrayList<TraceRecord>()
        override fun onLive(record: TraceRecord) {
            records += record
        }
    }

    private class RecordingHook : FaultHook {
        val faults = CopyOnWriteArrayList<Pair<FaultPhase, String?>>()
        override fun onFault(phase: FaultPhase, faultMessage: String?, cause: Throwable) {
            faults += phase to faultMessage
        }
    }

    private fun CollectingLive.named(name: String): NamedRecord =
        records.filterIsInstance<NamedRecord>().single { it.name == name }

    private fun CollectingLive.logged(message: String): LogRecord =
        records.filterIsInstance<LogRecord>().single { it.message == message }

    @Test fun `a span-less emit outside any scope carries the fallback scope`() {
        val live = CollectingLive()
        Kotrace.install(listOf(live), fallbackScopeId = { "session-1" })

        emitLog { "app started" }
        emitException(IllegalStateException("boom"))

        val log = live.logged("app started")
        assertNull("still no trace: span-less", log.traceId)
        assertEquals("session-1", log.scopeId)
        assertEquals("session-1", live.records.filterIsInstance<ExceptionRecord>().single().scopeId)
        assertTrue("the wire carries it as scope_id", log.toJson().contains("\"scope_id\":\"session-1\""))
    }

    @Test fun `an auto-root span and its children outside any scope carry the fallback scope`() = runTest {
        val live = CollectingLive()
        Kotrace.install(listOf(live), fallbackScopeId = { "session-1" })

        span("checkout") {
            currentSpan()!!.log { "root" }
            span("pay") { currentSpan()!!.log { "child" } }
        }

        assertEquals("session-1", live.logged("root").scopeId)
        assertEquals("session-1", live.logged("child").scopeId)
        assertEquals("one trace under the scope", live.logged("root").traceId, live.logged("child").traceId)
    }

    @Test fun `an ambient scope wins, and the fallback applies again once it exits`() = runTest {
        val live = CollectingLive()
        Kotrace.install(listOf(live), fallbackScopeId = { "session-1" })

        withScope("journey-7") {
            emitNamed("in_journey")
            withScope("step-2") { emitNamed("in_step") }
            span("op") { currentSpan()!!.log { "span in journey" } }
        }
        emitNamed("after_journey")

        assertEquals("journey-7", live.named("in_journey").scopeId)
        assertEquals("a nested scope overrides as before", "step-2", live.named("in_step").scopeId)
        assertEquals("journey-7", live.logged("span in journey").scopeId)
        assertEquals("session-1", live.named("after_journey").scopeId)
    }

    @Test fun `the provider is read at each resolution, so rotating it changes later records only`() {
        val live = CollectingLive()
        var session = "session-1"
        Kotrace.install(listOf(live), fallbackScopeId = { session })

        emitNamed("before")
        session = "session-2"
        emitNamed("after")

        assertEquals("session-1", live.named("before").scopeId)
        assertEquals("session-2", live.named("after").scopeId)
    }

    @Test fun `a provider returning null leaves the record without a scope`() {
        val live = CollectingLive()
        Kotrace.install(listOf(live), fallbackScopeId = { null })

        emitNamed("no_session_yet")

        assertNull(live.named("no_session_yet").scopeId)
    }

    @Test fun `a throwing provider costs the span-less record its scope, never the record`() {
        val hook = RecordingHook()
        Kotrace.installFaultHook(hook)
        val live = CollectingLive()
        Kotrace.install(listOf(live), fallbackScopeId = { error("session store not ready") })

        emitNamed("orphan")

        assertNull("still delivered, with no scope", live.named("orphan").scopeId)
        assertEquals(listOf(FaultPhase.SPANLESS_LIVE to "scope.fallback"), hook.faults.toList())
    }

    @Test fun `a throwing provider never changes the traced outcome`() = runTest {
        val hook = RecordingHook()
        Kotrace.installFaultHook(hook)
        val live = CollectingLive()
        Kotrace.install(listOf(live), fallbackScopeId = { error("session store not ready") })

        val result = span("op") {
            currentSpan()!!.log { "traced" }
            42
        }

        assertEquals("the block returns as it would untraced", 42, result)
        assertNull(live.logged("traced").scopeId)
        assertEquals(listOf(FaultPhase.SPAN_OPEN to "scope.fallback"), hook.faults.toList())
    }

    @Test fun `a record the provider emits takes no fallback rather than re-entering it`() = runTest {
        val live = CollectingLive()
        Kotrace.install(listOf(live), fallbackScopeId = {
            emitNamed("session_read")
            "session-1"
        })

        span("op") { currentSpan()!!.log { "traced" } }

        assertNull(live.named("session_read").scopeId)
        assertEquals("session-1", live.logged("traced").scopeId)
    }

    @Test fun `with nothing installed there is no fallback`() = runTest {
        val live = CollectingLive()

        withContext(TraceConfig(listOf(live))) {
            span("op") { currentSpan()!!.log { "traced" } }
        }

        assertNull(live.logged("traced").scopeId)
    }

    @Test fun `a per-flow configuration override keeps the installed fallback`() = runTest {
        val global = CollectingLive()
        val flowOverride = CollectingLive()
        Kotrace.install(listOf(global), fallbackScopeId = { "session-1" })

        withContext(TraceConfig(listOf(flowOverride))) {
            span("op") { currentSpan()!!.log { "traced" } }
            emitNamed("orphan")
        }

        assertEquals("correlation is not a sink choice", "session-1", flowOverride.logged("traced").scopeId)
        assertEquals("session-1", flowOverride.named("orphan").scopeId)
        assertTrue(global.records.isEmpty())
    }

    @Test fun `the provider overload sets the fallback at install, before the adapters resolve`() {
        val live = CollectingLive()
        Kotrace.install(fallbackScopeId = { "session-1" }) { listOf(live) }

        emitNamed("first")

        assertEquals("session-1", live.named("first").scopeId)
    }

    @Test fun `the ambient readers never return the fallback`() = runTest {
        Kotrace.install(emptyList(), fallbackScopeId = { "session-1" })

        assertNull(currentScopeId())
        assertNull(currentThreadScopeId())
    }

    @Test fun `resetForTest clears the fallback, and a second install still throws`() {
        Kotrace.install(emptyList(), fallbackScopeId = { "session-1" })
        assertThrows(IllegalStateException::class.java) { Kotrace.install(emptyList(), fallbackScopeId = { "session-2" }) }

        Kotrace.resetForTest()
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        emitNamed("after_reset")

        assertNull(live.named("after_reset").scopeId)
    }
}
