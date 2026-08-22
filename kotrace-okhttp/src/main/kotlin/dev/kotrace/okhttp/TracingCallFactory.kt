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
 */
class TracingCallFactory(private val delegate: Call.Factory) : Call.Factory {

    // The genuine non-suspend bridge site: newCall runs on the coroutine thread with no coroutine frame,
    // so startSpan is correct here — this is exactly what NonSuspendTracingBridge opts in for (ADR-003).
    @OptIn(NonSuspendTracingBridge::class)
    override fun newCall(request: Request): Call {
        val untraced = currentThreadSpan() == null && currentThreadCollector() == null &&
            currentThreadConfig()?.liveAdapters.isNullOrEmpty()
        if (untraced) return delegate.newCall(request)

        val span = startSpan(
            name = "http ${request.method} ${request.url.encodedPath}",
            attributes = mapOf(HttpSpan.LAYER to HttpSpan.LAYER_HTTP),
        )
        return delegate.newCall(request.newBuilder().tag(Span::class.java, span).build())
    }
}
