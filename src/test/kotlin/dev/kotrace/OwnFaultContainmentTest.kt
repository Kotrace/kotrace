package dev.kotrace

import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.event.LogRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A fault of kotrace's own never changes the traced outcome (ADR-034): a span that fails to open runs its block
 * untraced and tells the [FaultHook] ([FaultPhase.SPAN_OPEN]). Only the strict-not-installed failure (ADR-011)
 * still reaches the traced code.
 */
@OptIn(KotraceTestApi::class)
class OwnFaultContainmentTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    private class RecordingHook : FaultHook {
        val faults = mutableListOf<Pair<FaultPhase, Throwable>>()
        override fun onFault(phase: FaultPhase, faultMessage: String?, cause: Throwable) {
            faults += phase to cause
        }
    }

    private class CollectingReport : ReportAdapter {
        override val policy = object : TracePolicy {}
        val statuses = mutableListOf<TraceStatus>()
        val records = mutableListOf<TraceRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            statuses += status
            this.records += records
        }
    }

    private fun Throwable.chain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    /** A null value a Java caller could pass: stamping it while the span opens throws inside kotrace. */
    @Suppress("UNCHECKED_CAST")
    private val poisoned = mapOf<String, String?>("flow" to null) as Map<String, String>

    @Test fun `a root that fails to open still returns the block's value`() = runTest {
        val hook = RecordingHook()
        var runs = 0

        Kotrace.installFaultHook(hook)
        val value = withContext(TraceConfig(emptyList())) {
            span("checkout", attributes = poisoned) {
                runs++
                42
            }
        }

        assertEquals(42, value)
        assertEquals("the block runs once", 1, runs)
        assertEquals(FaultPhase.SPAN_OPEN, hook.faults.single().first)
        assertTrue(hook.faults.single().second is NullPointerException)
    }

    @Test fun `a root that fails to open rethrows the block's throwable unchanged`() = runTest {
        val hook = RecordingHook()
        val boom = IllegalStateException("declined")

        val thrown = runCatching {
            Kotrace.installFaultHook(hook)
            withContext(TraceConfig(emptyList())) {
                span<Unit>("checkout", attributes = poisoned) { throw boom }
            }
        }.exceptionOrNull()

        // Coroutine stacktrace recovery may copy it across withContext; the original is in the chain.
        assertTrue("the block's throwable propagated, not replaced", thrown!!.chain().any { it === boom })
        assertEquals(listOf(FaultPhase.SPAN_OPEN), hook.faults.map { it.first })
    }

    @Test fun `spans inside an unopened root are reported as roots`() = runTest {
        val report = CollectingReport()

        Kotrace.installFaultHook(RecordingHook())
        withContext(TraceConfig(listOf(report))) {
            span("checkout", attributes = poisoned) {
                span("pricing") { currentSpan()!!.log { "priced" } }
            }
        }

        assertEquals(listOf(TraceStatus.OK), report.statuses)
        assertEquals(listOf("pricing"), report.records.filterIsInstance<LogRecord>().map { it.operation })
    }

    @Test fun `a child that fails to open leaves its block under the parent`() = runTest {
        val hook = RecordingHook()
        val spans = collectTrace {
            Kotrace.installFaultHook(hook)
            withContext(TraceConfig(emptyList())) {
                span("parent") {
                    span("child", attributes = poisoned) { span("grandchild") {} }
                }
            }
        }

        val parent = spans.single { it.name == "parent" }
        assertEquals(listOf("parent", "grandchild"), spans.map { it.name })
        assertEquals("the unopened child's span parents under the parent", parent.spanId, spans.last().parentId)
        assertEquals(listOf(FaultPhase.SPAN_OPEN), hook.faults.map { it.first })
    }

    @Test fun `the strict-not-installed failure still reaches the traced code`() = runTest {
        Kotrace.strictWhenNotInstalled()

        val thrown = runCatching { span("op") { 42 } }.exceptionOrNull()

        assertTrue("kotrace's strict failure, not a contained fault", thrown is StrictNotInstalledException)
    }
}
