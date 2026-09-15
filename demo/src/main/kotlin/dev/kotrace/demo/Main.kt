package dev.kotrace.demo

import com.sun.net.httpserver.HttpServer
import dev.kotrace.AdapterFaultHook
import dev.kotrace.event.AttributedEvent
import dev.kotrace.FaultPhase
import dev.kotrace.Kotrace
import dev.kotrace.LiveAdapter
import dev.kotrace.ReportAdapter
import dev.kotrace.event.SpanEvent
import dev.kotrace.SpanCollector
import dev.kotrace.TRACEPARENT_HEADER
import dev.kotrace.TraceAdapter
import dev.kotrace.TraceLink
import dev.kotrace.TracePolicy
import dev.kotrace.event.TraceRecord
import dev.kotrace.TraceOutcome
import dev.kotrace.TraceStatus
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.renderTree
import dev.kotrace.UnredactedTraceRead
import dev.kotrace.currentSpan
import dev.kotrace.event.addException
import dev.kotrace.event.emitNamed
import dev.kotrace.event.log
import dev.kotrace.event.toJson
import dev.kotrace.span
import dev.kotrace.withScope
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A self-contained tour of kotrace. Models a mobile "checkout" flow as a tree of [span]s, logs a
 * few lines along the way, fans a couple of spans out in parallel, and makes one **real**
 * OkHttp call whose `traceparent` is captured by a throwaway in-process server — proving the
 * client↔backend stitch with no OpenTelemetry anywhere.
 *
 * **Setup (ADR-010).** The consumer's fan-out is installed **once** as kotrace's process-wide config
 * ([Kotrace.install]); every path resolves it (`currentThreadConfig() ?: Kotrace.defaultConfig()`), so a
 * span, a span-less emit, suspend and non-suspend all reach the same adapters with no per-trace
 * `TraceConfig` seeding. Only the per-flow [SpanCollector] rides the `CoroutineContext`. The four roles:
 * - [LiveWatch] — a [LiveAdapter] that prints every record the instant it is logged (the on-device debug
 *   watch). Its [TracePolicy] wants DEBUG and opts into `sensitive` payloads, because nothing it prints
 *   leaves the device.
 * - [FailureExport] — a [ReportAdapter] that, only on a failed trace, prints the searchable fan-out (one
 *   JSON line per record). Its policy keeps INFO+ and refuses `sensitive` records (fail-closed) — the
 *   subset safe to leave the device.
 * - [FaultyLive] — a deliberately broken [LiveAdapter] (ADR-014) that throws exactly when it matters most.
 * - [FaultWatch] — an opt-in [AdapterFaultHook] (ADR-014) that observes the faults kotrace contains.
 *
 * The split is the headline: the same DEBUG breadcrumb and the same captured HTTP body appear in the live
 * watch yet are absent from the export — the per-adapter fan-out policy is what lets kotrace stand in for
 * OkHttp's BODY logging without leaking a body into a crash report. There is no capture gate (ADR-002):
 * every event is stored, and each adapter's [TracePolicy.acceptsEvent] decides delivery — [LiveWatch]
 * surfaces DEBUG live, while the export policy keeps only INFO+ and refuses `sensitive`. Severity is a plain
 * `"level"` attribute — kotrace names no level; these policies rank it.
 *
 * **Auto-root (ADR-013).** The consumer writes only `span { }`. The top-level `checkout` span opens with no
 * span and no collector in context, so it **self-owns** the trace: it installs a collector, opens the root,
 * runs the block, and calls `reportTrace` once at the outcome (`OK` on return, `CANCELLED`/`ERROR` on an
 * escaping throwable) — no hand-assembled `SpanCollector`, no manual `reportTrace`. Forgetting the boundary
 * can no longer silently drop the trace. The `FailureExport` below receives the failure report at the
 * outcome purely because `checkout` failed — nothing in `main` reports it. (The manual `SpanCollector` path
 * survives for bespoke control — the user-report flow at the end keeps a collector so it can `renderTree`.)
 *
 * **Failure-as-value (ADR-016).** `checkout` fails by *throwing* — the outcome auto-root derives from
 * completion. A `refund` saga at the end shows the other shape: its domain failure is a **returned value**,
 * not a throw. It uses the return-aware `span(returnedOutcome = …) { }` overload to map the returned
 * `Payout.Failed` to `ERROR` and ride the saga's rollback throwables up as trace-level `attached` orphans
 * (ADR-012). The birthplace cause is recorded on the root with the suspend-safe `addException` (a returned
 * `ERROR` verdict does not itself stamp the span). Nothing is thrown, yet `FailureExport` still receives the
 * failure report — with both the birthplace cause and the attached rollback throwables.
 *
 * **Adapter fault isolation (ADR-014).** A telemetry sink must never corrupt the traced operation.
 * [FaultyLive] throws while `span` is recording the escaping `card declined` throwable — the most damaging
 * moment, *before* the rethrow, where an unguarded live adapter would *replace* the application throwable.
 * kotrace contains it: `checkout` still fails with `card declined`, [LiveWatch] (a sibling) still prints
 * every record, and the fault is routed to the opt-in [FaultWatch] hook. Release installs no hook and the
 * fault stays silent; the tour installs one to make the containment visible. JVM-fatal errors are the
 * deliberate exception — kotrace never swallows those.
 *
 * **Span-less emits + scope (ADR-010).** Before the trace, an orphan `emitNamed` — no span, no context —
 * still reaches the live fan-out through the installed global (an app-lifecycle or push callback emits
 * exactly this way); its record carries null identity and no `scope_id`. The checkout then runs inside a
 * [withScope]: everything under it — the traced spans **and** an orphan emit — carries a `scope_id`
 * correlation umbrella above `trace_id` (`scope ⊇ trace ⊇ span`). Watch `"scope_id":"checkout_session_42"`
 * appear on the in-scope records, live and in the export, and be absent from the pre-scope orphan.
 *
 * The http span shows the **two span channels** (ADR-001): its `layer=http` is a fixed **attribute** (birth-set,
 * the only thing an `acceptsSpan` gate may read), while `http.status`, known only once the response returns, is
 * emitted **info** — it rides every record off that span (nested in the JSON, `"info":{"http.status":"200"}`) but
 * is never a filter key. Fixed dimensions filter; late results are payload.
 *
 * Finally the tour shows **cross-trace correlation** (ADR-009): after checkout fails, a separate
 * "user reports the failure" flow opens its own trace carrying a [TraceLink] back to the checkout's
 * `trace_id` (captured from inside the auto-rooted span). The link joins two independent trees where
 * [dev.kotrace.Span.parentId] cannot, and surfaces as a nested `links` array on every record off the
 * linking span.
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

/**
 * ADR-014: a live sink that fails exactly when it matters most — while `span` records the escaping throwable,
 * *before* the rethrow. Without fault isolation its throw would replace the application's `card declined`;
 * with it, the app throwable survives, [LiveWatch] still prints, and the fault is routed to [FaultWatch].
 */
private class FaultyLive : LiveAdapter {
    override val policy: TracePolicy = WatchPolicy // sees everything, like the watch
    override fun onLive(record: TraceRecord) {
        if (record is ExceptionRecord) throw RuntimeException("sink offline: could not ship crash record")
    }
}

/**
 * ADR-014: an opt-in diagnostic hook. Release installs none, so contained faults stay silent and a broken
 * sink can never crash the traced operation; this tour installs one to make the containment visible. The
 * hook contract allows concurrent invocation across threads, so the counter is atomic.
 */
private class FaultWatch : AdapterFaultHook {
    private val count = AtomicInteger()
    val faults: Int get() = count.get()
    override fun onAdapterFault(phase: FaultPhase, adapter: TraceAdapter?, cause: Throwable) {
        count.incrementAndGet()
        println("fault  · [$phase] ${adapter?.let { it::class.simpleName } ?: "shared record"} contained: ${cause.message}")
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
    // The http span's fixed filter attribute is passed in from here, not baked into the factory.
    val calls: Call.Factory = TracingCallFactory(http, mapOf("layer" to "http"))

    // ADR-010 setup: install the consumer's adapters ONCE as kotrace's process-wide fan-out config. Every
    // path (span, span-less, live, report, suspend, non-suspend) resolves this — so no per-trace TraceConfig
    // is seeded onto the context below; only the SpanCollector (per-flow state) rides it. FaultyLive is a
    // broken sink included on purpose (ADR-014); FaultWatch is the opt-in hook that observes what kotrace
    // contains — release would install neither.
    val export = FailureExport()
    val faultWatch = FaultWatch()
    // FaultyLive is installed BEFORE LiveWatch on purpose: it fails first, so LiveWatch printing afterwards
    // is honest proof that a contained fault does not starve the siblings that follow it (ADR-014).
    Kotrace.install(listOf(FaultyLive(), LiveWatch(), export), faultWatch)

    // A span-less orphan emit (ADR-010): outside any span, outside any scope, with nothing on the context —
    // yet it still reaches the live fan-out through the installed global. An app-lifecycle or push callback
    // emits exactly like this. Its record has null identity and NO scope_id.
    println("── live watch · span-less orphan (no span, no scope) ──")
    emitNamed("app_launched", mapOf("build" to "demo"))

    // The auto-rooted checkout captures its own trace_id for the cross-trace link below; nothing in main
    // holds its collector — auto-root owns it (ADR-013).
    val checkoutTraceId = AtomicReference<String?>()
    // withScope establishes a live-only correlation umbrella: every record under it carries
    // scope_id="checkout_session_42" alongside its trace_id (a span) or alone (an orphan emit).
    withScope("checkout_session_42") {
        println()
        println("── live watch (everything, as it happens — on-device only) ──")
        // An orphan emit INSIDE the scope: no span, so still null identity — but now carrying scope_id.
        emitNamed("checkout_opened")
        // ADR-013 auto-root: `checkout` is a bare top-level span. It self-owns a collector, opens the root,
        // and reports at the outcome — so FailureExport receives the ERROR report here with no manual
        // SpanCollector and no manual reportTrace. runCatching only keeps the demo running past the failure;
        // the throwable still escaped the span, which is what makes auto-root report ERROR.
        // ADR-014: FaultyLive throws while the crash is recorded, but it is contained — checkout still fails
        // with `card declined`, LiveWatch still prints, and FaultWatch observes the LIVE fault.
        val outcome = runCatching { checkout(calls, pricingUrl, checkoutTraceId) }
        server.stop(0)

        println()
        // The app throwable is preserved (ADR-014): the broken sink did not replace it.
        println("checkout failed with: ${outcome.exceptionOrNull()?.message}")
        println("adapter faults contained + observed by the hook: ${faultWatch.faults}")
    }
    // Capture checkout's birthplace now — the refund saga below also fails and would otherwise overwrite the
    // shared export's last-seen crash record.
    val checkoutCrash = export.crash

    // Cross-trace correlation (ADR-009): a follow-up "user reports the failure" flow is deliberately its
    // own trace, yet it is *about* the checkout that just failed. Open the report trace carrying a TraceLink
    // to the checkout's captured trace_id — a birth-set edge that joins the two separate trees where
    // parentId (an in-tree edge) cannot. The link rides every record off the linking span and surfaces in
    // the live watch as a nested `links` array on the wire.
    //
    // This flow keeps a manual SpanCollector on purpose: with a collector already in context, the span is a
    // manual-boundary root (ADR-013) — it does NOT auto-report, and the consumer keeps the collector to
    // renderTree the raw tree. That is the deliberate escape hatch auto-root leaves in place.
    val reportCollector = SpanCollector()
    withContext(reportCollector) {
        println()
        println("── live watch · user report (a SEPARATE trace linking back to checkout) ──")
        val link = TraceLink(checkoutTraceId.get()!!, mapOf("reason" to "user_report"))
        span("user_report_error", links = listOf(link)) {
            currentSpan()?.log(lvl("INFO")) { "user reported the failed checkout" }
        }
        println()
        println("── renderTree · user report (manual boundary — you keep the collector, ADR-013) ──")
        println(reportCollector.spans.renderTree())
    }

    // Failure-as-value (ADR-016): the refund saga fails by *returning* a Payout.Failed — nothing is thrown.
    // The return-aware span maps that value to ERROR and rides the rollback throwables up as attached orphans,
    // so FailureExport still fires. Contrast with checkout, whose ERROR came from an escaping throwable.
    println()
    println("── live watch · refund saga (failure is a RETURNED value, not a throw — ADR-016) ──")
    val payout = refundSaga()
    println()
    println("refund saga returned $payout — nothing was thrown, yet the failure was exported above")

    println()
    println("traceparent seen by backend: ${seenTraceparent.get()}")
    // The report is the sole birthplace authority (ADR-005): read the crash off the export, not a helper
    // that recomputes it. throwable.message is fine here — this is a local human console read, not a sink.
    checkoutCrash?.let { println("checkout birthplace: ${it.operation} — ${it.throwable.message}") }
}

/**
 * The traced business flow: nested spans, span logs, a real HTTP hop, a parallel fan-out, and a failing leaf.
 * Written as a bare top-level [span] — auto-root (ADR-013) makes it self-own its trace and report at the
 * outcome when called with no trace in context. It captures its own `trace_id` into [traceIdSink] so the
 * cross-trace user-report flow can link back to it.
 */
private suspend fun checkout(
    calls: Call.Factory,
    pricingUrl: String,
    traceIdSink: AtomicReference<String?>,
): Unit = span("checkout") {
    traceIdSink.set(currentSpan()?.traceId)
    currentSpan()?.log(lvl("INFO")) { "checkout started" }

    span("validate.cart") {
        // DEBUG: appears in the live watch, absent from the failure export (its policy is INFO+).
        currentSpan()?.log(lvl("DEBUG")) { "3 line items" }
        delay(15)
        currentSpan()?.log(lvl("INFO")) { "cart valid" }
    }

    // No manual span here: TracingCallFactory opens the http span itself (attribute layer=http, passed in above) and
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
    // each enclosing span; the export picks this deepest throwable-bearing span back out (ADR-005).
    span("payment.charge") {
        currentSpan()?.log(lvl("INFO")) { "charging card" }
        delay(20)
        error("card declined")
    }
}

/** A refund outcome carried as a **value** (ADR-016): a failure is *returned*, not thrown. */
private sealed interface Payout {
    data class Settled(val cents: Int) : Payout
    /** [cause] is the birthplace throwable; [rollbackErrors] are the saga's suppressed unwinding throwables. */
    data class Failed(val cause: Throwable, val rollbackErrors: List<Throwable>) : Payout
}

/**
 * A saga whose failure is a **returned value** (ADR-016), not a throw. The return-aware `span` overload maps
 * the returned [Payout] to a trace outcome: [Payout.Settled] → `OK`, [Payout.Failed] → `ERROR` carrying the
 * rollback throwables as trace-level `attached` orphans (ADR-012). The birthplace [Payout.Failed.cause] is
 * recorded on the root span with the suspend-safe [addException] so the crash sink receives it — a returned
 * `ERROR` verdict does not itself stamp the span. Because auto-root still reports, `FailureExport` fires with
 * no throw in sight.
 */
private suspend fun refundSaga(): Payout =
    span(
        name = "refund",
        returnedOutcome = { result: Payout ->
            when (result) {
                is Payout.Settled -> TraceOutcome(TraceStatus.OK)
                is Payout.Failed -> TraceOutcome(TraceStatus.ERROR, attached = result.rollbackErrors)
            }
        },
    ) {
        currentSpan()?.log(lvl("INFO")) { "refund requested" }
        span("gateway.refund") {
            currentSpan()?.log(lvl("INFO")) { "calling gateway" }
            delay(10)
        }
        // The gateway rejects and unwinding the ledger throws while rolling back — but neither escapes the
        // flow: the failure is returned as a value, with the rollback throwable riding on it.
        val cause = IllegalStateException("gateway rejected refund")
        val rollback = IllegalStateException("ledger reversal failed during rollback")
        currentSpan()?.log(lvl("ERROR")) { "refund failed; rolled back" }
        currentSpan()?.addException(cause) // birthplace on the root; the returned value carries the verdict
        Payout.Failed(cause, listOf(rollback))
    }
