package dev.kotrace.samples

import dev.kotrace.Kotrace
import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.TraceStatus
import dev.kotrace.UnredactedTraceRead
import dev.kotrace.adapter.LiveAdapter
import dev.kotrace.adapter.ReportAdapter
import dev.kotrace.adapter.FaultHook
import dev.kotrace.adapter.TracePolicy
import dev.kotrace.currentSpan
import dev.kotrace.event.ExceptionOrigin
import dev.kotrace.event.ExceptionRecord
import dev.kotrace.event.addException
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.emitException
import dev.kotrace.event.emitNamed
import dev.kotrace.report.renderTree
import dev.kotrace.span
import dev.kotrace.withScope
import kotlinx.coroutines.withContext

/**
 * The core Kotlin snippets of the README and `docs/writing-adapters.md`, compiled (ADR-007/030). Each `README snippet` region is the README block
 * verbatim (imports aside, which live at the top of this file); `ReadmeSnippetsTest` fails if the two drift.
 * Only the stubs around the regions are invented. Never executed.
 */
@Suppress("unused", "UNUSED_VARIABLE", "UnusedVariable")
internal object ReadmeSnippets {

    // Stand-ins for the consumer-side adapters and build configuration the snippets refer to. Each declares the
    // attributes it accepts (ADR-039), as a real adapter must.
    private class CrashAdapter(reporter: Any) : ReportAdapter {
        override val policy = object : TracePolicy {
            override fun acceptsAttribute(key: String, value: String) = key == "report"
        }
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) = Unit
    }
    private class AnalyticsAdapter(analytics: Any) : LiveAdapter {
        override val policy = object : TracePolicy {
            override fun acceptsAttribute(key: String, value: String) = key == "channel"
        }
        override fun onLive(record: TraceRecord) = Unit
    }
    private class LiveLogAdapter(logcat: Any) : LiveAdapter {
        // A debug watch on the developer's own device: everything, since nothing it prints leaves that device.
        override val policy = object : TracePolicy {
            override val acceptsSensitive = true
            override fun acceptsAttribute(key: String, value: String) = true
        }
        override fun onLive(record: TraceRecord) = Unit
    }
    private object BuildConfig { const val DEBUG = true }
    private class SessionStore { fun currentSessionId(): String? = null }
    private val sessionStore = SessionStore()
    private val reporter = Any()
    private val analytics = Any()
    private val logcat = Any()
    private val myLiveAdapter: LiveAdapter = LiveLogAdapter(logcat)

    fun installBuildList() {
        // README snippet: install-buildlist
        Kotrace.install {
            buildList {
                add(CrashAdapter(reporter))
                add(AnalyticsAdapter(analytics))
                if (BuildConfig.DEBUG) add(LiveLogAdapter(logcat)) // conditional, reads like addInterceptor
            }
        }
        // end README snippet
    }

    fun adapterPolicy() {
        // README snippet: adapter-policy
        // Ships off the device: a vetted vocabulary, kotrace's own keys admitted by name like any other.
        val crashPolicy = object : TracePolicy {
            private val shipped = setOf("feature", "result", Span.EVENTS_DROPPED_ATTRIBUTE)
            override fun acceptsAttribute(key: String, value: String) = key in shipped
        }

        // A debug watch on the developer's own device: every attribute, and sensitive messages too.
        val logcatPolicy = object : TracePolicy {
            override val acceptsSensitive = true
            override fun acceptsAttribute(key: String, value: String) = true
        }
        // end README snippet
    }

    suspend fun renderTree() {
        // README snippet: render-tree
        val collector = SpanCollector()
        withContext(collector) {                     // rides the CoroutineContext down every child span
            runCatching {                            // a real reporter lets the throwable propagate instead
                span("handler") {
                    span("service") {
                        span("store.write") { error("boom") }
                    }
                }
            }
        }
        @OptIn(UnredactedTraceRead::class)           // human debug read, never route this to a sink (ADR-008)
        println(collector.spans.renderTree())
        // end README snippet
    }

    fun spanlessAndScope() {
        // README snippet: spanless-scope
        // 1. Once at startup (or Kotrace.install { diGraph.adapters() } to resolve lazily under dependency injection).
        //    The optional fallbackScopeId scopes every record emitted outside any withScope (ADR-038).
        Kotrace.install(listOf(myLiveAdapter), fallbackScopeId = { sessionStore.currentSessionId() })

        // 2. Orphan emit: no coroutine, no span needed. Fans live to the installed adapters, under the fallback scope_id.
        fun onPushReceived(cause: Throwable) {
            emitNamed("push_received", mapOf("channel" to "promo"))
            emitException(cause, mapOf("report" to "explicit"))   // rides ExceptionRecord.spanAttributes
        }

        // 3. A scope: everything inside carries scope_id = "journey-42" instead; a span additionally carries its trace_id.
        suspend fun handleJourney() = withScope("journey-42") {
            emitNamed("journey_started")                 // scope_id only
            span("checkout") { /* … */ }                 // trace_id + scope_id
        }
        // end README snippet
    }

    // Stand-ins for the guide's crash reporter, trace store and metrics.
    interface CrashReporter { fun record(cause: Throwable, traceId: String?) }
    interface TraceStore { fun append(record: ExceptionRecord, propagated: Boolean) }
    private object Metrics { fun count(name: String, tag: String) = Unit }

    // README snippet: guide-report-crash
    class ReportCrashSink(private val reporter: CrashReporter) : ReportAdapter {
        override val policy = object : TracePolicy {}

        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            if (status != TraceStatus.ERROR) return          // a recovered failure is in an OK report too
            records.filterIsInstance<ExceptionRecord>().forEach { reporter.record(it.throwable, it.traceId) }
        }
    }
    // end README snippet

    // README snippet: guide-live-crash
    class LiveCrashSink(private val reporter: CrashReporter) : LiveAdapter, ReportAdapter {
        override val policy = object : TracePolicy {
            // Every addException is shipped live by onLive, so the report must not hand it over again (ADR-040).
            override val acceptsExplicitException = false
            // The marker is read off a filtered record, so it must be admitted, or onLive never sees it (ADR-039).
            override fun acceptsAttribute(key: String, value: String) = key == REPORTED
        }

        // A live record has no origin: the marker is what tells an explicit report from a thrown failure.
        override fun onLive(record: TraceRecord) {
            if (record is ExceptionRecord && record.spanAttributes[REPORTED] == "true") {
                reporter.record(record.throwable, record.traceId)
            }
        }

        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            if (status != TraceStatus.ERROR) return
            records.filterIsInstance<ExceptionRecord>().forEach { reporter.record(it.throwable, it.traceId) }
        }

        companion object { const val REPORTED = "reported" }
    }
    // end README snippet

    // README snippet: guide-live-crash-call
    suspend fun reportCaught(cause: Throwable) {
        val marked = mapOf(LiveCrashSink.REPORTED to "true")
        val span = currentSpan()
        if (span != null) span.addException(cause, marked) else emitException(cause, marked)
    }
    // end README snippet

    // README snippet: guide-trace-viewer
    class TraceViewerSink(private val store: TraceStore) : ReportAdapter {
        override val policy = object : TracePolicy {
            override val acceptsPropagatedException = true   // the whole climb, not one record per failure
        }

        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
            records.filterIsInstance<ExceptionRecord>().forEach { record ->
                when (record.origin) {                       // exhaustive: a new origin is a compile error here
                    ExceptionOrigin.BIRTHPLACE, ExceptionOrigin.EXPLICIT -> store.append(record, propagated = false)
                    ExceptionOrigin.PROPAGATED -> store.append(record, propagated = true)
                    null -> Unit                             // live records only; a report record always has one
                }
            }
        }
    }
    // end README snippet

    fun faultHook() {
        // README snippet: guide-fault-hook
        Kotrace.installFaultHook { phase, faultMessage, cause ->
            Metrics.count("kotrace_fault", "${phase.name}:${faultMessage ?: cause::class.simpleName}")
        }
        // end README snippet
    }
}
