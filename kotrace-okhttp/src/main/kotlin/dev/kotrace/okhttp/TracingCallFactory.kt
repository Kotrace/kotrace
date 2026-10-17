package dev.kotrace.okhttp

import dev.kotrace.NonSuspendTracingBridge
import dev.kotrace.Span
import dev.kotrace.currentThreadCollector
import dev.kotrace.currentThreadConfig
import dev.kotrace.currentThreadSpan
import dev.kotrace.startSpan
import okhttp3.Call
import okhttp3.Request

/**
 * The Trap-1 bridge, and where an HTTP call's **own span** is born. Retrofit calls [newCall] on the
 * coroutine's own thread (when it creates the raw call, before handing it to OkHttp's dispatcher), so
 * [currentThreadSpan] / [currentThreadCollector] still resolve here — they do **not** inside [TracingInterceptor],
 * which runs on OkHttp's thread. So the span is opened and registered here, then tagged on the request
 * for the interceptor to finish (timing / status / body) on OkHttp's thread.
 *
 * With no active span the call opens a **root** http span (untraced-request coverage), but only when
 * something would consume it — an active collector (fan-out) or a registered live adapter. A call made
 * with tracing fully off is passed through untouched, so this is safe to wrap every client with.
 *
 * **The span name is a static symbol, never the raw URL (ADR-021).** A span's name becomes `operation` on
 * every record lifted off it — including the non-sensitive crash record and the `toJson` log line — and no
 * policy gate covers it. A REST path can embed user data (`/users/alice@example.com`), so the default
 * [spanName] is `http <METHOD>` only; the path stays on the `sensitive` breadcrumbs [TracingInterceptor]
 * logs. To tell endpoints apart, pass a [spanName] that maps a request to a **sanitized route template**
 * (`http GET /users/{id}`) — it must return a symbol, never interpolate the concrete path or query.
 */
class TracingCallFactory(
    private val delegate: Call.Factory,
    private val attributes: Map<String, String> = emptyMap(),
    private val spanName: (Request) -> String = ::defaultHttpSpanName,
) : Call.Factory {

    // The genuine non-suspend bridge site: newCall runs on the coroutine thread with no coroutine frame,
    // so startSpan is correct here — this is exactly what NonSuspendTracingBridge opts in for (ADR-003).
    @OptIn(NonSuspendTracingBridge::class)
    override fun newCall(request: Request): Call {
        val untraced = currentThreadSpan() == null && currentThreadCollector() == null &&
            currentThreadConfig()?.liveAdapters.isNullOrEmpty()
        if (untraced) return delegate.newCall(request)

        val span = startSpan(
            name = nameOf(request),
            attributes = attributes,
        )
        return delegate.newCall(request.newBuilder().tag(Span::class.java, span).build())
    }

    /**
     * The caller's [spanName], or the default when it throws (a route table miss, a bug in the mapper): naming a
     * span is telemetry and must never fail the call. A JVM-fatal error still propagates (ADR-014).
     */
    private fun nameOf(request: Request): String = try {
        spanName(request)
    } catch (t: Throwable) {
        if (t is VirtualMachineError || t is ThreadDeath || t is LinkageError) throw t
        defaultHttpSpanName(request)
    }
}

/** The default [TracingCallFactory] span name: `http <METHOD>` — a symbol only, no path or query (ADR-021). */
private fun defaultHttpSpanName(request: Request): String = "http ${request.method}"
