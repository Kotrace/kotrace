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
        val factory = TracingCallFactory(delegate)

        var parentId: String? = null
        withContext(SpanCollector()) {
            span("AccountRepository.fetch") {
                parentId = currentSpan()!!.spanId
                factory.newCall(Request.Builder().url("https://graph.example.com/accounts").build())
            }
        }

        val tagged = sent.captured.tag(Span::class.java)!!
        assertEquals("http GET /accounts", tagged.name)
        assertEquals("http", tagged.attributes["layer"])
        assertEquals("child of the active span", parentId, tagged.parentId)
    }

    @Test
    fun `a call with tracing off is left untagged`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }

        TracingCallFactory(delegate).newCall(Request.Builder().url("https://x.example.com").build())

        assertNull(sent.captured.tag(Span::class.java))
    }
}