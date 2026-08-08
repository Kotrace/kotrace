package dev.kotrace.demo

import com.sun.net.httpserver.HttpServer
import dev.kotrace.SpanCollector
import dev.kotrace.TRACEPARENT_HEADER
import dev.kotrace.birthplaceSpan
import dev.kotrace.collectTrace
import dev.kotrace.formatTree
import dev.kotrace.okhttp.TracingCallFactory
import dev.kotrace.okhttp.TracingInterceptor
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
 * A self-contained tour of kotrace. Models a mobile "checkout" flow as a tree of [trace] spans, fans a
 * couple of them out in parallel, and makes one **real** OkHttp call whose `traceparent` is captured by
 * a throwaway in-process server — proving the client↔backend stitch with no OpenTelemetry anywhere.
 *
 * Run: `./gradlew :demo:run -q`
 */
fun main() = runBlocking<Unit> {
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

    // Drive the whole flow under one collector. We build it by hand (rather than via `collectTrace`) so we
    // keep the reference and can call `birthplaceSpan()` on it. runCatching swallows the failure here so we
    // can render the tree — a real reporter reads the collector at the report site and lets it propagate.
    val collector = SpanCollector()
    withContext(collector) {
        runCatching { checkout(calls, pricingUrl) }
    }

    server.stop(0)

    println(collector.spans.formatTree())
    println()
    println("traceparent seen by backend: ${seenTraceparent.get()}")
    collector.birthplaceSpan()?.let { println("birthplace: ${it.name} — ${it.error?.message}") }
}

/** The traced business flow: nested spans, a real HTTP hop, a parallel fan-out, and a failing leaf. */
private suspend fun checkout(calls: Call.Factory, pricingUrl: String): Unit = trace("checkout") {
    trace("validate.cart") { delay(15) }

    // A real HTTP call inside a span — TracingInterceptor stamps this span's traceparent on the wire.
    trace("http.GET /pricing") {
        calls.newCall(Request.Builder().url(pricingUrl).build()).execute().use { it.body?.string() }
    }

    // Parallel fan-out: two child spans opened concurrently. The collector's CopyOnWriteArrayList makes
    // the concurrent registration safe.
    coroutineScope {
        listOf("load.user", "load.recommendations")
            .map { name -> async { trace(name) { delay((30..60).random().toLong()) } } }
            .awaitAll()
    }

    // The failing leaf. ERROR marks the whole path up to `checkout` as the throwable rethrows through
    // each enclosing trace; `birthplaceSpan()` picks this deepest ERROR span back out.
    trace("payment.charge") {
        delay(20)
        error("card declined")
    }
}