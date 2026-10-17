package dev.kotrace

import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogEvent
import dev.kotrace.event.LogRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A report is the trace as it stood when [reportTrace] began (ADR-019/029), even though the walk itself is lazy
 * and runs when the first adapter iterates. An event appended after the report began — here by an adapter's
 * own `onReport`, before it iterates — belongs to no report, and every adapter sees the same timeline. The same
 * holds for the `attached` orphans, and a walk retried after a fault reads the same frozen timelines.
 */
class ReportSnapshotTest {

    @Test fun `events appended after the report began are not in it, whenever an adapter iterates`() = runTest {
        lateinit var held: Span
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val appendsThenReads = object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                held.log { "appended inside onReport" }
                records.filterIsInstance<LogRecord>().mapTo(first) { it.message }
            }
        }
        val readsLater = object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                records.filterIsInstance<LogRecord>().mapTo(second) { it.message }
            }
        }

        withContext(TraceConfig(listOf(appendsThenReads, readsLater))) {
            span("root") {
                held = currentSpan()!!
                held.log { "before" }
            }
        }

        assertEquals(listOf("before"), first)
        assertEquals("every adapter sees the same frozen timeline", listOf("before"), second)
    }

    @Test fun `the attached orphans are read once, when the report begins`() = runTest {
        val attached = mutableListOf<Throwable>(IllegalStateException("rollback"))
        val first = mutableListOf<String?>()
        val second = mutableListOf<String?>()
        val mutatesThenReads = object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                attached += IllegalStateException("added inside onReport")
                records.filterIsInstance<ExceptionRecord>().mapTo(first) { it.throwable.message }
            }
        }
        val readsLater = object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                records.filterIsInstance<ExceptionRecord>().mapTo(second) { it.throwable.message }
            }
        }
        val collector = SpanCollector()

        withContext(collector + TraceConfig(listOf(mutatesThenReads, readsLater))) {
            span("saga") {}
            collector.reportTrace(TraceStatus.ERROR, attached)
        }

        assertEquals(listOf("rollback"), first)
        assertEquals(listOf("rollback"), second)
    }

    @Test fun `an attached list that fails to read costs only the orphans, never the report`() = runTest {
        val unreadable = object : AbstractList<Throwable>() {
            override val size get() = 1
            override fun get(index: Int): Throwable = throw IllegalStateException("unreadable")
        }
        val faults = mutableListOf<Pair<FaultPhase, TraceAdapter?>>()
        val logs = mutableListOf<String>()
        val report = object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                records.filterIsInstance<LogRecord>().mapTo(logs) { it.message }
            }
        }
        val collector = SpanCollector()

        withContext(collector + TraceConfig(listOf(report), faultHook = { phase, adapter, _ -> faults += phase to adapter })) {
            span("saga") { currentSpan()!!.log { "step" } }
            collector.reportTrace(TraceStatus.ERROR, unreadable)
        }

        assertEquals("the span's records are still reported", listOf("step"), logs)
        assertEquals(listOf<Pair<FaultPhase, TraceAdapter?>>(FaultPhase.REPORT to null), faults)
    }

    @Test fun `a frozen timeline reads the same on every call and never sees a later append`() = runTest {
        withContext(TraceConfig(emptyList())) {
            val span = Span("t", "s", null, "root", startNanos = 0L)
            span.log { "before" }
            val frozen = FrozenTimelines(listOf(span))
            span.log { "after" }

            fun messages() = frozen.of(span).map { (it as LogEvent).message }
            assertEquals(listOf("before"), messages())
            assertEquals("a walk retried after a fault reads the same timeline", listOf("before"), messages())
        }
    }
}
