package dev.kotrace

import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.end
import dev.kotrace.bridge.startSpan
import dev.kotrace.event.NamedRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.emitLog
import dev.kotrace.event.emitNamed
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The strict-not-installed opt-in (ADR-011): when [Kotrace.strictWhenNotInstalled] is armed, a fan-out that
 * resolves **no** configuration (nothing installed and no per-flow [TraceConfig] override) is a hard error instead
 * of ADR-010's silent no-op. Off by default, so release keeps the no-op; armed, it turns "forgot to install"
 * and "emitted before install" loud at the first offending emit. A deliberate `install(emptyList())` is a
 * real (non-null) configuration, so it never trips the check.
 */
@OptIn(NonSuspendTracingBridge::class)
class StrictNotInstalledTest {

    private class CollectingLive(override val policy: TracePolicy = object : TracePolicy {}) : LiveAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onLive(record: TraceRecord) { records += record }
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    // --- off by default: ADR-010 no-op preserved ---

    @Test fun `not armed and no install, so an orphan emit is a safe no-op, no throw`() {
        emitLog { "nobody listening" }
        emitNamed("orphan")
    }

    // --- armed: a null resolution is a hard error ---

    @Test fun `armed with no install, an orphan emit throws`() {
        Kotrace.strictWhenNotInstalled()
        val ex = assertThrows(IllegalStateException::class.java) { emitNamed("orphan") }
        assertTrue("message points at the wiring fix", ex.message!!.contains("ADR-011"))
    }

    @Test fun `armed with no install, a span-scoped emit off-coroutine also throws`() {
        Kotrace.strictWhenNotInstalled()
        // A real span exists, but with no installed configuration the log's fan-out resolves null → strict throws.
        val span = startSpan("op")
        assertThrows(IllegalStateException::class.java) { span.log { "off-coroutine" } }
    }

    @Test fun `armed with no install, end(ERROR, e) keeps the application throwable, telemetry failure suppressed`() {
        Kotrace.strictWhenNotInstalled()
        val span = startSpan("op")
        val boom = java.io.IOException("boom")

        span.end(SpanStatus.ERROR, boom) // must not throw: the caller's own `throw boom` follows (ADR-023)

        assertEquals("the span still completes", SpanStatus.ERROR, span.status)
        assertTrue(
            "the strict failure rides the application throwable instead of replacing it",
            boom.suppressed.single() is IllegalStateException,
        )
    }

    // --- armed: install (or a context override) satisfies the check ---

    @Test fun `armed, then installed, emits fan out normally, no throw`() {
        Kotrace.strictWhenNotInstalled()
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        emitNamed("signup")
        assertEquals(listOf("signup"), live.records.filterIsInstance<NamedRecord>().map { it.name })
    }

    @Test fun `armed with install(emptyList), a deliberate disable is a real configuration, no throw`() {
        Kotrace.strictWhenNotInstalled()
        Kotrace.install(emptyList())
        emitNamed("orphan") // non-null empty configuration → no-op, but NOT a strict error
        assertTrue(Kotrace.defaultConfig()!!.liveAdapters.isEmpty())
    }

    @Test fun `armed with no global but a per-flow override present, the override satisfies the check`() = runTest {
        Kotrace.strictWhenNotInstalled()
        val override = CollectingLive()
        withContext(TraceConfig(listOf(override))) { emitNamed("scoped") }
        assertTrue("the override sees it, no throw", override.records.any { it is NamedRecord })
    }

    // --- the ordering bug: emit-before-install ---

    @Test fun `once armed, an emit before install throws and one after install succeeds`() {
        Kotrace.strictWhenNotInstalled()
        assertThrows(IllegalStateException::class.java) { emitNamed("too-early") }

        val live = CollectingLive()
        Kotrace.install(listOf(live))
        emitNamed("on-time")
        assertEquals(listOf("on-time"), live.records.filterIsInstance<NamedRecord>().map { it.name })
    }

    // --- latch semantics ---

    @Test fun `arming is idempotent, so a second arm is not an error`() {
        Kotrace.strictWhenNotInstalled()
        Kotrace.strictWhenNotInstalled()
        assertThrows(IllegalStateException::class.java) { emitNamed("orphan") }
    }

    @Test fun `resetForTest clears the strict latch`() {
        Kotrace.strictWhenNotInstalled()
        Kotrace.resetForTest()
        emitNamed("orphan") // latch cleared → back to the safe no-op
    }
}
