package dev.kotrace

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
 * The strict-uninstalled opt-in (ADR-011): when [Kotrace.strictWhenUninstalled] is armed, a fan-out that
 * resolves **no** config — nothing installed and no per-flow [TraceConfig] override — is a hard error instead
 * of ADR-010's silent no-op. Off by default, so release keeps the no-op; armed, it turns "forgot to install"
 * and "emitted before install" loud at the first offending emit. A deliberate `install(emptyList())` is a
 * real (non-null) config, so it never trips the check.
 */
@OptIn(NonSuspendTracingBridge::class)
class StrictUninstalledTest {

    private class CollectingLive(override val policy: TracePolicy = object : TracePolicy {}) : LiveAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onLive(record: TraceRecord) { records += record }
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    // --- off by default: ADR-010 no-op preserved ---

    @Test fun `not armed, no install — an orphan emit is a safe no-op, no throw`() {
        emitLog { "nobody listening" }
        emitNamed("orphan")
    }

    // --- armed: a null resolution is a hard error ---

    @Test fun `armed, no install — an orphan emit throws`() {
        Kotrace.strictWhenUninstalled()
        val ex = assertThrows(IllegalStateException::class.java) { emitNamed("orphan") }
        assertTrue("message points at the wiring fix", ex.message!!.contains("ADR-011"))
    }

    @Test fun `armed, no install — a span-scoped emit off-coroutine also throws`() {
        Kotrace.strictWhenUninstalled()
        // A real span exists, but with no installed config the log's fan-out resolves null → strict throws.
        val span = startSpan("op")
        assertThrows(IllegalStateException::class.java) { span.log { "off-coroutine" } }
    }

    // --- armed: install (or a context override) satisfies the check ---

    @Test fun `armed, then install — emits fan out normally, no throw`() {
        Kotrace.strictWhenUninstalled()
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        emitNamed("signup")
        assertEquals(listOf("signup"), live.records.filterIsInstance<NamedRecord>().map { it.name })
    }

    @Test fun `armed, install(emptyList) — deliberate disable is a real config, no throw`() {
        Kotrace.strictWhenUninstalled()
        Kotrace.install(emptyList())
        emitNamed("orphan") // non-null empty config → no-op, but NOT a strict error
        assertTrue(Kotrace.defaultConfig()!!.liveAdapters.isEmpty())
    }

    @Test fun `armed, no global, but a per-flow override present — override satisfies the check`() = runTest {
        Kotrace.strictWhenUninstalled()
        val override = CollectingLive()
        withContext(TraceConfig(listOf(override))) { emitNamed("scoped") }
        assertTrue("the override sees it, no throw", override.records.any { it is NamedRecord })
    }

    // --- the ordering bug: emit-before-install ---

    @Test fun `armed — an emit before install throws, one after install succeeds`() {
        Kotrace.strictWhenUninstalled()
        assertThrows(IllegalStateException::class.java) { emitNamed("too-early") }

        val live = CollectingLive()
        Kotrace.install(listOf(live))
        emitNamed("on-time")
        assertEquals(listOf("on-time"), live.records.filterIsInstance<NamedRecord>().map { it.name })
    }

    // --- latch semantics ---

    @Test fun `arming is idempotent — a second arm is not an error`() {
        Kotrace.strictWhenUninstalled()
        Kotrace.strictWhenUninstalled()
        assertThrows(IllegalStateException::class.java) { emitNamed("orphan") }
    }

    @Test fun `resetForTest clears the strict latch`() {
        Kotrace.strictWhenUninstalled()
        Kotrace.resetForTest()
        emitNamed("orphan") // latch cleared → back to the safe no-op
    }
}
