package dev.kotrace

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Span-scoped logs attach to the active span, honour the capture config, and — on failure — fan out to a
 * [TraceSink] as one searchable [TraceRecord] per event, tagged so `trace_id`/`span_id` recover the flow.
 */
class LogTest {

    @After
    fun resetConfig() {
        KotraceLog.captureLevels = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
        KotraceLog.emitMode = EmitMode.OnFailure
        KotraceLog.liveSink = null
        KotraceLog.redactor = null
    }

    @Test
    fun `logs bind to the active span and drop filtered levels`() = runTest {
        KotraceLog.captureLevels = setOf(LogLevel.INFO, LogLevel.ERROR)
        val spans = collectTrace {
            trace("usecase") {
                logSpan(LogLevel.INFO) { "start" }
                logSpan(LogLevel.DEBUG) { "noisy, dropped" }
                trace("repo") {
                    logSpan(LogLevel.INFO) { "storing" }
                }
            }
        }

        val usecase = spans.first { it.name == "usecase" }
        val repo = spans.first { it.name == "repo" }
        assertEquals(listOf("start"), usecase.events.map { it.message })
        assertEquals(listOf("storing"), repo.events.map { it.message })
    }

    @Test
    fun `report fans out one record per event plus the birthplace throwable`() = runTest {
        val records = mutableListOf<TraceRecord>()
        val collector = SpanCollector()
        kotlinx.coroutines.withContext(collector) {
            runCatching {
                trace("usecase") {
                    logSpan(LogLevel.INFO) { "exchange code" }
                    trace("repo") {
                        logSpan(LogLevel.INFO) { "store" }
                        throw IllegalStateException("Fake")
                    }
                }
            }
        }
        collector.report(TraceSink { records += it })

        val traceIds = records.map { it.traceId }.distinct()
        assertEquals("one trace_id across every record", 1, traceIds.size)

        val messages = records.map { "${it.operation}:${it.level}:${it.message}" }
        assertTrue(messages.contains("usecase:INFO:exchange code"))
        assertTrue(messages.contains("repo:INFO:store"))
        val error = records.single { it.throwable != null }
        assertEquals("repo", error.operation)
        assertEquals(LogLevel.ERROR, error.level)
        assertEquals("Fake", error.message)

        val usecaseSpan = records.first { it.operation == "usecase" }
        val repoRecord = records.first { it.operation == "repo" }
        assertEquals("parent_span_id rebuilds the tree", usecaseSpan.spanId, repoRecord.parentId)
    }

    @Test
    fun `span filter drops a layer's events but never its birthplace throwable`() = runTest {
        val records = mutableListOf<TraceRecord>()
        val collector = SpanCollector()
        kotlinx.coroutines.withContext(collector) {
            runCatching {
                trace("usecase", mapOf("layer" to "uc")) {
                    logSpan(LogLevel.INFO) { "start" }
                    trace("repo", mapOf("layer" to "repo")) {
                        logSpan(LogLevel.INFO) { "store" }
                        throw IllegalStateException("Fake")
                    }
                }
            }
        }
        collector.report(TraceSink { records += it }, SpanFilter { it.attributes["layer"] != "repo" })

        assertTrue("kept layer's event survives", records.any { it.operation == "usecase" && it.message == "start" })
        assertTrue(
            "dropped layer's breadcrumb is gone",
            records.none { it.operation == "repo" && it.throwable == null },
        )
        val error = records.single { it.throwable != null }
        assertEquals("birthplace throwable survives the filter", "repo", error.operation)
        assertEquals("Fake", error.message)
    }

    @Test
    fun `startChildSpanHere registers a child under the active span into the collector`() = runTest {
        val collector = SpanCollector()
        kotlinx.coroutines.withContext(collector) {
            trace("parent") {
                startChildSpanHere("http POST /token", mapOf("layer" to "http")).endHere(SpanStatus.OK)
            }
        }

        val parent = collector.spans.first { it.name == "parent" }
        val child = collector.spans.first { it.name == "http POST /token" }
        assertEquals("child hangs off the active span", parent.spanId, child.parentId)
        assertEquals("same trace", parent.traceId, child.traceId)
        assertEquals("http", child.attributes["layer"])
        assertTrue("endHere stamped the end", child.endNanos != null)
    }

    @Test
    fun `report drops local events but keeps normal events and the throwable`() = runTest {
        val records = mutableListOf<TraceRecord>()
        val collector = SpanCollector()
        kotlinx.coroutines.withContext(collector) {
            runCatching {
                trace("repo") {
                    logSpan(LogLevel.INFO) { "normal line" }
                    logSpan(LogLevel.INFO, local = true) { "SECRET body" }
                    throw IllegalStateException("boom")
                }
            }
        }
        collector.report(TraceSink { records += it })

        assertTrue("normal event kept", records.any { it.message == "normal line" })
        assertTrue("local event never leaves the device", records.none { it.message == "SECRET body" })
        assertTrue("birthplace throwable still emitted", records.any { it.throwable != null })
    }

    @Test
    fun `live emit mode emits each event immediately, even on a successful trace`() = runTest {
        val live = mutableListOf<TraceRecord>()
        KotraceLog.emitMode = EmitMode.Live
        KotraceLog.liveSink = TraceSink { live += it }

        collectTrace {
            trace("repo") {
                logSpan(LogLevel.INFO) { "line one" }
                logSpan(LogLevel.INFO) { "line two" }
            }
        }

        assertEquals(listOf("line one", "line two"), live.map { it.message })
    }

    @Test
    fun `redactor scrubs and can drop records on report`() = runTest {
        val records = mutableListOf<TraceRecord>()
        KotraceLog.redactor = Redactor { r -> if (r.message == "drop me") null else r.copy(message = "scrubbed") }
        val collector = SpanCollector()
        kotlinx.coroutines.withContext(collector) {
            runCatching {
                trace("repo") {
                    logSpan(LogLevel.INFO) { "keep" }
                    logSpan(LogLevel.INFO) { "drop me" }
                    throw IllegalStateException("boom")
                }
            }
        }
        collector.report(TraceSink { records += it })

        assertTrue("dropped record is gone", records.none { it.message == "drop me" })
        assertTrue("kept record was scrubbed", records.any { it.message == "scrubbed" })
        assertTrue("throwable record also passed the redactor", records.any { it.throwable != null })
    }

    @Test
    fun `toJson emits snake_case fields and escapes the message`() {
        val record = TraceRecord(
            traceId = "abc",
            spanId = "def",
            parentId = null,
            operation = "repo.store",
            level = LogLevel.ERROR,
            message = "boom \"quoted\"\nnext",
            throwable = IllegalStateException("Fake"),
            atNanos = 0,
        )
        val json = record.toJson()
        assertEquals(
            "{\"trace_id\":\"abc\",\"span_id\":\"def\",\"parent_span_id\":null," +
                "\"operation\":\"repo.store\",\"level\":\"ERROR\"," +
                "\"message\":\"boom \\\"quoted\\\"\\nnext\",\"exception\":\"IllegalStateException: Fake\"}",
            json,
        )
    }
}
