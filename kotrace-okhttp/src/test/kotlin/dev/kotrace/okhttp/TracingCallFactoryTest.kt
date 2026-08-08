package dev.kotrace.okhttp

import dev.kotrace.Span
import dev.kotrace.trace
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Closes the loop the interceptor test left open: proves the span reaches the outgoing request from the
 * coroutine context — no hand-set tag — because the factory runs on the coroutine's thread where
 * [trace] has mirrored the span (Trap 1).
 */
class TracingCallFactoryTest {

    @Test
    fun `tags the outgoing request with the active span`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }
        val factory = TracingCallFactory(delegate)

        trace("HTTP GET /accounts") {
            factory.newCall(Request.Builder().url("https://graph.example.com/accounts").build())
        }

        assertEquals("HTTP GET /accounts", sent.captured.tag(Span::class.java)?.name)
    }

    @Test
    fun `a call with no active span is left untagged`() = runTest {
        val sent = slot<Request>()
        val delegate = mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() }

        TracingCallFactory(delegate).newCall(Request.Builder().url("https://x.example.com").build())

        assertNull(sent.captured.tag(Span::class.java))
    }
}