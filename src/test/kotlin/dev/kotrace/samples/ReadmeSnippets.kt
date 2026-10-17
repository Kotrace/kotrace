package dev.kotrace.samples

import dev.kotrace.Kotrace
import dev.kotrace.LiveAdapter
import dev.kotrace.ReportAdapter
import dev.kotrace.SpanCollector
import dev.kotrace.TracePolicy
import dev.kotrace.TraceStatus
import dev.kotrace.UnredactedTraceRead
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.emitException
import dev.kotrace.event.emitNamed
import dev.kotrace.renderTree
import dev.kotrace.span
import dev.kotrace.withScope
import kotlinx.coroutines.withContext

/**
 * The README's core Kotlin snippets, compiled (ADR-007). Each `README snippet` region is the README block
 * verbatim (imports aside, which live at the top of this file); `ReadmeSnippetsTest` fails if the two drift.
 * Only the stubs around the regions are invented. Never executed.
 */
@Suppress("unused", "UNUSED_VARIABLE", "UnusedVariable")
internal object ReadmeSnippets {

    // Stand-ins for the consumer-side adapters and build config the snippets refer to.
    private class CrashAdapter(reporter: Any) : ReportAdapter {
        override val policy = object : TracePolicy {}
        override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) = Unit
    }
    private class AnalyticsAdapter(analytics: Any) : LiveAdapter {
        override val policy = object : TracePolicy {}
        override fun onLive(record: TraceRecord) = Unit
    }
    private class LiveLogAdapter(logcat: Any) : LiveAdapter {
        override val policy = object : TracePolicy {}
        override fun onLive(record: TraceRecord) = Unit
    }
    private object BuildConfig { const val DEBUG = true }
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
        @OptIn(UnredactedTraceRead::class)           // human debug read — never route this to a sink (ADR-008)
        println(collector.spans.renderTree())
        // end README snippet
    }

    fun spanlessAndScope() {
        // README snippet: spanless-scope
        // 1. Once at startup (or Kotrace.install { diGraph.adapters() } to resolve lazily under DI).
        Kotrace.install(listOf(myLiveAdapter))

        // 2. Orphan emit — no coroutine, no span needed. Fans live to the installed adapters.
        fun onPushReceived(cause: Throwable) {
            emitNamed("push_received", mapOf("channel" to "promo"))
            emitException(cause, mapOf("report" to "explicit"))   // info rides ExceptionRecord.info
        }

        // 3. A scope — everything inside carries scope_id = "session-42"; a span additionally carries its trace_id.
        suspend fun handleSession() = withScope("session-42") {
            emitNamed("session_started")                 // scope_id only
            span("checkout") { /* … */ }                 // trace_id + scope_id
        }
        // end README snippet
    }
}
