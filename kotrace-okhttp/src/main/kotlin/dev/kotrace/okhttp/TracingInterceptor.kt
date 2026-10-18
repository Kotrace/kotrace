package dev.kotrace.okhttp

import dev.kotrace.Span
import dev.kotrace.SpanStatus
import dev.kotrace.TRACEPARENT_HEADER
import dev.kotrace.adapter.FaultPhase
import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.end
import dev.kotrace.bridge.runIsolated
import dev.kotrace.bridge.startSpan
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
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens and closes the HTTP [Span] for each execution of a call [TracingCallFactory] tagged: injects the W3C
 * `traceparent` so the backend continues the trace, and stamps the span's status, timing and error. It emits under
 * the flow's captured configuration (ADR-023), and a telemetry fault never changes the call's response or exception: it
 * reaches the `FaultHook` as `FaultPhase.HTTP` (ADR-035), and only an armed strict check fails the call. An
 * untagged request passes through untouched. Install it with `addInterceptor` (once per call), not
 * `addNetworkInterceptor` (once per hop).
 *
 * [Level] is a verbosity dial like OkHttp's `HttpLoggingInterceptor`, not a severity. Every breadcrumb that
 * carries a URL path, a header or a body is logged **sensitive**, since each can hold user data
 * (`/users/alice@example.com`, `Authorization`); a report sink drops those unless its policy opts in. The span's
 * method, status and timing are the only non-sensitive facts.
 *
 * A call its caller canceled ends its span ERROR without recording an exception, like a cancelled span (ADR-022).
 * A call that hit its `callTimeout` is a failure and is recorded, although OkHttp enforces the timeout by canceling
 * the call. The span's `http.outcome` attribute (`canceled` or `call_timeout`) tells them apart for a non-sensitive sink.
 * A timeout records OkHttp's own cancellation exception; the caller receives it wrapped in an
 * `InterruptedIOException("timeout")`.
 *
 * [Level.BODY] is bounded and never alters the transport (ADR-024):
 * - **Request:** a body declared longer than [bodyLimit] is not written, only its size is logged. Otherwise it is
 *   written into a sink that stops after [bodyLimit] + 1 bytes. A one-shot or duplex body is omitted. An
 *   unknown-length body written as one large chunk is still staged whole once before the cap stops it.
 * - **Response:** nothing is read ahead. The first [bodyLimit] + 1 bytes are copied **as the caller reads**, and
 *   the snippet is logged when the caller reaches the end or closes the body, so the call gains no latency and a
 *   stream is never waited on. An unread body logs nothing; an event stream or duplex response is logged as
 *   omitted.
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

    // Runs on OkHttp's thread, off the coroutine frame, so it goes through the captured TraceHandoff (ADR-023).
    @OptIn(NonSuspendTracingBridge::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val trace = chain.request().tag(HttpTrace::class.java)
            ?: return chain.proceed(chain.request())
        return trace.handoff.withCapturedConfig { traced(chain, trace) }
    }

    /**
     * One execution, one span (ADR-023): a cloned or retried call gets its own span. Every telemetry step runs under
     * [runIsolated], so a telemetry fault can neither fail the call nor replace its exception (ADR-035).
     */
    @OptIn(NonSuspendTracingBridge::class)
    private fun traced(chain: Interceptor.Chain, trace: HttpTrace): Response {
        val handoff = trace.handoff
        val span = startSpan(
            trace.spanName,
            trace.traits,
            parent = handoff.parent,
            collector = handoff.collector,
            scopeId = handoff.scopeId,
        )
        // The inherited trace id may not be W3C-shaped, and toTraceparent refuses it (ADR-028): send the request
        // without the header rather than fail it.
        val traceparent = runIsolated(phase = null, recover = { null }) { span.toTraceparent() }
        // Never keep a caller-set traceparent: it would tie the request to an unrelated trace.
        val request = chain.request().newBuilder().apply {
            if (traceparent == null) removeHeader(TRACEPARENT_HEADER) else header(TRACEPARENT_HEADER, traceparent)
        }.build()

        val path = request.url.encodedPath
        runIsolated(FaultPhase.HTTP, "http.request.log", recover = {}) {
            if (traceparent == null && level >= Level.BASIC) span.log { "traceparent omitted: the trace id is not a W3C trace-id" }
            if (level >= Level.HEADERS) span.log(sensitive = true) { "⇢ ${request.method} $path headers${request.headers.render()}" }
            if (level >= Level.BODY) request.bodySnippet()?.let { snippet -> span.log(sensitive = true) { "⇢ body $snippet" } }
        }

        val startNanos = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            // A canceled call surfaces as an IOException ("Canceled", "Socket closed"); any other throwable is a
            // failure whatever state the call is in.
            val canceled = t is IOException && isCanceled(chain)
            val timedOut = canceled && callTimeoutSpent(chain, span)
            runIsolated(FaultPhase.HTTP, "http.failure.log", escaping = t, recover = {}) {
                // Before the log and the record, so every record of this span carries it.
                if (canceled) {
                    span.putAttribute(HttpSpan.OUTCOME, if (timedOut) HttpSpan.CALL_TIMEOUT else HttpSpan.CANCELED)
                }
                if (level >= Level.BASIC) {
                    span.log(sensitive = true) {
                        val outcome = when {
                            timedOut -> "call timeout"
                            canceled -> "canceled"
                            else -> t.message
                        }
                        "✗ ${request.method} $path: $outcome"
                    }
                }
            }
            if (canceled && !timedOut) span.end(SpanStatus.ERROR) else span.end(SpanStatus.ERROR, t)
            throw t
        }

        // Capture plain values, never the Response: a report adapter evaluates the lazy messages after the body closed.
        val code = response.code
        val headers = response.headers
        // The span ends even when a strict failure propagates from a step below (ADR-035).
        try {
            runIsolated(FaultPhase.HTTP, "http.response.log", recover = {}) {
                span.putAttribute(HttpSpan.STATUS, code.toString())
                val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                if (level >= Level.BASIC) span.log(sensitive = true) { "← $code ${request.method} $path (${tookMs}ms)" }
                if (level >= Level.HEADERS) span.log(sensitive = true) { "⇠ $code headers${headers.render()}" }
            }
            return if (level >= Level.BODY) response.withBodyCapture(request, span, trace) else response
        } catch (t: Throwable) {
            // The caller never gets this response, so nothing else would close it and release its connection.
            runCatching { response.body?.close() }.exceptionOrNull()?.takeIf { it !== t }?.let(t::addSuppressed)
            throw t
        } finally {
            span.end(if (response.isSuccessful) SpanStatus.OK else SpanStatus.ERROR)
        }
    }

    /**
     * The response with its body wrapped in a [TeeSource] that logs the snippet onto [span] once the caller is done
     * reading, on whatever thread that is. A stream is logged as omitted; on a fault the original response is returned.
     */
    @OptIn(NonSuspendTracingBridge::class)
    private fun Response.withBodyCapture(request: Request, span: Span, trace: HttpTrace): Response {
        if (bodyLimit == 0L) return this
        return runIsolated(FaultPhase.HTTP, "http.response.body", recover = { this }) {
            // Nullable on OkHttp 4 (the declared floor, ADR-027), never null on 5.
            val body = body ?: return this
            val type = body.contentType()
            val omitted = when {
                request.body?.isDuplex() == true -> "(duplex body omitted)"
                type?.type == "text" && type.subtype == "event-stream" -> "(event stream omitted)"
                else -> null
            }
            if (omitted != null) {
                runIsolated(FaultPhase.HTTP, "http.response.body.omitted", recover = {}) { span.log(sensitive = true) { "⇠ body $omitted" } }
                return this
            }
            val contentLength = body.contentLength()
            val tee = TeeSource(body.source(), captureLimit) { captured, seen, reachedEnd ->
                // Partial only when provably so: a JSON converter often stops at the closing brace, before EOF.
                val partial = !reachedEnd && contentLength >= 0 && seen < contentLength
                trace.handoff.withCapturedConfig {
                    runIsolated(FaultPhase.HTTP, "http.response.body.log", recover = {}) {
                        captured.snippet(contentLength, partial)?.let { snippet -> span.log(sensitive = true) { "⇠ body $snippet" } }
                    }
                }
            }
            newBuilder().body(tee.buffer().asResponseBody(type, contentLength)).build()
        }
    }

    /**
     * Whether the call's `callTimeout` is spent. The span opens after earlier interceptors, so its clock trails the
     * call's: the last tenth of the budget counts too, reading a late cancel as a timeout rather than losing one.
     */
    private fun callTimeoutSpent(chain: Interceptor.Chain, span: Span): Boolean =
        runIsolated(phase = null, recover = { false }) {
            val budget = chain.call().timeout().timeoutNanos()
            budget > 0 && System.nanoTime() - span.startNanos >= budget - budget / 10
        }

    /**
     * Whether the call itself was canceled; a chain that cannot say (a test double) reads as not canceled. Like
     * [callTimeoutSpent], a probe, not a telemetry step: a chain that cannot answer is no fault to report.
     */
    private fun isCanceled(chain: Interceptor.Chain): Boolean =
        runIsolated(phase = null, recover = { false }) { chain.call().isCanceled() }

    private fun Headers.render(): String = buildString {
        this@render.forEach { (name, value) -> append("\n    $name: $value") }
    }

    /**
     * The request body as text, or null when there is none. A one-shot body is omitted (writing it would consume
     * it), and so is a duplex one. Best-effort: a binary body reads as garbled text.
     */
    private fun Request.bodySnippet(): String? {
        val body = body ?: return null
        if (bodyLimit == 0L) return null
        if (body.isDuplex()) return "(duplex body omitted)"
        if (body.isOneShot()) return "(one-shot body omitted)"
        // okio stages a single large write whole before any sink can stop it, so the cap alone would not bound memory.
        val length = body.contentLength()
        if (length > bodyLimit) return "(body omitted, $length bytes)"
        val capped = CappedSink(captureLimit)
        // A failure keeps whatever was captured.
        runIsolated(FaultPhase.HTTP, "http.request.body", recover = {}) {
            try {
                val sink = capped.buffer()
                body.writeTo(sink)
                sink.flush()
            } catch (_: SnippetFull) {
                // The cap stops the write, by design: no fault.
            }
        }
        return capped.captured.snippet(length, partial = false)
    }

    /** Up to [bodyLimit] bytes as UTF-8, marked when truncated or [partial]; null when nothing was captured. */
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
 * The sink a request body is written into to capture its snippet (ADR-024). A request body can be written more than
 * once, so this copy never touches the one sent on the wire.
 *
 * It keeps the first [limit] bytes in [captured] and discards the rest, since a [Sink] must consume every byte it is
 * given. Once [limit] bytes are kept it throws [SnippetFull], so `writeTo` stops instead of serializing the rest of a
 * large body. [limit] is one byte over the snippet length: that extra byte is how the snippet knows it was truncated.
 *
 * The cap bounds the copy, not memory: okio stages a single large write whole before this sink sees it, so the caller
 * omits a body whose declared length is already over the limit.
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
 * Wraps a response body's source to capture its snippet while the caller reads (ADR-024). A response body can be read
 * only once, so kotrace never reads it itself: every read passes through unchanged and the first [limit] bytes are
 * copied aside. It adds no read and no wait, and keeps nothing beyond those bytes.
 *
 * [onDone] runs exactly once, on the caller's thread, when the caller reaches the end of the body or closes it,
 * whichever comes first. It receives the copied bytes, `seen` (every byte read through, past [limit] too) and
 * `reachedEnd`, false when the caller closed before the end, as a JSON converter does when it stops at the closing
 * brace. A body the caller never reads and never closes never calls it.
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


/**
 * Control flow only: stops a body's `writeTo` at the snippet cap. It records no stack trace, by overriding
 * [fillInStackTrace] rather than through the constructor that takes `writableStackTrace`, which Android has only
 * from API 24.
 */
private class SnippetFull : RuntimeException("kotrace: body snippet limit reached") {
    override fun fillInStackTrace(): Throwable = this
}
