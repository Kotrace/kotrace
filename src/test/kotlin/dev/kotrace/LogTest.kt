package dev.kotrace

import dev.kotrace.event.AttributedEvent
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.LogRecord
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.NamedRecord
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.addNamed
import dev.kotrace.event.exception
import dev.kotrace.event.log
import dev.kotrace.event.toJson
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Span-scoped events attach to the active span (stored unconditionally — no capture gate, ADR-002) and fan out to adapters:
 * a [ReportAdapter] on the failure/end path (one record per [dev.kotrace.event.LogEvent] + the birthplace [dev.kotrace.event.ExceptionRecord]),
 * a [LiveAdapter] per-event as it happens (log breadcrumbs + named/analytics events + exceptions as thrown).
 *
 * Severity is a plain `"level"` attribute — kotrace holds no severity/level taxonomy; the test policy ranks
 * the strings, exactly as a consumer does.
 */
@OptIn(NonSuspendTracingBridge::class)
class LogTest {

    private val defaultLevels = setOf("INFO", "WARN", "ERROR")
    private fun lvl(level: String) = mapOf("level" to level)

    private fun testPolicy(
        accept: (Span) -> Boolean = { true },
        sensitive: Boolean = false,
        wantLevels: Set<String> = defaultLevels,
    ) = object : TracePolicy {
        override fun acceptsSpan(span: Span) = accept(span)
        override fun acceptsEvent(event: SpanEvent) =
            (event as? AttributedEvent)?.attributes?.get("level")?.let { it in wantLevels } ?: true
        override val acceptsSensitive = sensitive
    }

    private inner class CollectingReport(override val policy: TracePolicy = testPolicy()) : ReportAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            this.records += records
        }
    }

    private inner class StatusGatedReport(
        private val only: TraceStatus,
        override val policy: TracePolicy = testPolicy(),
    ) : ReportAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            if (status == only) this.records += records
        }
    }

    private inner class CollectingLive(override val policy: TracePolicy = testPolicy()) : LiveAdapter {
        val records = mutableListOf<TraceRecord>()
        override fun onLive(record: TraceRecord) {
            records += record
        }
    }

    @Test
    fun `every level is stored, and fan-out filters the ones no adapter accepts`() = runTest {
        // No capture gate (ADR-002): a filtered level is still STORED on the span; the policy drops it only
        // at fan-out. The lazy message of a filtered event is never resolved, but the event itself is kept.
        val report = CollectingReport()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            span("usecase") {
                currentSpan()?.log(lvl("INFO")) { "start" }
                currentSpan()?.log(lvl("DEBUG")) { "noisy" }
            }
            collector.reportTrace(TraceStatus.OK)
        }

        val usecase = collector.spans.first { it.name == "usecase" }
        assertEquals(
            "storage keeps every log now the capture gate is gone",
            listOf("start", "noisy"),
            usecase.events.filterIsInstance<LogEvent>().map { it.message },
        )
        assertEquals(
            "fan-out still drops the level no adapter accepts",
            listOf("start"),
            report.records.filterIsInstance<LogRecord>().map { it.message },
        )
    }

    @Test
    fun `reportTrace emits one record per event plus the birthplace exception`() = runTest {
        val report = CollectingReport()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            runCatching {
                span("usecase") {
                    currentSpan()?.log(lvl("INFO")) { "exchange code" }
                    span("repo") {
                        currentSpan()?.log(lvl("INFO")) { "store" }
                        throw IllegalStateException("Fake")
                    }
                }
            }
            collector.reportTrace(TraceStatus.ERROR)
        }

        assertEquals("one trace_id across every record", 1, report.records.map { it.traceId }.distinct().size)

        val logs = report.records.filterIsInstance<LogRecord>()
            .map { "${it.operation}:${it.attributes["level"]}:${it.message}" }
        assertTrue(logs.contains("usecase:INFO:exchange code"))
        assertTrue(logs.contains("repo:INFO:store"))

        val crash = report.records.filterIsInstance<ExceptionRecord>().single()
        assertEquals("repo", crash.operation)
        assertEquals("Fake", crash.throwable.message)

        val usecaseId = report.records.first { it.operation == "usecase" }.spanId
        val repoRecord = report.records.first { it.operation == "repo" }
        assertEquals("parent_span_id rebuilds the tree", usecaseId, repoRecord.parentId)
    }

    @Test
    @OptIn(NonSuspendTracingBridge::class)
    fun `a throwable-less ERROR leaf never shadows an ancestor crash out of the report`() = runTest {
        // Regression for the birthplace bug (ADR-005): a bridge span ended ERROR with no throwable — an
        // OkHttp 500 that returned rather than threw — is the leaf-most ERROR span but carries no
        // ExceptionEvent. The old status-only predicate made it the branch's birthplace, so its (absent)
        // exception emitted nothing while the ancestor's real throwable, no longer leaf-most, was dropped.
        val report = CollectingReport()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            runCatching {
                span("checkout") {
                    startSpan("http").end(SpanStatus.ERROR) // ERROR child, no throwable — the shadowing leaf
                    throw IllegalStateException("pricing failed") // the real crash origin, on the ancestor
                }
            }
            collector.reportTrace(TraceStatus.ERROR)
        }

        val crashes = report.records.filterIsInstance<ExceptionRecord>()
        assertEquals("the ancestor throwable reaches the report, unshadowed by the http leaf", 1, crashes.size)
        assertEquals("the crash lands on the throwable-bearing span, not the ERROR leaf", "checkout", crashes.single().operation)
        assertEquals("pricing failed", crashes.single().throwable.message)
    }

    @Test
    fun `reportable admits logs and exceptions but never a named event`() {
        assertTrue("a log breadcrumb is reportable", LogEvent(emptyMap(), { "x" }, atNanos = 0).reportable())
        assertTrue("a birthplace exception is reportable", ExceptionEvent(IllegalStateException("boom"), atNanos = 0).reportable())
        assertTrue("a named/analytics event is live-only, not reportable", !NamedEvent("payment.captured", emptyMap(), atNanos = 0).reportable())
    }

    @Test
    fun `reportTrace never routes a named event to the report, only logs and the exception`() = runTest {
        val report = CollectingReport()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            runCatching {
                span("checkout") {
                    currentSpan()?.log(lvl("INFO")) { "breadcrumb" }
                    currentSpan()?.addNamed("payment.captured", mapOf("gateway" to "stripe"))
                    throw IllegalStateException("Fake")
                }
            }
            collector.reportTrace(TraceStatus.ERROR)
        }

        assertTrue("the log breadcrumb reaches the report", report.records.any { it is LogRecord && it.message == "breadcrumb" })
        assertTrue("the birthplace exception reaches the report", report.records.any { it is ExceptionRecord })
        assertTrue("the named event is live-only — never in the report", report.records.none { it is NamedRecord })
    }

    @Test
    fun `acceptsSpan drops a layer's events but never its birthplace exception`() = runTest {
        val report = CollectingReport(testPolicy(accept = { it.attributes["layer"] != "repo" }))
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            runCatching {
                span("usecase", mapOf("layer" to "uc")) {
                    currentSpan()?.log(lvl("INFO")) { "start" }
                    span("repo", mapOf("layer" to "repo")) {
                        currentSpan()?.log(lvl("INFO")) { "store" }
                        throw IllegalStateException("Fake")
                    }
                }
            }
            collector.reportTrace(TraceStatus.ERROR)
        }

        assertTrue(
            "kept layer's event survives",
            report.records.any { it is LogRecord && it.operation == "usecase" && it.message == "start" },
        )
        assertTrue(
            "dropped layer's breadcrumb is gone",
            report.records.none { it is LogRecord && it.operation == "repo" },
        )
        val crash = report.records.filterIsInstance<ExceptionRecord>().single()
        assertEquals("birthplace exception survives the filter", "repo", crash.operation)
        assertEquals("Fake", crash.throwable.message)
    }

    @Test
    fun `startChildSpanHere registers a child under the active span into the collector`() = runTest {
        val collector = SpanCollector()
        withContext(collector) {
            span("parent") {
                startSpan("http POST /token", mapOf("layer" to "http")).end(SpanStatus.OK)
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
    fun `a sensitive event is dropped unless the policy accepts it`() = runTest {
        val plain = CollectingReport(testPolicy(sensitive = false))
        val trusted = CollectingReport(testPolicy(sensitive = true))
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(plain, trusted))) {
            runCatching {
                span("repo") {
                    currentSpan()?.log(lvl("INFO")) { "normal line" }
                    currentSpan()?.log(lvl("INFO"), sensitive = true) { "SECRET body" }
                    throw IllegalStateException("boom")
                }
            }
            collector.reportTrace(TraceStatus.ERROR)
        }

        assertTrue("normal event kept", plain.records.any { it is LogRecord && it.message == "normal line" })
        assertTrue("sensitive dropped for fail-closed policy", plain.records.none { it is LogRecord && it.message == "SECRET body" })
        assertTrue("sensitive kept for opted-in policy", trusted.records.any { it is LogRecord && it.message == "SECRET body" })
        assertTrue("birthplace exception still emitted", plain.records.any { it is ExceptionRecord })
    }

    @Test
    fun `a live adapter sees each event immediately, even on a successful trace`() = runTest {
        val live = CollectingLive()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(live))) {
            span("repo") {
                currentSpan()?.log(lvl("INFO")) { "line one" }
                currentSpan()?.log(lvl("INFO")) { "line two" }
            }
        }

        assertEquals(listOf("line one", "line two"), live.records.filterIsInstance<LogRecord>().map { it.message })
    }

    @Test
    fun `addNamed fans a NamedRecord to a live adapter, correlated to its span`() = runTest {
        val live = CollectingLive()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(live))) {
            span("checkout") {
                currentSpan()?.addNamed("payment.captured", mapOf("gateway" to "stripe"))
            }
        }

        val named = live.records.filterIsInstance<NamedRecord>().single()
        assertEquals("payment.captured", named.name)
        assertEquals("stripe", named.attributes["gateway"])
        assertEquals("correlated to the span it rode", "checkout", named.operation)
    }

    @Test
    fun `a live adapter sees the birthplace exception as it is thrown, climbing the tree without reportTrace`() = runTest {
        val live = CollectingLive()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(live))) {
            runCatching {
                span("usecase") {
                    span("repo") {
                        throw IllegalStateException("Fake")
                    }
                }
            }
        }

        val crashes = live.records.filterIsInstance<ExceptionRecord>()
        assertEquals("live sees the throwable re-recorded up the tree, deepest first — no reportTrace called", listOf("repo", "usecase"), crashes.map { it.operation })
        assertEquals("Fake", crashes.first().throwable.message)
    }

    @Test
    fun `a live policy dropping a layer still sees that layer's birthplace exception`() = runTest {
        val live = CollectingLive(testPolicy(accept = { it.attributes["layer"] != "repo" }))
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(live))) {
            runCatching {
                span("usecase", mapOf("layer" to "uc")) {
                    span("repo", mapOf("layer" to "repo")) {
                        currentSpan()?.log(lvl("INFO")) { "dropped breadcrumb" }
                        throw IllegalStateException("Fake")
                    }
                }
            }
        }

        assertTrue("repo's breadcrumb is filtered from the live watch", live.records.none { it is LogRecord && it.operation == "repo" })
        assertTrue("but repo's crash still surfaces — a layer filter never swallows a birthplace exception", live.records.any { it is ExceptionRecord && it.operation == "repo" })
    }

    @Test
    fun `report adapters self-gate on trace status`() = runTest {
        val onSuccess = StatusGatedReport(TraceStatus.OK)
        val onFailure = StatusGatedReport(TraceStatus.ERROR)
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(onSuccess, onFailure))) {
            runCatching {
                span("uc") {
                    currentSpan()?.log(lvl("INFO")) { "x" }
                    throw IllegalStateException("boom")
                }
            }
            collector.reportTrace(TraceStatus.ERROR)
        }

        assertTrue("success sink stays empty on a failed trace", onSuccess.records.isEmpty())
        assertTrue("failure sink receives the trace", onFailure.records.isNotEmpty())
    }

    @Test
    fun `LogRecord toJson emits snake_case fields and escapes the message`() {
        val record = LogRecord(
            traceId = "abc",
            spanId = "def",
            parentId = null,
            operation = "repo.store",
            atNanos = 0,
            info = emptyMap(),
            attributes = mapOf("level" to "ERROR"),
            message = "boom \"quoted\"\nnext",
        )
        assertEquals(
            "{\"trace_id\":\"abc\",\"span_id\":\"def\",\"parent_span_id\":null," +
                "\"operation\":\"repo.store\",\"kind\":\"log\"," +
                "\"message\":\"boom \\\"quoted\\\"\\nnext\",\"attributes\":{\"level\":\"ERROR\"}}",
            record.toJson(),
        )
    }

    @Test
    fun `ExceptionRecord toJson renders the class name only, never the message`() {
        val record = ExceptionRecord(
            traceId = "abc",
            spanId = "def",
            parentId = null,
            operation = "repo",
            atNanos = 0,
            info = emptyMap(),
            throwable = IllegalStateException("User quoctrung66@gmail.com not found"),
        )
        val json = record.toJson()
        assertEquals(
            "{\"trace_id\":\"abc\",\"span_id\":\"def\",\"parent_span_id\":null," +
                "\"operation\":\"repo\",\"kind\":\"exception\"," +
                "\"exception\":\"java.lang.IllegalStateException\"}",
            json,
        )
        assertFalse(
            "throwable.message (potential PII) must never reach the toJson log path",
            json.contains("quoctrung66@gmail.com"),
        )
    }

    @Test
    fun `span info surfaces on every report record and spreads into toJson`() = runTest {
        val report = CollectingReport()
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            runCatching {
                span("http", mapOf("layer" to "http")) {
                    currentSpan()!!.putInfo("http.status", "500")
                    currentSpan()?.log(lvl("INFO")) { "sent" }
                    throw IllegalStateException("Fake")
                }
            }
            collector.reportTrace(TraceStatus.ERROR)
        }

        val log = report.records.filterIsInstance<LogRecord>().single { it.operation == "http" }
        assertEquals("info rides the breadcrumb record", "500", log.info["http.status"])
        val crash = report.records.filterIsInstance<ExceptionRecord>().single()
        assertEquals("info rides the crash record too", "500", crash.info["http.status"])
        assertTrue(
            "toJson nests info under an info object",
            log.toJson().contains("\"info\":{\"http.status\":\"500\"}"),
        )
    }

    @Test
    fun `toJson nests attributes and info, so map keys never collide with identity keys`() {
        val record = LogRecord(
            traceId = "abc",
            spanId = "def",
            parentId = null,
            operation = "repo",
            atNanos = 0,
            // keys deliberately equal to a reserved identity key and to an attribute key —
            // nesting namespaces them, so none is dropped or duplicated
            info = mapOf("shared" to "info-kept", "trace_id" to "info-tid"),
            attributes = mapOf("operation" to "attr-op", "shared" to "attr-kept"),
            message = "m",
        )
        assertEquals(
            "{\"trace_id\":\"abc\",\"span_id\":\"def\",\"parent_span_id\":null," +
                "\"operation\":\"repo\",\"kind\":\"log\",\"message\":\"m\"," +
                "\"attributes\":{\"operation\":\"attr-op\",\"shared\":\"attr-kept\"}," +
                "\"info\":{\"shared\":\"info-kept\",\"trace_id\":\"info-tid\"}}",
            record.toJson(),
        )
    }

    @Test
    fun `toJson omits empty attributes and info objects`() {
        val record = LogRecord(
            traceId = "abc",
            spanId = "def",
            parentId = null,
            operation = "repo",
            atNanos = 0,
            info = emptyMap(),
            attributes = emptyMap(),
            message = "m",
        )
        assertEquals(
            "{\"trace_id\":\"abc\",\"span_id\":\"def\",\"parent_span_id\":null," +
                "\"operation\":\"repo\",\"kind\":\"log\",\"message\":\"m\"}",
            record.toJson(),
        )
    }

    @Test
    fun `acceptsSpan never sees info, so a late value cannot become a filter key`() = runTest {
        // A policy that WOULD drop the span if it could read http.status. It cannot: status is emitted
        // info, not a fixed attribute, so acceptsSpan (which reads attributes only) is blind to it — the
        // race-free invariant (ADR-001). The breadcrumb survives.
        val report = CollectingReport(testPolicy(accept = { it.attributes["http.status"] != "500" }))
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            span("http", mapOf("layer" to "http")) {
                currentSpan()!!.putInfo("http.status", "500")
                currentSpan()?.log(lvl("INFO")) { "sent" }
            }
            collector.reportTrace(TraceStatus.OK)
        }

        assertTrue(
            "acceptsSpan reads attributes only, never info — the 500 breadcrumb is kept",
            report.records.any { it is LogRecord && it.operation == "http" && it.message == "sent" },
        )
    }

    @Test
    fun `a rejected span still stores its log events, but fan-out drops them and keeps the birthplace exception`() = runTest {
        // No capture gate (ADR-002): a span the policy rejects by layer still STORES its breadcrumbs; the
        // report only hides them at fan-out. The birthplace exception has no acceptsSpan gate, so it reports.
        val report = CollectingReport(testPolicy(accept = { it.attributes["layer"] != "noise" }))
        val collector = SpanCollector()
        withContext(collector + TraceConfig(listOf(report))) {
            runCatching {
                span("usecase", mapOf("layer" to "uc")) {
                    currentSpan()?.log(lvl("INFO")) { "kept" }
                    span("noise", mapOf("layer" to "noise")) {
                        currentSpan()?.log(lvl("INFO")) { "stored but not reported" }
                        throw IllegalStateException("Fake")
                    }
                }
            }
            collector.reportTrace(TraceStatus.ERROR)
        }

        val noise = collector.spans.first { it.name == "noise" }
        assertTrue(
            "rejected span still stores its log events — storage is unconditional",
            noise.events.filterIsInstance<LogEvent>().any { it.message == "stored but not reported" },
        )
        assertTrue(
            "but fan-out drops the rejected layer's breadcrumbs",
            report.records.none { it is LogRecord && it.operation == "noise" },
        )
        assertEquals("exception has no acceptsSpan gate", "Fake", noise.exception?.message)
        val crash = report.records.filterIsInstance<ExceptionRecord>().single()
        assertEquals("birthplace crash still reports despite the dropped breadcrumbs", "noise", crash.operation)
    }
}
