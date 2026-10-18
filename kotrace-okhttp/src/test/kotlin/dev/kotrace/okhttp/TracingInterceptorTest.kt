// Tests inspect the raw span timeline (ADR-028); nothing here reaches a sink.
@file:OptIn(dev.kotrace.UnredactedTraceRead::class)

package dev.kotrace.okhttp

import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.SpanContext
import dev.kotrace.SpanStatus
import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.TraceHandoff
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.SpanEvent
import dev.kotrace.event.TraceRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Proves the interceptor opens the HTTP span from the factory's tag, puts a spec-correct `traceparent` for
 * *that* span on the wire, and reflects the response back onto it, driven here against a MockK'd chain. (The
 * real dispatcher-thread behavior is in [OkHttpDispatcherBridgeTest].)
 */
@OptIn(NonSuspendTracingBridge::class)
class TracingInterceptorTest {

    /** A request tagged the way [TracingCallFactory] tags it, capturing [collector] as the flow's context. */
    private suspend fun tagged(collector: SpanCollector, builder: Request.Builder = Request.Builder()): Request {
        val handoff = withContext(collector) { TraceHandoff.captureOrNull()!! }
        return builder
            .url("https://graph.example.com/accounts")
            .tag(HttpTrace::class.java, HttpTrace(handoff, "http GET", emptyMap()))
            .build()
    }

    /** Drives the interceptor against a MockK'd chain, capturing the request it actually sends. */
    private fun run(request: Request, code: Int, interceptor: TracingInterceptor = TracingInterceptor()): Request {
        val sent = slot<Request>()
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { proceed(capture(sent)) } answers {
                Response.Builder()
                    .request(sent.captured).protocol(Protocol.HTTP_1_1).code(code).message("msg").build()
            }
        }
        interceptor.intercept(chain)
        return sent.captured
    }

    private fun SpanCollector.httpSpan(): Span = spans.single { it.name == "http GET" }

    @Test
    fun `opens the http span on execution and injects its traceparent`() = runTest {
        val collector = SpanCollector()
        val sent = run(tagged(collector), code = 200)

        val span = collector.httpSpan()
        assertEquals("00-${span.traceId}-${span.spanId}-01", sent.header("traceparent"))
        assertEquals("200", span.attributes["http.status"])
        assertEquals(SpanStatus.OK, span.status)
    }

    @Test
    fun `a 500 marks the span ERROR and records the status`() = runTest {
        val collector = SpanCollector()
        run(tagged(collector), code = 500)

        val span = collector.httpSpan()
        assertEquals(SpanStatus.ERROR, span.status)
        assertEquals("500", span.attributes["http.status"])
    }

    @Test
    fun `a parent with a non-W3C trace id sends the request without a header, and never fails the call`() = runTest {
        // A bridge or test built the parent by hand (Span's constructor is public); the http span inherits its id.
        val collector = SpanCollector()
        val parent = Span("NOT-A-W3C-ID", "s", null, "bridge.parent", startNanos = 0L)
        val handoff = withContext(collector + SpanContext(parent)) { TraceHandoff.captureOrNull()!! }
        val request = Request.Builder().url("https://graph.example.com/accounts")
            .tag(HttpTrace::class.java, HttpTrace(handoff, "http GET", emptyMap())).build()

        val sent = run(request, code = 200)

        assertNull("no malformed header on the wire", sent.header("traceparent"))
        val span = collector.httpSpan()
        assertEquals("the call itself succeeded and the span closed", SpanStatus.OK, span.status)
        assertTrue(span.endNanos != null)
    }

    @Test
    fun `an omitted traceparent also drops one the caller set`() = runTest {
        val collector = SpanCollector()
        val parent = Span("NOT-A-W3C-ID", "s", null, "bridge.parent", startNanos = 0L)
        val handoff = withContext(collector + SpanContext(parent)) { TraceHandoff.captureOrNull()!! }
        val request = Request.Builder().url("https://graph.example.com/accounts")
            .header("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
            .tag(HttpTrace::class.java, HttpTrace(handoff, "http GET", emptyMap())).build()

        val sent = run(request, code = 200)

        assertNull("never forwards a header for some other trace", sent.header("traceparent"))
    }

    @Test
    fun `the omitted-traceparent breadcrumb respects Level NONE`() = runTest {
        val collector = SpanCollector()
        val parent = Span("NOT-A-W3C-ID", "s", null, "bridge.parent", startNanos = 0L)
        val handoff = withContext(collector + SpanContext(parent)) { TraceHandoff.captureOrNull()!! }
        val request = Request.Builder().url("https://graph.example.com/accounts")
            .tag(HttpTrace::class.java, HttpTrace(handoff, "http GET", emptyMap())).build()

        run(request, code = 200, TracingInterceptor(TracingInterceptor.Level.NONE))

        assertTrue("NONE logs nothing", collector.httpSpan().events.filterIsInstance<LogEvent>().isEmpty())
    }

    @Test
    fun `a JVM-fatal error from a breadcrumb still propagates`() = runTest {
        val fatal = object : dev.kotrace.adapter.LiveAdapter {
            override val policy = object : TracePolicy {
                override val acceptsSensitive = true
            }
            override fun onLive(record: TraceRecord): Unit = throw NoClassDefFoundError("gone")
        }
        val handoff = withContext(SpanCollector() + TraceConfig(listOf(fatal))) { TraceHandoff.captureOrNull()!! }
        val request = Request.Builder().url("https://graph.example.com/accounts")
            .tag(HttpTrace::class.java, HttpTrace(handoff, "http GET", emptyMap())).build()

        val thrown = runCatching { run(request, code = 200) }.exceptionOrNull()

        assertTrue("the fatal error is not contained", thrown is NoClassDefFoundError)
    }

    /**
     * A JVM-fatal [gone] from the response breadcrumb propagates past a response the caller never gets; its body's
     * `close()` throws [closeFailure]. Returns what the call threw and whether the body was closed.
     */
    private suspend fun failingAfterResponse(gone: Throwable, closeFailure: Throwable): Pair<Throwable?, Boolean> {
        val fatal = object : dev.kotrace.adapter.LiveAdapter {
            override val policy = object : TracePolicy {
                override val acceptsSensitive = true
            }
            override fun onLive(record: TraceRecord): Unit = throw gone
        }
        val handoff = withContext(SpanCollector() + TraceConfig(listOf(fatal))) { TraceHandoff.captureOrNull()!! }
        val request = Request.Builder().url("https://graph.example.com/accounts")
            .tag(HttpTrace::class.java, HttpTrace(handoff, "http GET", emptyMap())).build()
        var closed = false
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = 0L
            override fun source(): BufferedSource = Buffer()
            override fun close() {
                closed = true
                throw closeFailure
            }
        }
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { proceed(any()) } answers {
                Response.Builder().request(firstArg()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body).build()
            }
        }
        return runCatching { TracingInterceptor().intercept(chain) }.exceptionOrNull() to closed
    }

    @Test
    fun `a response the caller never gets is closed, its close failure suppressed on the propagating one`() = runTest {
        val gone = NoClassDefFoundError("gone")
        val closeFailure = IOException("close failed")

        val (thrown, closed) = failingAfterResponse(gone, closeFailure)

        assertTrue("the body is closed, releasing its connection", closed)
        assertSame(gone, thrown)
        assertTrue(gone.suppressed.contains(closeFailure))
    }

    @Test
    fun `a close failure that is the propagating failure itself never replaces it`() = runTest {
        // Suppressing an exception on itself throws IllegalArgumentException instead.
        val gone = NoClassDefFoundError("gone")

        val (thrown, closed) = failingAfterResponse(gone, closeFailure = gone)

        assertTrue(closed)
        assertSame(gone, thrown)
    }

    @Test
    fun `a request with no tag passes through without a header`() {
        val sent = run(Request.Builder().url("https://graph.example.com/accounts").build(), code = 200)

        assertNull("no trace, no traceparent", sent.header("traceparent"))
    }

    /** Drives the interceptor against a call OkHttp canceled mid-flight, whose call-timeout budget is [callTimeoutNanos]. */
    private fun runCanceled(request: Request, callTimeoutNanos: Long): Throwable? {
        val canceledCall = mockk<Call> {
            every { isCanceled() } returns true
            every { timeout() } returns Timeout().timeout(callTimeoutNanos, TimeUnit.NANOSECONDS)
        }
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { call() } returns canceledCall
            every { proceed(any()) } throws IOException("Canceled")
        }
        return runCatching { TracingInterceptor().intercept(chain) }.exceptionOrNull()
    }

    @Test
    fun `a call its caller canceled records no exception, and says so in http outcome`() = runTest {
        val collector = SpanCollector()
        val thrown = runCanceled(tagged(collector), callTimeoutNanos = 0) // no callTimeout: the caller canceled it

        assertTrue("the call's own exception still reaches the caller", thrown is IOException)
        val span = collector.httpSpan()
        assertEquals(SpanStatus.ERROR, span.status)
        assertTrue("a cancellation is not a crash", span.events.none { it is ExceptionEvent })
        assertEquals("canceled", span.attributes["http.outcome"])
    }

    @Test
    fun `a call whose callTimeout fired records the exception, and says so in http outcome`() = runTest {
        val collector = SpanCollector()
        runCanceled(tagged(collector), callTimeoutNanos = 1) // a budget already spent: the timeout canceled it

        val span = collector.httpSpan()
        assertTrue("a timeout is a failure", span.events.any { it is ExceptionEvent })
        assertEquals("call_timeout", span.attributes["http.outcome"])
    }

    /** A no-op report adapter, present only so a `TraceConfig` is in scope; storage is unconditional (no capture gate, ADR-002). */
    private val captureDebug = object : ReportAdapter {
        override val policy = object : TracePolicy {
            override fun acceptsEvent(event: SpanEvent) = true
        }
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) = Unit
    }

    @Test
    fun `captures request and response bodies as sensitive events when enabled`() = runTest {
        val collector = SpanCollector()
        val request = withContext(TraceConfig(listOf(captureDebug))) {
            tagged(collector, Request.Builder().post("req-secret".toRequestBody(null)))
        }
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { proceed(any()) } answers {
                Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                    .body("resp-secret".toResponseBody(null)).build()
            }
        }

        val body = TracingInterceptor(TracingInterceptor.Level.BODY).intercept(chain).use { it.body!!.string() }

        assertEquals("the caller reads the body untouched", "resp-secret", body)
        val logs = collector.httpSpan().events.filterIsInstance<LogEvent>()
        val sensitiveEvents = logs.filter { it.sensitive }
        assertTrue("request body captured", sensitiveEvents.any { it.message.contains("req-secret") })
        assertTrue("response body captured", sensitiveEvents.any { it.message.contains("resp-secret") })
        assertTrue("every body event is sensitive (never reported)", logs.filter { it.message.contains("secret") }.all { it.sensitive })
    }

    /**
     * A request body that counts what it writes and refuses a second write when [oneShot] / [duplex] says so. It
     * writes [length] bytes but declares [declaredLength] (`-1` = unknown, as a streamed body would).
     */
    private class CountingBody(
        private val length: Long,
        private val oneShot: Boolean = false,
        private val duplex: Boolean = false,
        private val declaredLength: Long = length,
    ) : RequestBody() {
        var written = 0L
        override fun contentType(): MediaType? = null
        override fun contentLength() = declaredLength
        override fun isOneShot() = oneShot
        override fun isDuplex() = duplex
        override fun writeTo(sink: BufferedSink) {
            check(!oneShot && !duplex) { "the snippet must never write a one-shot or duplex body" }
            val chunk = ByteArray(8 * 1024) { 'a'.code.toByte() }
            var left = length
            while (left > 0) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                sink.write(chunk, 0, n)
                written += n
                left -= n
            }
        }
    }

    private suspend fun bodyLogs(body: RequestBody, bodyLimit: Long = 1024): List<LogEvent> {
        val collector = SpanCollector()
        run(tagged(collector, Request.Builder().post(body)), code = 200, TracingInterceptor(TracingInterceptor.Level.BODY, bodyLimit))
        return collector.httpSpan().events.filterIsInstance<LogEvent>().filter { it.message.startsWith("⇢ body") }
    }

    @Test
    fun `BODY level never writes a request body whose declared length exceeds bodyLimit`() = runTest {
        // Declared-length gate, not just the cap: okio stages a single large write (a ByteArray body) whole
        // before any sink below it could stop it.
        val upload = CountingBody(length = 16L * 1024 * 1024)

        val snippet = bodyLogs(upload, bodyLimit = 1024).single().message

        assertEquals("not written at all", 0L, upload.written)
        assertEquals("⇢ body (body omitted, 16777216 bytes)", snippet)
    }

    @Test
    fun `BODY level stops an unknown-length request body at bodyLimit`() = runTest {
        val stream = CountingBody(length = 16L * 1024 * 1024, declaredLength = -1)

        val snippet = bodyLogs(stream, bodyLimit = 1024).single().message

        assertTrue("stopped at the cap, not the whole 16 MiB: wrote ${stream.written}", stream.written <= 64 * 1024)
        assertEquals("exactly bodyLimit bytes of content, marked truncated", "⇢ body " + "a".repeat(1024) + "… (truncated)", snippet)
    }

    @Test
    fun `BODY level omits a one-shot or duplex request body instead of consuming it`() = runTest {
        assertEquals("⇢ body (one-shot body omitted)", bodyLogs(CountingBody(10, oneShot = true)).single().message)
        assertEquals("⇢ body (duplex body omitted)", bodyLogs(CountingBody(10, duplex = true)).single().message)
    }

    @Test
    fun `a body that fits is logged whole, with no truncation mark`() = runTest {
        assertEquals("⇢ body " + "a".repeat(1024), bodyLogs(CountingBody(1024), bodyLimit = 1024).single().message)
    }

    @Test
    fun `bodyLimit must not be negative`() {
        assertThrows(IllegalArgumentException::class.java) { TracingInterceptor(TracingInterceptor.Level.BODY, bodyLimit = -1) }
    }

    /** Runs a BODY-level interceptor over a MockK'd chain answering [responseBody]; returns the caller's response. */
    private suspend fun bodyResponse(collector: SpanCollector, responseBody: String, bodyLimit: Long = 1024): Response {
        val request = tagged(collector)
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { proceed(any()) } answers {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                    .body(responseBody.toResponseBody(null)).build()
            }
        }
        return TracingInterceptor(TracingInterceptor.Level.BODY, bodyLimit).intercept(chain)
    }

    private fun SpanCollector.responseBodyLogs() =
        httpSpan().events.filterIsInstance<LogEvent>().filter { it.message.startsWith("⇠ body") }.map { it.message }

    @Test
    fun `the response snippet is copied as the caller reads, leaving the body intact`() = runTest {
        val collector = SpanCollector()
        val full = "b".repeat(20 * 1024)

        val read = bodyResponse(collector, full).use { it.body!!.string() }

        assertEquals("the caller gets every byte", full, read)
        assertEquals(listOf("⇠ body " + "b".repeat(1024) + "… (truncated, 20480 bytes)"), collector.responseBodyLogs())
    }

    @Test
    fun `a response body the caller never reads logs no snippet`() = runTest {
        val collector = SpanCollector()

        bodyResponse(collector, "never read").close()

        assertTrue(collector.responseBodyLogs().isEmpty())
    }

    @Test
    fun `a response body closed part-way is marked partial only when provably so`() = runTest {
        // 20 KiB with a Content-Length: one 4-byte read pulls a single segment, well short of the declared length.
        val known = SpanCollector()
        bodyResponse(known, "c".repeat(20 * 1024), bodyLimit = 32 * 1024).use { it.body!!.source().readUtf8(4) }
        assertTrue(known.responseBodyLogs().single().endsWith("… (read partially)"))

        // A converter that stops at the end of the JSON without reading EOF: every declared byte was seen, so no
        // partial mark.
        val whole = SpanCollector()
        bodyResponse(whole, "{\"a\":1}").use { it.body!!.source().readUtf8(7) }
        assertEquals(listOf("⇠ body {\"a\":1}"), whole.responseBodyLogs())
    }
}
