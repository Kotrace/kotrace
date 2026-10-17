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
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens and closes the HTTP [Span] for each execution of a call [TracingCallFactory] tagged: injects the W3C
 * `traceparent` (so the backend continues the same trace — mobile↔backend correlation, no OpenTelemetry
 * dependency), brackets `chain.proceed`, and stamps the span's status / timing / error. It emits under the flow's
 * captured config (ADR-023), and a telemetry fault never changes the call's response or exception. A request the
 * factory did not tag passes through untouched, so this is safe to install on every client. Install it with
 * `addInterceptor` (once per call), not `addNetworkInterceptor` (once per redirect/retry hop).
 *
 * How much it logs is its own [Level] — the same NONE/BASIC/HEADERS/BODY dial OkHttp's
 * `HttpLoggingInterceptor` owns, a **verbosity** scale, distinct from severity: kotrace core holds no log
 * level, so this interceptor decides what to record and tags nothing with severity. The span itself
 * supplies the tree nesting, timing, and OK/ERROR status, so a breadcrumb needs no level of its own.
 *
 * Every breadcrumb that interpolates a URL **path** (the [Level.BASIC] request/response lines) is logged
 * **sensitive**, because a REST path can embed user data (`/users/alice@example.com`) — kotrace's symbol
 * rule. Headers and bodies are sensitive for the same reason (an `Authorization` header or a body is user
 * data). The report fan-out drops every sensitive event unless a policy opts in, so all of these are
 * on-device-only by default; [Level.HEADERS] / [Level.BODY] remain debug-build-only. The only non-sensitive
 * facts are the span's own method/status/timing/nesting.
 *
 * [Level.BODY] is bounded and never alters the transport (ADR-024):
 * - **Request:** a body whose declared length exceeds [bodyLimit] is not written at all (only its size is
 *   logged); a smaller or unknown-length one is written into a sink that stops after [bodyLimit] + 1 bytes (the
 *   extra byte tells a truncated body from one that fits). A one-shot or duplex body is omitted, never consumed.
 *   The cap bounds a body written in pieces; an **unknown-length** body that hands okio one large chunk (a
 *   `MultipartBody` with an unknown-length part around a big `ByteArray`) is still staged whole once before the
 *   cap can stop it — debug-only memory, bounded by that chunk.
 * - **Response:** nothing is read before the response is returned. The body is wrapped so that, **as the caller
 *   reads it**, the first [bodyLimit] + 1 bytes are copied aside, and the snippet is logged once the caller
 *   reaches the end of the body or closes it. The call gains no latency and no extra read, and a stream is never
 *   waited on. A body the caller never reads logs no snippet. The event carries a `String`, so it survives the
 *   body being closed before a report runs. A `text/event-stream` or duplex response is logged as omitted.
 * This is what lets kotrace stand in for OkHttp's logging interceptor at BODY level without the crash-report
 * leak that would follow from a path or body on a normal span.
 */
class TracingInterceptor(
    private val level: Level = Level.BASIC,
    private val bodyLimit: Long = 8 * 1024,
) : Interceptor {

    init {
        require(bodyLimit >= 0) { "bodyLimit must be >= 0, was $bodyLimit" }
    }

    /** One byte past [bodyLimit], so a snippet can tell a body that exactly fits from a truncated one. */
    private val captureLimit = bodyLimit.coerceAtMost(Long.MAX_VALUE - 1) + 1

    /** Verbosity, low → high (each includes the ones below). Mirrors OkHttp's `HttpLoggingInterceptor.Level`. */
    enum class Level { NONE, BASIC, HEADERS, BODY }

    // Runs on OkHttp's thread, off the coroutine frame: the span is opened and closed through the flow's
    // captured TraceHandoff — the non-suspend bridge NonSuspendTracingBridge opts in for (ADR-003/023).
    @OptIn(NonSuspendTracingBridge::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val trace = chain.request().tag(HttpTrace::class.java)
            ?: return chain.proceed(chain.request())
        // Emit under the flow's own config override, not whatever this dispatcher thread would resolve.
        return trace.handoff.withCapturedConfig { traced(chain, trace) }
    }

    /**
     * One execution, one span (ADR-023): a cloned or retried call re-enters here and gets its own span, so a
     * failed attempt is never overwritten by the next one. Instrumentation only observes: every telemetry step
     * runs under [observe], so a telemetry fault can neither fail the call nor replace its exception, and
     * [end] attaches a failed exception record to the application throwable as suppressed instead of throwing.
     */
    @OptIn(NonSuspendTracingBridge::class)
    private fun traced(chain: Interceptor.Chain, trace: HttpTrace): Response {
        val span = trace.handoff.startSpan(trace.spanName, trace.attributes)
        // The span id is minted W3C-valid, but the trace id is inherited from the parent, which a hand-built or
        // continued span may carry in another shape; toTraceparent then refuses it (ADR-028). That is telemetry,
        // not the call: send the request without the header rather than fail it (ADR-023).
        val traceparent = try {
            span.toTraceparent()
        } catch (t: Throwable) {
            if (t.isJvmFatal()) throw t
            null
        }
        // Either way the request carries this span's header or none: a traceparent the caller set would otherwise
        // survive only when ours is omitted, tying the request to an unrelated trace.
        val request = chain.request().newBuilder().apply {
            if (traceparent == null) removeHeader(TRACEPARENT_HEADER) else header(TRACEPARENT_HEADER, traceparent)
        }.build()

        val path = request.url.encodedPath
        observe {
            if (traceparent == null && level >= Level.BASIC) span.log { "traceparent omitted: the trace id is not a W3C trace-id" }
            if (level >= Level.HEADERS) span.log(sensitive = true) { "⇢ ${request.method} $path headers${request.headers.render()}" }
            if (level >= Level.BODY) request.bodySnippet()?.let { snippet -> span.log(sensitive = true) { "⇢ body $snippet" } }
        }

        val startNanos = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            // sensitive: carries the path and the throwable message, both of which can hold user data. The
            // throwable itself is recorded via end(ERROR, t) → the crash reporter, independent of this line.
            observe { if (level >= Level.BASIC) span.log(sensitive = true) { "✗ ${request.method} $path: ${t.message}" } }
            span.end(SpanStatus.ERROR, t)
            throw t
        }

        // The lazy messages below capture plain values, never the Response: a report adapter evaluates them long
        // after the caller has closed the body.
        val code = response.code
        val headers = response.headers
        observe {
            span.putInfo(HttpSpan.STATUS, code.toString())
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            // sensitive: the line carries the URL path, which can embed user data (an id, an email); a
            // report sink sees it only if its policy opts into sensitive (device-only by default).
            if (level >= Level.BASIC) span.log(sensitive = true) { "← $code ${request.method} $path (${tookMs}ms)" }
            if (level >= Level.HEADERS) span.log(sensitive = true) { "⇠ $code headers${headers.render()}" }
        }
        val returned = if (level >= Level.BODY) response.withBodyCapture(request, span, trace) else response
        span.end(if (response.isSuccessful) SpanStatus.OK else SpanStatus.ERROR)
        return returned
    }

    /**
     * The response the caller gets at [Level.BODY]: its body wrapped in a [TeeSource] that copies the first
     * [captureLimit] bytes as the caller reads, then logs the snippet onto [span] (under the flow's captured
     * config, from whatever thread the caller reads on). A stream is logged as omitted and returned untouched.
     * Wrapping never fails the call: on any non-fatal fault the original response is returned.
     */
    @OptIn(NonSuspendTracingBridge::class)
    private fun Response.withBodyCapture(request: Request, span: Span, trace: HttpTrace): Response {
        if (bodyLimit == 0L) return this
        return try {
            // Nullable on OkHttp 4 (the declared floor, ADR-027), never null on 5.
            val body = body ?: return this
            val type = body.contentType()
            val omitted = when {
                request.body?.isDuplex() == true -> "(duplex body omitted)"
                type?.type == "text" && type.subtype == "event-stream" -> "(event stream omitted)"
                else -> null
            }
            if (omitted != null) {
                observe { span.log(sensitive = true) { "⇠ body $omitted" } }
                return this
            }
            val contentLength = body.contentLength()
            val tee = TeeSource(body.source(), captureLimit) { captured, seen, reachedEnd ->
                // Partial only when provably so: a JSON converter typically stops at the closing brace without
                // reaching EOF, and a body of unknown length cannot be judged.
                val partial = !reachedEnd && contentLength >= 0 && seen < contentLength
                trace.handoff.withCapturedConfig {
                    observe {
                        captured.snippet(contentLength, partial)?.let { snippet -> span.log(sensitive = true) { "⇠ body $snippet" } }
                    }
                }
            }
            newBuilder().body(tee.buffer().asResponseBody(type, contentLength)).build()
        } catch (t: Throwable) {
            if (t.isJvmFatal()) throw t
            this
        }
    }

    /**
     * Runs one telemetry step, containing any non-fatal fault — e.g., a strict-uninstalled resolution (ADR-011)
     * on a flow with no config at all. The HTTP call's own outcome (its response or exception) is never altered by
     * instrumentation; a JVM-fatal error still propagates (ADR-014).
     */
    private inline fun observe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t.isJvmFatal()) throw t
        }
    }

    /** Header lines, one per entry — sensitive: names are symbols but values (`Authorization`) can be user data. */
    private fun Headers.render(): String = buildString {
        this@render.forEach { (name, value) -> append("\n    $name: $value") }
    }

    /**
     * The request body as text, or null when there is none. A **one-shot** body is omitted (writing it here would
     * consume it and break the actual send), and so is a **duplex** one (it streams for the call's lifetime). The
     * body is written into a [CappedSink], so at most [bodyLimit] + 1 bytes are produced however large it is.
     * Best-effort: a binary body reads as garbled text, acceptable for a device-only debug event.
     */
    private fun Request.bodySnippet(): String? {
        val body = body ?: return null
        if (bodyLimit == 0L) return null
        if (body.isDuplex()) return "(duplex body omitted)"
        if (body.isOneShot()) return "(one-shot body omitted)"
        // A declared length past the limit is never written: okio stages a body's single large write (a
        // ByteArray body) whole before any sink below it can stop it, so the cap alone would not bound memory.
        val length = body.contentLength()
        if (length > bodyLimit) return "(body omitted, $length bytes)"
        val capped = CappedSink(captureLimit)
        try {
            val sink = capped.buffer()
            body.writeTo(sink)
            sink.flush()
        } catch (t: Throwable) {
            // SnippetFull stops the write at the cap; a read failure keeps whatever was captured. Never swallow
            // a JVM-fatal error (ADR-014): a compromised process must surface, not read as "no snippet".
            if (t.isJvmFatal()) throw t
        }
        return capped.captured.snippet(length, partial = false)
    }

    /**
     * Up to [bodyLimit] bytes as UTF-8, marked when the body was longer, or when the caller provably stopped
     * reading before its end ([partial]); null when nothing was captured.
     */
    private fun Buffer.snippet(contentLength: Long, partial: Boolean): String? {
        if (size == 0L) return null
        val truncated = size > bodyLimit
        val text = readUtf8(minOf(size, bodyLimit))
        return when {
            truncated && contentLength >= 0 -> "$text… (truncated, $contentLength bytes)"
            truncated -> "$text… (truncated)"
            partial -> "$text… (read partially)"
            else -> text
        }
    }
}

/**
 * Keeps the first [limit] bytes written to it and then stops the writer by throwing [SnippetFull], so snapshotting
 * a request body costs at most [limit] bytes of memory and I/O, not the whole body (ADR-024).
 */
private class CappedSink(private val limit: Long) : Sink {
    val captured = Buffer()

    override fun write(source: Buffer, byteCount: Long) {
        val take = minOf(byteCount, limit - captured.size)
        captured.write(source, take)
        source.skip(byteCount - take)
        if (captured.size >= limit) throw SnippetFull()
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

/**
 * Copies the first [limit] bytes the caller's reads pull through it, and hands them to [onDone] exactly once —
 * with the total bytes seen — when the caller reaches the end of the body (`reachedEnd = true`) or closes it
 * (ADR-024). It only ever reads when the caller does, so it adds no latency and cannot stall on a stream.
 */
private class TeeSource(
    delegate: Source,
    private val limit: Long,
    private val onDone: (captured: Buffer, seen: Long, reachedEnd: Boolean) -> Unit,
) : ForwardingSource(delegate) {
    private val captured = Buffer()
    private var seen = 0L
    private val done = AtomicBoolean(false)

    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read == -1L) {
            finish(reachedEnd = true)
            return read
        }
        seen += read
        val take = minOf(read, limit - captured.size)
        if (take > 0) sink.copyTo(captured, sink.size - read, take)
        return read
    }

    override fun close() {
        try {
            super.close()
        } finally {
            finish(reachedEnd = false)
        }
    }

    private fun finish(reachedEnd: Boolean) {
        if (done.compareAndSet(false, true)) onDone(captured, seen, reachedEnd)
    }
}

/** JVM-fatal throwables never contained (ADR-014) — mirrors core's `isFatalFault()`, which is core-internal. */
internal fun Throwable.isJvmFatal(): Boolean = this is VirtualMachineError || this is ThreadDeath || this is LinkageError

/** Control flow only: stops a body's `writeTo` at the snippet cap. No stack trace, no suppression. */
private class SnippetFull : RuntimeException("kotrace: body snippet limit reached", null, false, false)
