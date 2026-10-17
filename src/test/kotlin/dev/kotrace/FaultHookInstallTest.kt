package dev.kotrace

import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.event.emitLog
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The fault hook is process-wide and install-once, apart from any configuration (ADR-037): installed first, it hears
 * of a fault in the `install` provider and of faults in a flow that has no configuration at all.
 */
class FaultHookInstallTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    private class RecordingHook : FaultHook {
        val faults = mutableListOf<Pair<FaultPhase, String?>>()
        override fun onFault(phase: FaultPhase, faultMessage: String?, cause: Throwable) {
            synchronized(faults) { faults += phase to faultMessage }
        }
    }

    @Test fun `a second installFaultHook is an error, never a silent replace`() {
        Kotrace.installFaultHook(RecordingHook())

        assertThrows(IllegalStateException::class.java) { Kotrace.installFaultHook(RecordingHook()) }
    }

    @Test fun `a hook installed before install hears of a throwing provider`() {
        val hook = RecordingHook()
        Kotrace.installFaultHook(hook)
        Kotrace.install { error("dependency graph not ready") }

        emitLog { "first fan-out resolves the provider" }

        assertEquals(listOf(FaultPhase.INSTALL to "install.provider"), hook.faults)
    }

    @Test fun `with no configuration at all, a fault still reaches the hook`() = runTest {
        val hook = RecordingHook()
        Kotrace.installFaultHook(hook)

        val value = span("op", returnVerdictClassifier = { _: Int -> error("classifier bug") }) { 7 }

        assertEquals("the traced code is untouched", 7, value)
        assertEquals(listOf(FaultPhase.RETURN_VERDICT_CLASSIFIER to "span.classify"), hook.faults)
    }

    @Test fun `resetForTest clears the hook, so the next test can install its own`() {
        Kotrace.installFaultHook(RecordingHook())
        Kotrace.resetForTest()

        Kotrace.installFaultHook(RecordingHook()) // throws if the reset left the first hook in place
    }
}
