package dev.kotrace.okhttp

import dev.kotrace.Kotrace
import dev.kotrace.LiveAdapter
import dev.kotrace.SpanCollector
import dev.kotrace.TracePolicy
import dev.kotrace.event.TraceRecord
import dev.kotrace.span
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The factory half of the OkHttp bridge (ADR-023): on the coroutine's thread, where the trace context still
 * resolves, it captures that context and tags it — with the span's name and attributes — on the outgoing
 * request. It opens **no** span itself: the interceptor opens one per execution, so an unexecuted call leaves
 * nothing open. (The end-to-end behavior on OkHttp's dispatcher thread is in [OkHttpDispatcherBridgeTest].)
 */
class TracingCallFactoryTest {

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    private fun capturingDelegate(): Pair<Call.Factory, io.mockk.CapturingSlot<Request>> {
        val sent = slot<Request>()
        return mockk<Call.Factory> { every { newCall(capture(sent)) } returns mockk() } to sent
    }

    @Test
    fun `tags the request with the flow's context and span identity, opening no span until executed`() = runTest {
        val (delegate, sent) = capturingDelegate()
        val factory = TracingCallFactory(delegate, mapOf("layer" to "http"))
        val collector = SpanCollector()

        withContext(collector) {
            span("AccountRepository.fetch") {
                factory.newCall(Request.Builder().url("https://graph.example.com/accounts").build())
            }
        }

        val trace = requireNotNull(sent.captured.tag(HttpTrace::class.java)) { "the request is tagged" }
        assertEquals("http GET", trace.spanName)
        assertEquals("http", trace.attributes["layer"])
        assertEquals(
            "a call that is created but never executed opens no span",
            listOf("AccountRepository.fetch"),
            collector.spans.map { it.name },
        )
    }

    @Test
    fun `the default span name never carries the URL path or query`() = runTest {
        val (delegate, sent) = capturingDelegate()

        withContext(SpanCollector()) {
            span("UserRepository.load") {
                TracingCallFactory(delegate).newCall(
                    Request.Builder().url("https://api.example.com/users/alice@example.com?token=s3cr3t").build(),
                )
            }
        }

        // The name becomes `operation` on every record — the non-sensitive crash record and toJson included —
        // with no policy gate over it, so the path (user data) must never reach it (ADR-021).
        val name = sent.captured.tag(HttpTrace::class.java)!!.spanName
        assertEquals("http GET", name)
        assertFalse("no path in the span name", name.contains("alice"))
        assertFalse("no query in the span name", name.contains("s3cr3t"))
    }

    @Test
    fun `a caller-supplied spanName maps the request to a sanitized route template`() = runTest {
        val (delegate, sent) = capturingDelegate()
        val factory = TracingCallFactory(delegate, spanName = { request ->
            val template = if (request.url.pathSegments.firstOrNull() == "users") "/users/{id}" else "/other"
            "http ${request.method} $template"
        })

        withContext(SpanCollector()) {
            span("UserRepository.load") {
                factory.newCall(Request.Builder().url("https://api.example.com/users/alice@example.com").build())
            }
        }

        assertEquals("http GET /users/{id}", sent.captured.tag(HttpTrace::class.java)!!.spanName)
    }

    @Test
    fun `defaults to no attributes when none are passed in`() = runTest {
        val (delegate, sent) = capturingDelegate()

        withContext(SpanCollector()) {
            span("AccountRepository.fetch") {
                TracingCallFactory(delegate).newCall(Request.Builder().url("https://graph.example.com/accounts").build())
            }
        }

        assertEquals(emptyMap<String, String>(), sent.captured.tag(HttpTrace::class.java)!!.attributes)
    }

    @Test
    fun `a spanName mapper that throws falls back to the default name instead of failing the call`() = runTest {
        val (delegate, sent) = capturingDelegate()
        val calls = TracingCallFactory(delegate, spanName = { error("route table miss") })

        withContext(SpanCollector()) {
            calls.newCall(Request.Builder().url("https://graph.example.com/accounts").build())
        }

        assertEquals("http GET", sent.captured.tag(HttpTrace::class.java)!!.spanName)
    }

    @Test
    fun `the birth attributes are snapshotted, so mutating the caller's map changes no call`() = runTest {
        val (delegate, sent) = capturingDelegate()
        val attributes = mutableMapOf("layer" to "http")
        val calls = TracingCallFactory(delegate, attributes)

        withContext(SpanCollector()) {
            calls.newCall(Request.Builder().url("https://graph.example.com/accounts").build())
        }
        attributes["layer"] = "mutated" // after newCall, before the call would execute

        assertEquals(mapOf("layer" to "http"), sent.captured.tag(HttpTrace::class.java)!!.attributes)
    }

    @Test
    fun `a call with tracing off is left untagged`() = runTest {
        val (delegate, sent) = capturingDelegate()

        TracingCallFactory(delegate).newCall(Request.Builder().url("https://x.example.com").build())

        assertNull(sent.captured.tag(HttpTrace::class.java))
    }

    @Test
    fun `a process-wide live adapter alone is enough to trace a call made outside any span`() {
        Kotrace.install(listOf(object : LiveAdapter {
            override val policy = object : TracePolicy {}
            override fun onLive(record: TraceRecord) = Unit
        }))
        val (delegate, sent) = capturingDelegate()

        TracingCallFactory(delegate).newCall(Request.Builder().url("https://x.example.com").build())

        assertNotNull("the installed live adapter would consume a root http span", sent.captured.tag(HttpTrace::class.java))
    }
}
