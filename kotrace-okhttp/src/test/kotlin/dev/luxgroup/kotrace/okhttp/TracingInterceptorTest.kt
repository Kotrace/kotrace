package dev.luxgroup.kotrace.okhttp

import dev.luxgroup.kotrace.Span
import dev.luxgroup.kotrace.SpanStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the interceptor puts a spec-correct `traceparent` on the wire from a span carried on the
 * request tag (Trap 1: the tag is the bridge, since the coroutine context is invisible on OkHttp's
 * thread), and reflects the response back onto the span.
 */
class TracingInterceptorTest {

    private fun span(traceId: String = "a".repeat(32), spanId: String = "b".repeat(16)) =
        Span(traceId = traceId, spanId = spanId, parentId = null, name = "HTTP GET /accounts", startNanos = 0L)

    private fun request(span: Span?) = Request.Builder()
        .url("https://graph.example.com/accounts")
        .apply { if (span != null) tag(Span::class.java, span) }
        .build()

    /** Drives the interceptor against a MockK'd chain, capturing the request it actually sends. */
    private fun run(request: Request, code: Int): Request {
        val sent = slot<Request>()
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { proceed(capture(sent)) } answers {
                Response.Builder()
                    .request(sent.captured).protocol(Protocol.HTTP_1_1).code(code).message("msg").build()
            }
        }
        TracingInterceptor().intercept(chain)
        return sent.captured
    }

    @Test
    fun `injects traceparent from the span tag`() {
        val span = span()
        val sent = run(request(span), code = 200)

        assertEquals("00-${"a".repeat(32)}-${"b".repeat(16)}-01", sent.header("traceparent"))
        assertEquals("200", span.attributes["http.status"])
        assertEquals(SpanStatus.OK, span.status)
    }

    @Test
    fun `a 500 marks the span ERROR and records the status`() {
        val span = span()
        run(request(span), code = 500)

        assertEquals(SpanStatus.ERROR, span.status)
        assertEquals("500", span.attributes["http.status"])
    }

    @Test
    fun `a request with no span tag passes through without a header`() {
        val sent = run(request(span = null), code = 200)

        assertNull("no span, no traceparent", sent.header("traceparent"))
    }
}