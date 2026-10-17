package dev.kotrace

import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.TraceRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The genuine-cancellation verdict is decided **once**, where the throwable is first seen, and the auto-root
 * reports that same verdict (ADR-022). A Job cancelled after the failure was already recorded — here by a
 * synchronous live adapter, standing in for any other thread — must not turn that failure into CANCELLED,
 * which would leave a CANCELLED trace carrying an ExceptionRecord.
 *
 * That covers the throwable the root itself sees. A nested span's `CancellationException`-typed failure that the
 * cancellation overtakes on its way up is a different case: the root is handed the Job's cancellation instead, so
 * the trace is CANCELLED and keeps the child's record (ADR-022, pinned by the last test).
 */
class CancellationVerdictTest {

    private class Outcome {
        val statuses = CopyOnWriteArrayList<TraceStatus>()
        val exceptions = CopyOnWriteArrayList<ExceptionRecord>()
    }

    /** A live adapter that cancels [job] the moment an exception is fanned out, plus a report collector. */
    private fun adapters(job: () -> Job, outcome: Outcome) = listOf(
        object : LiveAdapter {
            override val policy = object : TracePolicy {}
            override fun onLive(record: TraceRecord) {
                if (record is ExceptionRecord) job().cancel()
            }
        },
        object : ReportAdapter {
            override val policy = object : TracePolicy {}
            override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
                outcome.statuses += status
                outcome.exceptions += records.filterIsInstance<ExceptionRecord>()
            }
        },
    )

    @Test fun `a thrown failure stays ERROR when the job is cancelled after it was recorded`() = runBlocking {
        val outcome = Outcome()
        lateinit var job: Job
        job = launch(Dispatchers.Default) {
            withContext(TraceConfig(adapters({ job }, outcome))) {
                span("root") { throw CancellationException("aborted while still active") }
            }
        }
        job.join()

        assertEquals("the failure recorded while active is reported as a failure", listOf(TraceStatus.ERROR), outcome.statuses)
        assertEquals(1, outcome.exceptions.size)
    }

    @Test fun `a returned failure stays ERROR when the job is cancelled after it was recorded`() = runBlocking {
        val outcome = Outcome()
        lateinit var job: Job
        job = launch(Dispatchers.Default) {
            withContext(TraceConfig(adapters({ job }, outcome))) {
                span(
                    "root",
                    failureClassifier = { r: Result<Unit> -> r.exceptionOrNull()?.let(ReturnedFailure::CausedBy) },
                ) { Result.failure<Unit>(CancellationException("returned while still active")) }
            }
        }
        job.join()

        assertEquals(listOf(TraceStatus.ERROR), outcome.statuses)
        assertEquals(1, outcome.exceptions.size)
    }

    @Test fun `a nested failure the cancellation overtakes stays recorded under CANCELLED`() = runBlocking {
        val outcome = Outcome()
        lateinit var job: Job
        job = launch(Dispatchers.Default) {
            withContext(TraceConfig(adapters({ job }, outcome))) {
                span("screen") { span("fetch") { withTimeout(10) { delay(1_000) } } }
            }
        }
        job.join()

        assertEquals("the root is handed the Job's cancellation", listOf(TraceStatus.CANCELLED), outcome.statuses)
        assertEquals(
            "the child's timeout, recorded before the cancellation arrived, stays in the report",
            listOf("fetch"),
            outcome.exceptions.map { it.operation },
        )
    }
}
