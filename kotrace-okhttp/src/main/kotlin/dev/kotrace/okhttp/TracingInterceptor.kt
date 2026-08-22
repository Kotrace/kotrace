package dev.kotrace.okhttp

import dev.kotrace.NonSuspendTracingBridge
import dev.kotrace.Span
import dev.kotrace.SpanStatus
import dev.kotrace.TRACEPARENT_HEADER
import dev.kotrace.end
import dev.kotrace.event.log
import dev.kotrace.toTraceparent
import okhttp3.Headers
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
 * How much it logs is its own [Level] — the same NONE/BASIC/HEADERS/BODY dial OkHttp's
 * `HttpLoggingInterceptor` owns, a **verbosity** scale, distinct from severity: kotrace core holds no log
 * level, so this interceptor decides what to record and tags nothing with severity. The span itself
 * supplies the tree nesting, timing, and OK/ERROR status, so a breadcrumb needs no level of its own.
 *
 * The span carries method + **path only** (no query string: a query can hold user data, which must never
 * reach a report — kotrace's symbol rule). Headers and bodies are logged as **sensitive** events (an
 * `Authorization` header or a body is user data), which the report fan-out drops unless a policy opts in:
 * they must never leave the device, so [Level.HEADERS] / [Level.BODY] are debug-build-only, on-device-only
 * ([bodyLimit] caps how much of a body is read). This is what lets kotrace stand in for OkHttp's logging
 * interceptor at BODY level without the crash-report leak that would follow from a body on a normal span.
 */
class TracingInterceptor(
    private val level: Level = Level.BASIC,
    private val bodyLimit: Long = 8 * 1024,
) : Interceptor {

    /** Verbosity, low → high (each includes the ones below). Mirrors OkHttp's `HttpLoggingInterceptor.Level`. */
    enum class Level { NONE, BASIC, HEADERS, BODY }

    // Closes the span the factory opened, off the coroutine frame (OkHttp's thread) — the non-suspend
    // bridge that end() opts in for (ADR-003).
    @OptIn(NonSuspendTracingBridge::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val span = chain.request().tag(Span::class.java)
            ?: return chain.proceed(chain.request())

        val request = chain.request().newBuilder()
            .header(TRACEPARENT_HEADER, span.toTraceparent())
            .build()

        val path = request.url.encodedPath
        if (level >= Level.HEADERS) span.log(sensitive = true) { "⇢ ${request.method} $path headers${request.headers.render()}" }
        if (level >= Level.BODY) request.bodySnippet()?.let { span.log(sensitive = true) { "⇢ body $it" } }

        val startNanos = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            if (level >= Level.BASIC) span.log { "← ${response.code} ${request.method} $path (${tookMs}ms)" }
            if (level >= Level.HEADERS) span.log(sensitive = true) { "⇠ ${response.code} headers${response.headers.render()}" }
            if (level >= Level.BODY) span.log(sensitive = true) { "⇠ body ${response.peekBody(bodyLimit).string()}" }

            span.putInfo(HttpSpan.STATUS, response.code.toString())
            span.end(if (response.isSuccessful) SpanStatus.OK else SpanStatus.ERROR)
            response
        } catch (t: Throwable) {
            if (level >= Level.BASIC) span.log { "✗ ${request.method} $path: ${t.message}" }
            span.end(SpanStatus.ERROR, t)
            throw t
        }
    }

    /** Header lines, one per entry — sensitive: names are symbols but values (`Authorization`) can be user data. */
    private fun Headers.render(): String = buildString {
        this@render.forEach { (name, value) -> append("\n    $name: $value") }
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
