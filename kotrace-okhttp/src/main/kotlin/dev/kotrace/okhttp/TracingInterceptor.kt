package dev.kotrace.okhttp

import dev.kotrace.LogLevel
import dev.kotrace.Span
import dev.kotrace.SpanStatus
import dev.kotrace.TRACEPARENT_HEADER
import dev.kotrace.endHere
import dev.kotrace.log
import dev.kotrace.toTraceparent
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.util.concurrent.TimeUnit

/**
 * Finishes the HTTP [Span] opened by [TracingCallFactory]: injects the W3C `traceparent` (so the backend
 * continues the same trace — mobile↔backend correlation, no OpenTelemetry dependency), brackets
 * `chain.proceed`, and stamps the span's status / timing / error. A request with no span tag passes
 * through untouched, so this is safe to install on every client.
 *
 * The span carries method + **path only** (no query string: a query can hold user data, which must never
 * reach a report — kotrace's symbol rule). A response-summary event is logged so a live sink shows the
 * call as it happens and the failure fan-out has a human line; the span itself supplies the tree
 * nesting, timing and status. Request/response **bodies** — captured only when [captureBody] is on — are
 * logged as `local` events, which [dev.kotrace.SpanCollector.report] drops unconditionally: a body is PII
 * and must never leave the device, so body capture is a debug-build-only, on-device-only concern
 * ([bodyLimit] caps how much is read). This is what lets kotrace stand in for OkHttp's logging
 * interceptor at BODY level without the crash-report leak that would follow from a body on a normal span.
 */
class TracingInterceptor(
    private val captureBody: Boolean = false,
    private val bodyLimit: Long = 8 * 1024,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val span = chain.request().tag(Span::class.java)
            ?: return chain.proceed(chain.request())

        val request = chain.request().newBuilder()
            .header(TRACEPARENT_HEADER, span.toTraceparent())
            .build()

        val path = request.url.encodedPath
        if (captureBody) request.bodySnippet()?.let { span.log(LogLevel.DEBUG, local = true) { "⇢ body $it" } }

        val startNanos = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            span.attributes["http.status"] = response.code.toString()
            span.log(if (response.isSuccessful) LogLevel.INFO else LogLevel.ERROR) {
                "← ${response.code} ${request.method} $path (${tookMs}ms)"
            }
            if (captureBody) {
                span.log(LogLevel.DEBUG, local = true) { "⇠ body ${response.peekBody(bodyLimit).string()}" }
            }
            span.endHere(if (response.isSuccessful) SpanStatus.OK else SpanStatus.ERROR)
            response
        } catch (t: Throwable) {
            span.log(LogLevel.ERROR) { "✗ ${request.method} $path: ${t.message}" }
            span.endHere(SpanStatus.ERROR, t)
            throw t
        }
    }

    /**
     * The request body as text, or null. Skips a **one-shot** body — writing it here would consume it and
     * break the actual send — and caps at [bodyLimit]. Best-effort: a binary body reads as garbled text,
     * acceptable for a device-only debug event; a read failure yields null.
     */
    private fun Request.bodySnippet(): String? {
        val body = body ?: return null
        if (body.isOneShot()) return null
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8(minOf(buffer.size, bodyLimit))
        } catch (t: Throwable) {
            null
        }
    }
}
