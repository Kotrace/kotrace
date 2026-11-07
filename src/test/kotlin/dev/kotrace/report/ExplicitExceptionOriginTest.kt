package dev.kotrace.report

import dev.kotrace.Kotrace
import dev.kotrace.ReturnVerdict
import dev.kotrace.SpanCollector
import dev.kotrace.SpanStatus
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.end
import dev.kotrace.bridge.startSpan
import dev.kotrace.currentSpan
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.ExceptionOrigin
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addException
import dev.kotrace.event.toJson
import dev.kotrace.span
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExplicitExceptionOriginTest {
    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    @Test fun `default report keeps an explicit record in a failing trace with its JSON origin`() = runTest {
        val records = mutableListOf<TraceRecord>()
        Kotrace.install(listOf(report(records)))
        val handled = IllegalStateException("handled")
        val escaping = IllegalArgumentException("escaping")

        runCatching {
            span("root") {
                currentSpan()!!.addException(handled)
                throw escaping
            }
        }

        val crashes = records.filterIsInstance<ExceptionRecord>()
        assertEquals(2, crashes.size)
        val explicit = crashes.single { it.throwable === handled }
        assertEquals(ExceptionOrigin.EXPLICIT, explicit.origin)
        assertTrue(explicit.toJson().contains("\"exception_origin\":\"explicit\""))
        // runTest's stacktrace recovery may hand the report a copy of the escaping throwable, so match by message.
        assertEquals(ExceptionOrigin.BIRTHPLACE, crashes.single { it.throwable.message == escaping.message }.origin)
    }

    @Test fun `explicit gate runs before acceptsEvent and keeps the thrown birthplace and propagated copy`() = runTest {
        val records = mutableListOf<TraceRecord>()
        val accepted = mutableListOf<Throwable>()
        val policy = object : TracePolicy {
            override val acceptsExplicitException = false
            override val acceptsPropagatedException = true
            override fun acceptsEvent(event: SpanEvent): Boolean {
                if (event is ExceptionEvent) accepted += event.throwable
                return true
            }
        }
        Kotrace.install(listOf(report(records, policy)))
        val handled = IllegalStateException("handled")
        val escaping = IllegalArgumentException("escaping")

        runCatching {
            span("root") {
                currentSpan()!!.addException(handled)
                span("leaf") { throw escaping }
            }
        }

        val crashes = records.filterIsInstance<ExceptionRecord>()
        assertEquals(listOf(ExceptionOrigin.PROPAGATED, ExceptionOrigin.BIRTHPLACE), crashes.map { it.origin })
        assertEquals(listOf("root", "leaf"), crashes.map { it.operation })
        assertEquals(2, accepted.size)
        assertTrue(accepted.none { it === handled })
    }

    @Test fun `returned Failure throwable is a birthplace when explicit exceptions are disabled`() = runTest {
        val records = mutableListOf<TraceRecord>()
        Kotrace.install(
            listOf(report(records, object : TracePolicy { override val acceptsExplicitException = false })),
            returnVerdictClassifier = {
                (it as? Result<*>)?.exceptionOrNull()?.let(ReturnVerdict::Failure) ?: ReturnVerdict.Success
            },
        )

        span("root") { Result.failure<Unit>(IllegalStateException("returned")) }

        assertEquals(ExceptionOrigin.BIRTHPLACE, records.filterIsInstance<ExceptionRecord>().single().origin)
    }

    @OptIn(NonSuspendTracingBridge::class)
    @Test fun `bridge end error and attached orphan keep their origins behind the explicit gate`() {
        val records = mutableListOf<TraceRecord>()
        Kotrace.install(listOf(report(records, object : TracePolicy { override val acceptsExplicitException = false })))
        val collector = SpanCollector()
        val opened = startSpan("bridge", collector = collector)
        opened.end(SpanStatus.ERROR, IllegalStateException("bridge failure"))

        collector.reportTrace(TraceStatus.ERROR, attached = listOf(IllegalArgumentException("orphan")))

        val crashes = records.filterIsInstance<ExceptionRecord>()
        assertEquals(2, crashes.size)
        assertEquals(listOf(ExceptionOrigin.BIRTHPLACE, ExceptionOrigin.ATTACHED), crashes.map { it.origin })
    }

    private fun report(collected: MutableList<TraceRecord>, policy: TracePolicy = object : TracePolicy {}): ReportAdapter =
        object : ReportAdapter {
            override val policy = policy
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                collected += records
            }
        }
}
