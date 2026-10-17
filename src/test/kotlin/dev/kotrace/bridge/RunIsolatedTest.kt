package dev.kotrace.bridge

import dev.kotrace.Kotrace
import dev.kotrace.StrictNotInstalledException
import dev.kotrace.TraceConfig
import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [runIsolated] is the one fault-isolation rule, for the core and for integrations (ADR-014/034/035): an ordinary
 * fault is told to the hook and replaced by `recover`'s result, a null phase keeps the hook out of it, the
 * strict-not-installed failure propagates unless it can ride an escaping throwable, and a JVM-fatal error propagates.
 */
class RunIsolatedTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    private class RecordingHook : FaultHook {
        val faults = mutableListOf<Triple<FaultPhase, String?, Throwable>>()
        override fun onFault(phase: FaultPhase, faultMessage: String?, cause: Throwable) {
            faults += Triple(phase, faultMessage, cause)
        }
    }

    @Test fun `a fault is told to the hook with its message, and recover gives the result`() = runTest {
        val hook = RecordingHook()
        val boom = IllegalArgumentException("render failed")

        Kotrace.installFaultHook(hook)
        val result = withContext(TraceConfig(emptyList())) {
            runIsolated(FaultPhase.HTTP, "http.request.log", recover = { "fallback" }) { throw boom }
        }

        assertEquals("fallback", result)
        assertEquals(Triple(FaultPhase.HTTP, "http.request.log", boom as Throwable), hook.faults.single())
    }

    @Test fun `the block's own result passes through untouched`() {
        assertEquals(42, runIsolated(FaultPhase.HTTP, recover = { -1 }) { 42 })
    }

    @Test fun `with no configuration on the thread, the process-wide hook still hears of it`() {
        val hook = RecordingHook()
        Kotrace.installFaultHook(hook)

        runIsolated(FaultPhase.DATABASE, recover = {}) { error("log failed") }

        assertEquals(listOf(FaultPhase.DATABASE), hook.faults.map { it.first })
    }

    @Test fun `a null phase keeps the hook out of it`() = runTest {
        val hook = RecordingHook()

        Kotrace.installFaultHook(hook)
        val result = withContext(TraceConfig(emptyList())) {
            runIsolated(phase = null, recover = { false }) { error("a probe that cannot answer") }
        }

        assertEquals(false, result)
        assertTrue(hook.faults.isEmpty())
    }

    @Test fun `the strict failure propagates and is never the hook's`() = runTest {
        val hook = RecordingHook()
        val strict = StrictNotInstalledException("kotrace: strict-not-installed")

        Kotrace.installFaultHook(hook)
        val thrown = withContext(TraceConfig(emptyList())) {
            runCatching { runIsolated(FaultPhase.HTTP, recover = {}) { throw strict } }.exceptionOrNull()
        }

        assertSame(strict, thrown)
        assertTrue(hook.faults.isEmpty())
    }

    @Test fun `the strict failure rides an escaping throwable instead of replacing it`() {
        val escaping = java.io.IOException("connection reset")
        val strict = StrictNotInstalledException("kotrace: strict-not-installed")
        var recovered: Throwable? = null

        runIsolated(FaultPhase.HTTP, escaping = escaping, recover = { recovered = it }) { throw strict }

        assertSame(strict, escaping.suppressed.single())
        assertSame("recover still gives the result", strict, recovered)
    }

    @Test fun `a JVM-fatal error propagates`() {
        val fatal = NoClassDefFoundError("gone")

        val thrown = assertThrows(NoClassDefFoundError::class.java) {
            runIsolated(FaultPhase.HTTP, recover = {}) { throw fatal }
        }

        assertSame(fatal, thrown)
    }

    @Test fun `with nothing installed, an armed strict check makes captureOrNull throw`() {
        @OptIn(NonSuspendTracingBridge::class)
        assertEquals("not armed: the call passes through untraced", null, TraceHandoff.captureOrNull())

        Kotrace.strictWhenNotInstalled()

        @OptIn(NonSuspendTracingBridge::class)
        val thrown = runCatching { TraceHandoff.captureOrNull() }.exceptionOrNull()
        assertTrue(thrown is StrictNotInstalledException)
    }
}
