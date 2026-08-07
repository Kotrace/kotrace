package dev.luxgroup.kotrace.okhttp

import dev.luxgroup.kotrace.Span
import dev.luxgroup.kotrace.currentSpan
import okhttp3.Call
import okhttp3.Request

/**
 * The Trap-1 bridge. Retrofit calls [newCall] on the coroutine's own thread (when it creates the raw
 * call, before handing it to OkHttp's dispatcher), so [currentSpan] still resolves here — it does
 * **not** inside [TracingInterceptor], which runs on OkHttp's thread. Tag the request with the span so
 * the interceptor can read it there and inject `traceparent`.
 *
 * A call made with no active span is passed through untagged and otherwise untouched, so this is safe
 * to wrap every client with.
 */
class TracingCallFactory(private val delegate: Call.Factory) : Call.Factory {

    override fun newCall(request: Request): Call {
        val span = currentSpan() ?: return delegate.newCall(request)
        return delegate.newCall(request.newBuilder().tag(Span::class.java, span).build())
    }
}