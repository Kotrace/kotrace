package dev.kotrace.demo

import com.sun.net.httpserver.HttpServer
import dev.kotrace.KotraceLog
import dev.kotrace.LogLevel
import dev.kotrace.SpanCollector
import dev.kotrace.TRACEPARENT_HEADER
import dev.kotrace.birthplaceSpan
import dev.kotrace.formatTree
import dev.kotrace.logSpan
import dev.kotrace.okhttp.TracingCallFactory
import dev.kotrace.okhttp.TracingInterceptor
import dev.kotrace.report
import dev.kotrace.toJson
import dev.kotrace.trace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * A self-contained tour of kotrace. Models a mobile "checkout" flow as a tree of [trace] spans, logs a
 * few [logSpan] lines along the way, fans a couple of spans out in parallel, and makes one **real**
 * OkHttp call whose `traceparent` is captured by a throwaway in-process server — proving the
 * client↔backend stitch with no OpenTelemetry anywhere.
 *
 * On failure it renders two views of the same trace:
 * - [formatTree] — the indented human read, span logs interleaved;
 * - [report] — the searchable fan-out: one `TraceRecord` per log line + the birthplace throwable, each
 *   tagged with `trace_id` / `span_id` / `parent_span_id`, so a backend can group by either.
 *
 * Run: `./gradlew :demo:run -q`
 */
fun main() = runBlocking<Unit> {
    // Capture INFO and up; DEBUG lines (e.g. the SQL-ish detail below) are dropped by this config.
    KotraceLog.captureLevels = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)

    // A throwaway backend that records the `traceparent` header of whatever hits it, then 200s.
    val seenTraceparent = AtomicReference<String?>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/pricing") { exchange ->
            seenTraceparent.set(exchange.requestHeaders.getFirst(TRACEPARENT_HEADER))
            val body = "{\"price\":1200}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    val pricingUrl = "http://127.0.0.1:${server.address.port}/pricing"

    // TracingCallFactory tags the request from the coroutine thread; TracingInterceptor writes the
    // traceparent on OkHttp's thread. Both inert without an active span — safe to install on every client.
    val http = OkHttpClient.Builder().addInterceptor(TracingInterceptor()).build()
    val calls: Call.Factory = TracingCallFactory(http)

    // Drive the whole flow under one collector so we keep the reference for `report`/`birthplaceSpan`.
    // runCatching swallows the failure here so we can render — a real reporter reads the collector at the
    // report site and lets the throwable propagate.
    val collector = SpanCollector()
    withContext(collector) {
        runCatching { checkout(calls, pricingUrl) }
    }

    server.stop(0)

    println("── formatTree (human read) ──")
    println(collector.spans.formatTree())

    println("── report (searchable fan-out — one JSON line per record, ELK-ingestable) ──")
    collector.report { r -> println(r.toJson()) }

    println()
    println("traceparent seen by backend: ${seenTraceparent.get()}")
    collector.birthplaceSpan()?.let { println("birthplace: ${it.name} — ${it.error?.message}") }
}

/** The traced business flow: nested spans, span logs, a real HTTP hop, a parallel fan-out, and a failing leaf. */
private suspend fun checkout(calls: Call.Factory, pricingUrl: String): Unit = trace("checkout") {
    logSpan(LogLevel.INFO) { "checkout started" }

    trace("validate.cart") {
        logSpan(LogLevel.DEBUG) { "3 line items — dropped, DEBUG not captured" }
        delay(15)
        logSpan(LogLevel.INFO) { "cart valid" }
    }

    // A real HTTP call inside a span — TracingInterceptor logs the request/response onto this span and
    // stamps its traceparent on the wire.
    trace("http.GET /pricing") {
        calls.newCall(Request.Builder().url(pricingUrl).build()).execute().use { it.body?.string() }
    }

    // Parallel fan-out: two child spans opened concurrently. The collector's CopyOnWriteArrayList makes
    // the concurrent registration safe.
    coroutineScope {
        listOf("load.user", "load.recommendations")
            .map { name -> async { trace(name) { logSpan(LogLevel.INFO) { "$name loaded" }; delay((30..60).random().toLong()) } } }
            .awaitAll()
    }

    // The failing leaf. ERROR marks the whole path up to `checkout` as the throwable rethrows through
    // each enclosing trace; `birthplaceSpan()` / `report` pick this deepest ERROR span back out.
    trace("payment.charge") {
        logSpan(LogLevel.INFO) { "charging card" }
        delay(20)
        error("card declined")
    }
}
