package dev.kotrace.okhttp

import dev.kotrace.Span
import dev.kotrace.SpanCollector
import dev.kotrace.currentSpan
import dev.kotrace.span
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Closes the loop the interceptor test left open: proves the factory opens the HTTP call's **own** span as
 * a child of the active span and tags it on the outgoing request — no hand-set tag — because it runs on
 * the coroutine's thread where [span] mirrored the parent and the collector (Trap 1).
 */
class TracingCallFactoryTest {

    @Test
    fun `opens an http child span of the active span and tags the request with it`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }
        val factory = TracingCallFactory(delegate, mapOf("layer" to "http"))

        var parentId: String? = null
        withContext(SpanCollector()) {
            span("AccountRepository.fetch") {
                parentId = currentSpan()!!.spanId
                factory.newCall(Request.Builder().url("https://graph.example.com/accounts").build())
            }
        }

        val tagged = sent.captured.tag(Span::class.java)!!
        assertEquals("http GET", tagged.name)
        assertEquals("http", tagged.attributes["layer"])
        assertEquals("child of the active span", parentId, tagged.parentId)
    }

    @Test
    fun `the default span name never carries the URL path or query`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }

        withContext(SpanCollector()) {
            span("UserRepository.load") {
                TracingCallFactory(delegate).newCall(
                    Request.Builder().url("https://api.example.com/users/alice@example.com?token=s3cr3t").build(),
                )
            }
        }

        // The name becomes `operation` on every record — the non-sensitive crash record and toJson included —
        // with no policy gate over it, so the path (user data) must never reach it (ADR-021).
        val name = sent.captured.tag(Span::class.java)!!.name
        assertEquals("http GET", name)
        assertFalse("no path in the span name", name.contains("alice"))
        assertFalse("no query in the span name", name.contains("s3cr3t"))
    }

    @Test
    fun `a caller-supplied spanName maps the request to a sanitized route template`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }
        val factory = TracingCallFactory(delegate, spanName = { request ->
            val template = if (request.url.pathSegments.firstOrNull() == "users") "/users/{id}" else "/other"
            "http ${request.method} $template"
        })

        withContext(SpanCollector()) {
            span("UserRepository.load") {
                factory.newCall(Request.Builder().url("https://api.example.com/users/alice@example.com").build())
            }
        }

        assertEquals("http GET /users/{id}", sent.captured.tag(Span::class.java)!!.name)
    }

    @Test
    fun `defaults to no attributes when none are passed in`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }
        val factory = TracingCallFactory(delegate)

        withContext(SpanCollector()) {
            span("AccountRepository.fetch") {
                factory.newCall(Request.Builder().url("https://graph.example.com/accounts").build())
            }
        }

        assertEquals(emptyMap<String, String>(), sent.captured.tag(Span::class.java)!!.attributes)
    }

    @Test
    fun `a spanName mapper that throws falls back to the default name instead of failing the call`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }
        val calls = TracingCallFactory(delegate, spanName = { error("route table miss") })

        withContext(SpanCollector()) {
            calls.newCall(Request.Builder().url("https://graph.example.com/accounts").build())
        }

        assertEquals("http GET", sent.captured.tag(Span::class.java)!!.name)
    }

    @Test
    fun `a call with tracing off is left untagged`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }

        TracingCallFactory(delegate).newCall(Request.Builder().url("https://x.example.com").build())

        assertNull(sent.captured.tag(Span::class.java))
    }
}