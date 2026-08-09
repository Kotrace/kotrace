package dev.kotrace.okhttp

import dev.kotrace.LogLevel
import dev.kotrace.Span
import dev.kotrace.SpanStatus
import dev.kotrace.TRACEPARENT_HEADER
import dev.kotrace.log
import dev.kotrace.toTraceparent
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Injects the W3C `traceparent` for the request's [Span] tag, so the backend continues the same trace —
 * mobile↔backend correlation with no OpenTelemetry dependency.
 *
 * The span is attached to the request **at the call site**, not here: an interceptor runs on OkHttp's
 * own thread and cannot read the coroutine's `SpanContext` (Trap 1). A request with no span tag passes
 * through untouched, so this is safe to install on every client. Pair with [TracingCallFactory], which
 * places the tag from the coroutine thread.
 */
class TracingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val span = chain.request().tag(Span::class.java)
            ?: return chain.proceed(chain.request())

        val request = chain.request().newBuilder()
            .header(TRACEPARENT_HEADER, span.toTraceparent())
            .build()

        // The span is tagged, not ambient, on OkHttp's thread — log to it directly. Path only (no query
        // string): a query can carry user data, which must never reach a report (kotrace's symbol rule).
        span.log(LogLevel.INFO) { "→ ${request.method} ${request.url.encodedPath}" }
        val startNanos = System.nanoTime()
        val response = chain.proceed(request)
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        span.attributes["http.status"] = response.code.toString()
        span.log(if (response.isSuccessful) LogLevel.INFO else LogLevel.ERROR) {
            "← ${response.code} ${request.method} ${request.url.encodedPath} (${tookMs}ms)"
        }
        if (!response.isSuccessful) span.status = SpanStatus.ERROR
        return response
    }
}