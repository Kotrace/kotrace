package dev.kotrace.okhttp

import dev.kotrace.KotraceLog
import dev.kotrace.Span
import dev.kotrace.currentCollector
import dev.kotrace.currentSpan
import dev.kotrace.startChildSpanHere
import okhttp3.Call
import okhttp3.Request

/**
 * The Trap-1 bridge, and where an HTTP call's **own span** is born. Retrofit calls [newCall] on the
 * coroutine's own thread (when it creates the raw call, before handing it to OkHttp's dispatcher), so
 * [currentSpan] / [currentCollector] still resolve here — they do **not** inside [TracingInterceptor],
 * which runs on OkHttp's thread. So the span is opened and registered here, then tagged on the request
 * for the interceptor to finish (timing / status / body) on OkHttp's thread.
 *
 * With no active span the call opens a **root** http span (untraced-request coverage), but only when
 * something would consume it — an active collector (fan-out) or a live sink. A call made with tracing
 * fully off is passed through untouched, so this is safe to wrap every client with.
 */
class TracingCallFactory(private val delegate: Call.Factory) : Call.Factory {

    override fun newCall(request: Request): Call {
        val untraced = currentSpan() == null && currentCollector() == null && KotraceLog.liveSink == null
        if (untraced) return delegate.newCall(request)

        val span = startChildSpanHere(
            name = "http ${request.method} ${request.url.encodedPath}",
            attributes = mapOf("layer" to "http"),
        )
        return delegate.newCall(request.newBuilder().tag(Span::class.java, span).build())
    }
}
