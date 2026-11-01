package dev.kotrace.report

import dev.kotrace.Kotrace
import dev.kotrace.ReturnVerdict
import dev.kotrace.TraceOutcome
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.currentSpan
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.SpanRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addException
import dev.kotrace.event.emitException
import dev.kotrace.span
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The outcome matrix in `docs/outcome-matrix.md` is this test's output, not prose: each row runs a scenario against
 * one live adapter and three report adapters (default policy, `acceptsPropagatedException`, `acceptsExplicitException
 * = false`) and renders what each received. A behavior change that moves a cell fails here until the document is
 * regenerated with `KOTRACE_UPDATE_DOCS=1 ./gradlew :test --tests '*OutcomeMatrixTest' --rerun`, so the diff of the
 * document is the reviewable statement of the change.
 */
class OutcomeMatrixTest {
    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    private class Scenario(val name: String, val block: suspend () -> Unit)

    private val scenarios = listOf(
        Scenario("root returns normally") { span("root") {} },
        Scenario("root throws") { span("root") { throw Boom("root") } },
        Scenario("child throws, escapes the root") {
            span("root") { span("child") { throw Boom("child") } }
        },
        Scenario("child throws, root catches and recovers") {
            span("root") { runCatching { span("child") { throw Boom("child") } } }
        },
        Scenario("child throws, root catches and rethrows the same") {
            span("root") {
                try {
                    span("child") { throw Boom("child") }
                } catch (e: Boom) {
                    throw e
                }
            }
        },
        Scenario("child throws, root catches and throws a new one") {
            span("root") {
                try {
                    span("child") { throw Boom("child") }
                } catch (e: Boom) {
                    throw Boom("root", e)
                }
            }
        },
        Scenario("`addException`, root returns normally") {
            span("root") { currentSpan()!!.addException(Boom("explicit")) }
        },
        Scenario("`addException`, root throws another") {
            span("root") {
                currentSpan()!!.addException(Boom("explicit"))
                throw Boom("root")
            }
        },
        Scenario("`addException(e)`, root throws the same `e`") {
            span("root") {
                val e = Boom("same")
                currentSpan()!!.addException(e)
                throw e
            }
        },
        Scenario("`emitException` outside any span") { emitException(Boom("span-less")) },
        Scenario("root returns `Result.failure` (classifier `Failure(throwable)`)") {
            val r: Result<Unit> = span("root") { Result.failure(Boom("returned")) }
        },
        Scenario("root returns a value classified `Failure(null)`") {
            val r: String = span("root") { VALUE_ONLY_FAILURE }
        },
        Scenario("root `returnedOutcome` = OK over a returned `Result.failure`") {
            val r: Result<Unit> = span("root", returnedOutcome = { _: Result<Unit> -> TraceOutcome(TraceStatus.OK) }) {
                Result.failure(Boom("returned"))
            }
        },
        Scenario("root throws, with a `returnedOutcome` = OK") {
            span("root", returnedOutcome = { _: Unit -> TraceOutcome(TraceStatus.OK) }) { throw Boom("root") }
        },
        Scenario("root `returnedOutcome` = ERROR with an attached throwable") {
            span("root", returnedOutcome = ::failedWithAttached) {}
        },
        Scenario("child `returnedOutcome` = ERROR with an attached throwable") {
            span("root") { span("child", returnedOutcome = ::failedWithAttached) {} }
        },
        Scenario("`withTimeout` inside the root expires") {
            span("root") { withTimeout(1) { delay(1_000) } }
        },
        Scenario("active root awaits a `Deferred` cancelled elsewhere") {
            span("root") {
                val deferred = CompletableDeferred<Unit>()
                deferred.cancel()
                deferred.await()
            }
        },
        Scenario("caller's `withTimeout` around the root expires") {
            withTimeout(1) { span("root") { delay(1_000) } }
        },
    )

    @Test fun `docs outcome matrix matches what the adapters receive`() {
        val rendered = render(scenarios.map(::run))
        val doc = File("docs/outcome-matrix.md")
        val text = doc.readText()
        val start = text.indexOf(START)
        val end = text.indexOf(END)
        check(start >= 0 && end > start) { "docs/outcome-matrix.md lost its $START / $END markers" }
        val current = text.substring(start + START.length, end).trim('\n')
        if (System.getenv("KOTRACE_UPDATE_DOCS") == "1") {
            doc.writeText(text.substring(0, start + START.length) + "\n" + rendered + "\n" + text.substring(end))
            return
        }
        assertEquals(
            "docs/outcome-matrix.md drifted from kotrace's behavior; regenerate it (see this class) and review the diff",
            rendered,
            current,
        )
    }

    private class Row(
        val scenario: String,
        val status: String,
        val live: List<String>,
        val report: List<String>,
        val propagated: List<String>,
        val explicitOff: List<String>,
        val spans: List<String>,
    )

    private fun run(scenario: Scenario): Row {
        Kotrace.resetForTest()
        val live = Live()
        val default = Report(object : TracePolicy {})
        val propagated = Report(object : TracePolicy { override val acceptsPropagatedException = true })
        val explicitOff = Report(object : TracePolicy { override val acceptsExplicitException = false })
        val spans = Report(object : TracePolicy { override val acceptsSpanRecords = true })
        Kotrace.install(listOf(live, default, propagated, explicitOff, spans), returnVerdictClassifier = ::classify)
        // Virtual time: a timeout fires only when the test scheduler advances, never from DefaultExecutor's thread, so
        // the caller's timeout cannot cancel the root before it opens.
        runTest { runCatching { scenario.block() } }
        return Row(
            scenario = scenario.name,
            status = default.statuses.joinToString(", ").ifEmpty { "no report" },
            live = live.exceptions.map { it.where() },
            report = default.exceptions.map { it.where() + it.originSuffix() },
            propagated = propagated.exceptions.map { it.where() + it.originSuffix() },
            explicitOff = explicitOff.exceptions.map { it.where() + it.originSuffix() },
            spans = spans.spans.map { "${it.operation} ${it.status.name}" },
        )
    }

    private fun render(rows: List<Row>): String = buildString {
        appendLine("| Scenario | Trace status | Live exceptions | Report: default | Report: `acceptsPropagatedException` | Report: `acceptsExplicitException = false` | Report: `acceptsSpanRecords`, span statuses |")
        append("|---|---|---|---|---|---|---|")
        rows.forEach { row ->
            appendLine()
            append("| ${row.scenario} | ${row.status} | ${row.live.cell()} | ${row.report.cell()} | ")
            append("${row.propagated.cell()} | ${row.explicitOff.cell()} | ${row.spans.cell()} |")
        }
    }

    private fun List<String>.cell() = if (isEmpty()) "—" else joinToString(", ")

    // A scenario's own failure by its message; a library's (a timeout, a cancellation) by its class, whose message varies.
    private fun ExceptionRecord.where() =
        "${operation ?: "span-less"} `${if (throwable is Boom) throwable.message else throwable::class.simpleName}`"

    private fun ExceptionRecord.originSuffix() = origin?.let { " ${it.name}" }.orEmpty()

    private class Live : LiveAdapter {
        val exceptions = mutableListOf<ExceptionRecord>()
        override val policy = object : TracePolicy {}
        override fun onLive(record: TraceRecord) {
            if (record is ExceptionRecord) exceptions += record
        }
    }

    private class Report(override val policy: TracePolicy) : ReportAdapter {
        val statuses = mutableListOf<TraceStatus>()
        val exceptions = mutableListOf<ExceptionRecord>()
        val spans = mutableListOf<SpanRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            statuses += status
            records.forEach { record ->
                if (record is ExceptionRecord) exceptions += record
                if (record is SpanRecord) spans += record
            }
        }
    }

    /** A domain failure; a fixed message keeps the rendered cells stable. */
    private class Boom(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private companion object {
        const val START = "<!-- outcome-matrix:start -->"
        const val END = "<!-- outcome-matrix:end -->"
        const val VALUE_ONLY_FAILURE = "value-only failure"

        fun failedWithAttached(@Suppress("UNUSED_PARAMETER") value: Unit) =
            TraceOutcome(TraceStatus.ERROR, listOf(Boom("attached")))

        fun classify(value: Any?): ReturnVerdict = when {
            value is Result<*> && value.isFailure -> ReturnVerdict.Failure(value.exceptionOrNull())
            value == VALUE_ONLY_FAILURE -> ReturnVerdict.Failure(null)
            else -> ReturnVerdict.Success
        }
    }
}
