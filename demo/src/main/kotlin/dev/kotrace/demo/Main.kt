package dev.kotrace.demo

import com.sun.net.httpserver.HttpServer
import dev.kotrace.event.AttributedEvent
import dev.kotrace.LiveAdapter
import dev.kotrace.ReportAdapter
import dev.kotrace.event.SpanEvent
import dev.kotrace.SpanCollector
import dev.kotrace.SpanStatus
import dev.kotrace.TRACEPARENT_HEADER
import dev.kotrace.TraceConfig
import dev.kotrace.TracePolicy
import dev.kotrace.event.TraceRecord
import dev.kotrace.TraceStatus
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.reportTrace
import dev.kotrace.renderTree
import dev.kotrace.UnredactedTraceRead
import dev.kotrace.currentSpan
import dev.kotrace.event.log
import dev.kotrace.event.toJson
import dev.kotrace.span
import dev.kotrace.okhttp.TracingCallFactory
import dev.kotrace.okhttp.TracingInterceptor
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
 * The point of the demo is the **two-role adapter model** ([TraceConfig] carries the consumer's fan-out):
 * - [LiveWatch] — a [LiveAdapter] that prints every record the instant it is logged (the on-device debug
 *   watch). Its [TracePolicy] wants DEBUG and opts into `sensitive` payloads, because nothing it prints
 *   leaves the device.
 * - [FailureExport] — a [ReportAdapter] that, only on a failed trace, prints the searchable fan-out (one
 *   JSON line per record). Its policy keeps INFO+ and refuses `sensitive` records (fail-closed) — the
 *   subset safe to leave the device.
 *
 * The split is the headline: the same DEBUG breadcrumb and the same captured HTTP body appear in the live
 * watch yet are absent from the export — the per-adapter fan-out policy is what lets kotrace stand in for
 * OkHttp's BODY logging without leaking a body into a crash report. There is no capture gate (ADR-002):
 * every event is stored, and each adapter's [TracePolicy.acceptsEvent] decides delivery — [LiveWatch]
 * surfaces DEBUG live, while the export policy keeps only INFO+ and refuses `sensitive`. Severity is a plain
 * `"level"` attribute — kotrace names no level; these policies rank it.
 *
 * The http span shows the **two span channels** (ADR-001): its `layer=http` is a fixed **attribute** (birth-set,
 * the only thing an `acceptsSpan` gate may read), while `http.status`, known only once the response returns, is
 * emitted **info** — it rides every record off that span (nested in the JSON, `"info":{"http.status":"200"}`) but
 * is never a filter key. Fixed dimensions filter; late results are payload.
 *
 * Run: `./gradlew :demo:run -q`
 */

private fun lvl(level: String) = mapOf("level" to level)
private val INFO_PLUS = setOf("INFO", "WARN", "ERROR")

/** The on-device debug watch: wants every level and every `sensitive` payload — its output never leaves the device. */
private object WatchPolicy : TracePolicy {
    override fun acceptsEvent(event: SpanEvent): Boolean = true
    override val acceptsSensitive: Boolean = true
}

/** The failure export: INFO+ only, no `sensitive` payloads (the fail-closed defaults, spelled out for the tour). */
private object ExportPolicy : TracePolicy {
    override fun acceptsEvent(event: SpanEvent): Boolean =
        (event as? AttributedEvent)?.attributes?.get("level")?.let { it in INFO_PLUS } ?: true
    override val acceptsSensitive: Boolean = false
}

/** Live sink: prints each record as its event is appended — the per-event debug path. */
private class LiveWatch : LiveAdapter {
    override val policy: TracePolicy = WatchPolicy
    override fun onLive(record: TraceRecord) {
        println("live   · ${record.toJson()}")
    }
}

/** Report sink: on a failed trace only, prints the searchable fan-out — one ELK-ingestable JSON line per record. */
private class FailureExport : ReportAdapter {
    override val policy: TracePolicy = ExportPolicy

    /** The birthplace crash record this export saw, if any — the report is the sole birthplace authority (ADR-005). */
    var crash: ExceptionRecord? = null
        private set

    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        if (status == TraceStatus.OK) return // failure-only: keeps ERROR and a future CANCELLED, not just ERROR
        println("── failure export (safe subset — INFO+, no bodies) ──")
        records.forEach { record ->
            println(record.toJson())
            if (record is ExceptionRecord) crash = record
        }
    }
}

// Demo prints the raw tree to stdout for a human — an on-device debug read, never routed to a sink.
@OptIn(UnredactedTraceRead::class)
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

    // Level.BODY logs the request/response headers and bodies as `sensitive` events: LiveWatch shows them,
    // FailureExport drops them. TracingCallFactory tags the request from the coroutine thread;
    // TracingInterceptor writes the traceparent on OkHttp's thread. Both inert without an active span.
    val http = OkHttpClient.Builder().addInterceptor(TracingInterceptor(TracingInterceptor.Level.BODY)).build()
    val calls: Call.Factory = TracingCallFactory(http)

    // Seed the trace with its collector (the span tree) and its config (the adapters). Both ride the
    // CoroutineContext down every child span; fan-out reads them back at the report site below.
    val collector = SpanCollector()
    val export = FailureExport()
    val config = TraceConfig(listOf(LiveWatch(), export))
    withContext(collector + config) {
        // runCatching swallows the failure so we can render — a real reporter lets the throwable propagate
        // and fans out from a boundary that reads the collector/config off context.
        println("── live watch (everything, as it happens — on-device only) ──")
        runCatching { checkout(calls, pricingUrl) }
        server.stop(0)

        println()
        println("── renderTree (human read — the raw tree, all levels) ──")
        println(collector.spans.renderTree())
        println()

        // The trace's verdict, from any ERROR span. reportTrace hands it to every ReportAdapter's onReport, which
        // self-gates on it; it reads the active TraceConfig, so it must run inside this scope.
        val status = if (collector.spans.any { it.status == SpanStatus.ERROR }) TraceStatus.ERROR else TraceStatus.OK
        collector.reportTrace(status)
    }

    println()
    println("traceparent seen by backend: ${seenTraceparent.get()}")
    // The report is the sole birthplace authority (ADR-005): read the crash off the export, not a helper
    // that recomputes it. throwable.message is fine here — this is a local human console read, not a sink.
    export.crash?.let { println("birthplace: ${it.operation} — ${it.throwable.message}") }
}

/** The traced business flow: nested spans, span logs, a real HTTP hop, a parallel fan-out, and a failing leaf. */
private suspend fun checkout(calls: Call.Factory, pricingUrl: String): Unit = span("checkout") {
    currentSpan()?.log(lvl("INFO")) { "checkout started" }

    span("validate.cart") {
        // DEBUG: appears in the live watch, absent from the failure export (its policy is INFO+).
        currentSpan()?.log(lvl("DEBUG")) { "3 line items" }
        delay(15)
        currentSpan()?.log(lvl("INFO")) { "cart valid" }
    }

    // No manual span here: TracingCallFactory opens the http span itself (fixed attribute layer=http) and
    // TracingInterceptor finishes it — status, timing, the traceparent on the wire, and the body events. The
    // response code is stamped as span info (putInfo "http.status"), so every record off this span carries
    // "info":{"http.status":"200"} in its JSON — emitted payload, not a filter key (ADR-001).
    calls.newCall(Request.Builder().url(pricingUrl).build()).execute().use { it.body?.string() }

    // Parallel fan-out: two child spans opened concurrently. The collector's CopyOnWriteArrayList makes
    // the concurrent registration safe.
    coroutineScope {
        listOf("load.user", "load.recommendations")
            .map { name -> async { span(name) { currentSpan()?.log(lvl("INFO")) { "$name loaded" }; delay((30..60).random().toLong()) } } }
            .awaitAll()
    }

    // The failing leaf. ERROR marks the whole path up to `checkout` as the throwable rethrows through
    // each enclosing trace; the export picks this deepest throwable-bearing span back out (ADR-005).
    span("payment.charge") {
        currentSpan()?.log(lvl("INFO")) { "charging card" }
        delay(20)
        error("card declined")
    }
}
