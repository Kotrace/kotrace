package dev.kotrace.okhttp

import dev.kotrace.Kotrace
import dev.kotrace.SpanCollector
import dev.kotrace.SpanStatus
import dev.kotrace.TraceConfig
import dev.kotrace.TraceStatus
import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.LogRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.span
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The OkHttp bridge across the **real** dispatcher-thread boundary (ADR-023): calls are `enqueue`d, so the
 * interceptor runs on OkHttp's thread where no coroutine mirror is live. Proves the flow's captured context
 * (configuration override included) survives that hop, that instrumentation never alters the call's outcome, and that
 * each execution (a clone included) owns its own span.
 */
class OkHttpDispatcherBridgeTest {

    private val server = MockWebServer()

    @Before fun start() {
        Kotrace.resetForTest()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
        Kotrace.resetForTest()
    }

    private class CollectingLive : LiveAdapter {
        override val policy = object : TracePolicy {
            override val acceptsSensitive get() = true
        }
        val records = CopyOnWriteArrayList<TraceRecord>()
        override fun onLive(record: TraceRecord) {
            records += record
        }
    }

    private fun client(vararg extra: okhttp3.Interceptor, level: TracingInterceptor.Level = TracingInterceptor.Level.BASIC) =
        OkHttpClient.Builder()
        .addInterceptor(TracingInterceptor(level))
        .apply { extra.forEach { addInterceptor(it) } }
        .build()

    /**
     * A client whose outermost interceptor counts [done] down once [TracingInterceptor] has finished with a call
     * (span ended, exception recorded, records fanned out), so a test waits for that instead of sleeping.
     */
    private fun signallingClient(done: CountDownLatch) = OkHttpClient.Builder()
        .addInterceptor { chain -> try { chain.proceed(chain.request()) } finally { done.countDown() } }
        .addInterceptor(TracingInterceptor())
        .build()

    private fun CountDownLatch.awaitOrFail() = assertTrue("timed out waiting for the call", await(10, TimeUnit.SECONDS))

    /** Waits until the call is in flight: the server has its request. */
    private fun awaitInFlight() = assertTrue("the request never arrived", server.takeRequest(10, TimeUnit.SECONDS) != null)

    /**
     * Enqueues on OkHttp's dispatcher and blocks for the outcome: no coroutine resume, so no stacktrace-recovery
     * copy.
     */
    private fun Call.enqueueAndWait(): Result<Response> {
        val outcome = CompletableFuture<Result<Response>>()
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { outcome.complete(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) { outcome.complete(Result.success(response)) }
        })
        return outcome.get(10, TimeUnit.SECONDS)
    }

    private fun get(path: String = "/accounts") = Request.Builder().url(server.url(path)).build()

    @Test
    fun `interceptor records reach the flow's configuration override, not the process-wide one`() = runBlocking {
        val global = CollectingLive()
        val flowOverride = CollectingLive()
        Kotrace.install(listOf(global))
        server.enqueue(MockResponse().setBody("ok"))
        val calls = TracingCallFactory(client())

        withContext(Dispatchers.IO + SpanCollector() + TraceConfig(listOf(flowOverride))) {
            span("AccountRepository.fetch") { calls.newCall(get()).enqueueAndWait().getOrThrow().close() }
        }

        assertTrue(
            "the http span's breadcrumbs, emitted on the dispatcher thread, reach the flow's override",
            flowOverride.records.any { it.operation == "http GET" },
        )
        assertTrue(
            "nothing from this flow leaks to the process-wide adapter",
            global.records.none { it.operation == "http GET" },
        )
    }

    @Test
    fun `the strict-not-installed failure is loud, but never replaces the call's own exception`() = runBlocking {
        // Strict-not-installed with no configuration anywhere: every emit on this flow throws (ADR-011). The flow still
        // has a collector, so the call is traced. Strict reaches the caller like any span's would (ADR-035), but a call
        // already failing keeps its own exception, with the strict failure suppressed on it.
        Kotrace.strictWhenNotInstalled()
        server.enqueue(MockResponse().setBody("ok"))
        val boom = IOException("boom")
        var bodyClosed = false
        val closeSpy = okhttp3.Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = checkNotNull(response.body)
            val spied = object : ForwardingSource(body.source()) {
                override fun close() {
                    bodyClosed = true
                    super.close()
                }
            }
            response.newBuilder().body(spied.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
        }
        val calls = TracingCallFactory(client(closeSpy))
        val failing = TracingCallFactory(client({ throw boom }))
        val collector = SpanCollector()

        val (ok, failed) = withContext(Dispatchers.IO + collector) {
            runCatching { calls.newCall(get()).execute().close() } to failing.newCall(get()).enqueueAndWait()
        }

        val strict = ok.exceptionOrNull()
        assertTrue("a successful call surfaces the missing install", strict?.message.orEmpty().contains("strict-not-installed"))
        assertTrue("its span still ended", collector.spans.all { it.endNanos != null })
        assertTrue("the response the caller never got is closed, releasing its connection", bodyClosed)
        assertSame("the application's own exception propagates, not a telemetry one", boom, failed.exceptionOrNull())
        assertTrue(
            "the telemetry failure rides the application throwable as suppressed",
            boom.suppressed.any { it is IllegalStateException },
        )
    }

    @Test
    fun `a cloned call gets its own span, so a failed attempt is never overwritten by the retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("ok"))
        val calls = TracingCallFactory(client())
        val collector = SpanCollector()

        withContext(Dispatchers.IO + collector) {
            span("AccountRepository.fetch") {
                val first = calls.newCall(get())
                first.enqueueAndWait().getOrThrow().close()
                first.clone().enqueueAndWait().getOrThrow().close()
            }
        }

        val http = collector.spans.filter { it.name == "http GET" }
        assertEquals("one span per execution", 2, http.size)
        assertEquals(listOf(SpanStatus.ERROR, SpanStatus.OK), http.sortedBy { it.startNanos }.map { it.status })
        assertEquals(listOf("500", "200"), http.sortedBy { it.startNanos }.map { it.attributes["http.status"] })
        val parent = collector.spans.single { it.name == "AccountRepository.fetch" }
        assertTrue("both attempts are children of the calling span", http.all { it.parentId == parent.spanId })
    }

    private class SensitiveReport : ReportAdapter {
        override val policy = object : TracePolicy {
            override val acceptsSensitive get() = true
        }
        val records = CopyOnWriteArrayList<TraceRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            this.records += records
        }
    }

    @Test
    fun `BODY response snippet reaches the report after the caller has closed the body`() = runBlocking {
        // The report runs when the span ends, after the caller consumed and closed the body. The snippet must
        // already be a String by then, not a peek at a closed source that aborts the whole onReport.
        val report = SensitiveReport()
        server.enqueue(MockResponse().setBody("resp-body"))
        val calls = TracingCallFactory(client(level = TracingInterceptor.Level.BODY))

        withContext(Dispatchers.IO + TraceConfig(listOf(report))) {
            span("AccountRepository.fetch") { calls.newCall(get()).enqueueAndWait().getOrThrow().use { it.body!!.string() } }
        }

        assertTrue(
            "the debug report carries the response snippet",
            report.records.filterIsInstance<LogRecord>().any { it.message == "⇠ body resp-body" },
        )
    }

    @Test
    fun `BODY level never waits on an event stream before returning the response`() = runBlocking {
        // 1 KiB trickling at 64 bytes / 300 milliseconds: reading it takes about 5 seconds; the response itself is
        // ready at once.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: x\n\n".repeat(103))
                .throttleBody(64, 300, TimeUnit.MILLISECONDS),
        )
        val live = CollectingLive()
        val calls = TracingCallFactory(client(level = TracingInterceptor.Level.BODY))

        val tookMs = withContext(Dispatchers.IO + SpanCollector() + TraceConfig(listOf(live))) {
            val start = System.nanoTime()
            calls.newCall(get()).enqueueAndWait().getOrThrow().close()
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }

        assertTrue("the response was handed back without draining the stream (took ${tookMs} milliseconds)", tookMs < 2_000)
        assertTrue(live.records.filterIsInstance<LogRecord>().any { it.message == "⇠ body (event stream omitted)" })
    }

    @Test
    fun `BODY level never waits on an open stream of any content type`() = runBlocking {
        // NDJSON, not event-stream: no content-type rule can list every stream. 800 bytes at 32 bytes / 300
        // milliseconds take about 7 seconds to read; the response itself is ready at once, and BODY must not read ahead
        // of the caller.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("{\"a\":1}\n".repeat(100))
                .throttleBody(32, 300, TimeUnit.MILLISECONDS),
        )
        val calls = TracingCallFactory(client(level = TracingInterceptor.Level.BODY))

        val tookMs = withContext(Dispatchers.IO + SpanCollector()) {
            val start = System.nanoTime()
            calls.newCall(get()).enqueueAndWait().getOrThrow().close()
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }

        assertTrue("the response was handed back without reading the stream (took ${tookMs} milliseconds)", tookMs < 2_000)
    }

    @Test
    fun `an enqueued call under a non-W3C parent succeeds instead of failing on the dispatcher`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        val calls = TracingCallFactory(client())
        val parent = dev.kotrace.Span("4BF92F3577B34DA6A3CE929D0E0E4736", "s", null, "continued", startNanos = 0L)

        val result = withContext(Dispatchers.IO + SpanCollector() + dev.kotrace.SpanContext(parent)) {
            calls.newCall(get()).enqueueAndWait()
        }

        assertEquals("the call behaves exactly as untraced", 200, result.getOrThrow().also { it.close() }.code)
        assertEquals("and no malformed header reached the server", null, server.takeRequest().getHeader("traceparent"))
    }

    /** Retrofit's shape: a suspending call that cancels the OkHttp call when the coroutine is cancelled. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    private class Reports : ReportAdapter {
        override val policy = object : TracePolicy {}
        val statuses = CopyOnWriteArrayList<TraceStatus>()
        val exceptions = CopyOnWriteArrayList<ExceptionRecord>()
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            statuses += status
            exceptions += records.filterIsInstance<ExceptionRecord>()
        }
    }

    @Test
    fun `a call canceled with its coroutine is a cancellation, not a crash`() = runBlocking {
        server.enqueue(MockResponse().setBody("slow").setHeadersDelay(2, TimeUnit.SECONDS))
        val reports = Reports()
        val live = CollectingLive()
        val done = CountDownLatch(1)
        val calls = TracingCallFactory(signallingClient(done))

        val job = launch(Dispatchers.IO) {
            withContext(TraceConfig(listOf(live, reports))) {
                span("screen") { calls.newCall(get()).await().close() }
            }
        }
        awaitInFlight()
        job.cancelAndJoin()
        done.awaitOrFail() // the dispatcher thread ends the http span after the cancel reaches it

        assertEquals(listOf(TraceStatus.CANCELLED), reports.statuses)
        assertTrue("no IOException(\"Canceled\") birthplace", reports.exceptions.isEmpty())
        assertTrue("nor a live exception record", live.records.none { it is ExceptionRecord })
    }

    @Test
    fun `a sibling failure that cancels an in-flight call reports only the real failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("slow").setHeadersDelay(2, TimeUnit.SECONDS))
        val reports = Reports()
        val live = CollectingLive()
        val done = CountDownLatch(1)
        val calls = TracingCallFactory(signallingClient(done))

        runCatching {
            withContext(Dispatchers.IO + TraceConfig(listOf(reports, live))) {
                span("screen") {
                    coroutineScope {
                        launch { span("fetch") { calls.newCall(get()).await().close() } }
                        launch { awaitInFlight(); error("boom") }
                    }
                }
            }
        }
        done.awaitOrFail()

        assertEquals(listOf("IllegalStateException"), reports.exceptions.map { it.throwable.javaClass.simpleName })
        // The http span can end after the report froze, so only the live path proves it recorded nothing.
        assertEquals(
            listOf("IllegalStateException"),
            live.records.filterIsInstance<ExceptionRecord>().map { it.throwable.javaClass.simpleName }.distinct(),
        )
    }

    @Test
    fun `a call timeout is a failure the http span records, not a cancellation`() {
        // OkHttp enforces callTimeout by canceling the call, yet a timeout is a failure (ADR-022). Outside any span,
        // the http span is the only place it can be recorded.
        server.enqueue(MockResponse().setBody("slow").setHeadersDelay(2, TimeUnit.SECONDS))
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        // A budget large enough that the tenth the classification allows (for time spent before the span opens)
        // absorbs a slow machine.
        val http = OkHttpClient.Builder()
            .callTimeout(1, TimeUnit.SECONDS)
            .addInterceptor(TracingInterceptor())
            .build()

        val thrown = runCatching { TracingCallFactory(http).newCall(get()).execute() }.exceptionOrNull()

        assertTrue("the caller sees OkHttp's timeout, got $thrown", thrown is InterruptedIOException)
        val record = live.records.filterIsInstance<ExceptionRecord>().singleOrNull { it.operation == "http GET" }
        assertTrue("a live crash sink receives it", record != null)
        assertEquals("and can tell it was the timeout without sensitive events", "call_timeout", record!!.spanAttributes["http.outcome"])
        assertTrue(live.records.filterIsInstance<LogRecord>().any { it.message == "✗ GET /accounts: call timeout" })
    }

    @Test
    fun `a canceled call still records a failure that is not OkHttp's own cancellation`() {
        val live = CollectingLive()
        Kotrace.install(listOf(live))
        val boom = IllegalStateException("boom")
        val calls = TracingCallFactory(client({ chain -> chain.call().cancel(); throw boom }))

        val thrown = runCatching { calls.newCall(get()).execute() }.exceptionOrNull()

        assertSame(boom, thrown)
        assertTrue(
            "only an IOException can be the call's own cancellation; anything else is a failure",
            live.records.any { it is ExceptionRecord && it.throwable === boom },
        )
    }

    @Test
    fun `a sink uploading through a traced client does not feed its own uploads back to itself`() = runBlocking {
        // Offline: every upload fails, and a traced upload would record that failure and fan it out live (from
        // OkHttp's thread, past the same-thread guard) back into the sink, which would upload again, forever.
        val deadUrl = server.url("/ingest")
        server.shutdown()
        val uploads = java.util.concurrent.atomic.AtomicInteger()
        val firstUploadDone = CountDownLatch(1)
        lateinit var calls: TracingCallFactory
        val sink = object : LiveAdapter {
            override val policy = object : TracePolicy {}
            override fun onLive(record: TraceRecord) {
                if (record !is ExceptionRecord || uploads.incrementAndGet() > 50) return
                calls.newCall(Request.Builder().url(deadUrl).build()).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = firstUploadDone.countDown()
                    override fun onResponse(call: Call, response: Response) = response.close()
                })
            }
        }
        Kotrace.install(listOf(sink))
        calls = TracingCallFactory(client())

        runCatching { withContext(Dispatchers.IO) { span("screen") { error("one real crash") } } }
        // A traced upload fans its failure out from inside the call, so a loop has begun its next upload by now.
        firstUploadDone.awaitOrFail()

        assertEquals("one crash, one upload", 1, uploads.get())
    }

    @Test
    fun `a sink worker uploading through the plain client, not the traced factory, does not feed itself`() = runBlocking {
        // The worker thread is outside the live fan-out, so a call it made through TracingCallFactory would open a
        // root http span (a live adapter is installed) and feed its failure back (BACKLOG D04). The same client
        // without the factory is untagged, and the interceptor passes an untagged call straight through.
        val deadUrl = server.url("/ingest")
        server.shutdown()
        val http = client()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val uploads = java.util.concurrent.atomic.AtomicInteger()
        val firstUploadDone = CountDownLatch(1)
        val sink = object : LiveAdapter {
            override val policy = object : TracePolicy {}
            override fun onLive(record: TraceRecord) {
                if (record !is ExceptionRecord || uploads.incrementAndGet() > 50) return
                worker.execute {
                    runCatching { http.newCall(Request.Builder().url(deadUrl).build()).execute().close() }
                    firstUploadDone.countDown()
                }
            }
        }
        Kotrace.install(listOf(sink))
        // The app itself calls through TracingCallFactory(http): same client, same connection pool.

        try {
            runCatching { withContext(Dispatchers.IO) { span("screen") { error("one real crash") } } }
            // A traced upload fans its failure out on the worker, inside the call, so a loop has queued its next
            // upload by now.
            firstUploadDone.awaitOrFail()
        } finally {
            worker.shutdownNow()
        }

        assertEquals("one crash, one upload", 1, uploads.get())
    }
}
