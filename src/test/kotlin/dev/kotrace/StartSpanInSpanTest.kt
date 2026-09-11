package dev.kotrace

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The non-suspend [startSpan] resolves its parent from the [currentThreadSpan] mirror, not the coroutine
 * context directly. Called synchronously inside a suspend [span] — on the coroutine's own thread, where
 * [SpanContext]'s [kotlinx.coroutines.ThreadContextElement] has already mirrored the enclosing span — it
 * still finds that span as its parent: same `traceId`, `parentId` pointing at the enclosing span, and it
 * lands in the same [SpanCollector] via the [currentThreadCollector] mirror. Contrast: opened at the root
 * with no enclosing span, it roots a fresh trace.
 */
@OptIn(NonSuspendTracingBridge::class)
class StartSpanInSpanTest {

    @Test
    fun `startSpan inside span parents to the enclosing span via the thread-local mirror`() = runTest {
        val spans = collectTrace {
            span("root") {
                // Non-suspend bridge call, synchronous on the coroutine's thread.
                val manual = startSpan("manual")
                manual.end()
            }
        }

        assertEquals("both spans collected", 2, spans.size)

        val root = spans.first { it.name == "root" }
        val manual = spans.first { it.name == "manual" }

        assertNull("root has no parent", root.parentId)
        assertEquals("manual parents to the enclosing span", root.spanId, manual.parentId)
        assertEquals("one traceId across both", root.traceId, manual.traceId)
    }

    @Test
    fun `startSpan from a callback on another thread misparents - the mirror is not there`() = runTest {
        lateinit var rootSpan: Span
        lateinit var manualSpan: Span

        span("root") {
            rootSpan = currentSpan()!!

            // A raw callback thread that never resumed this coroutine, so SpanContext's
            // ThreadContextElement never ran updateThreadContext on it — the ThreadLocal mirror
            // (a plain, non-inheritable ThreadLocal) holds no span there. This is the OkHttp
            // dispatcher-thread case the @NonSuspendTracingBridge warning is about.
            val worker = Thread {
                manualSpan = startSpan("manual").also { it.end() }
            }
            worker.start()
            worker.join()
        }

        assertNull("off-thread: no ambient span mirror, so it silently roots", manualSpan.parentId)
        assertNotEquals(
            "off-thread manual span is a separate trace, not the enclosing span's child",
            rootSpan.traceId, manualSpan.traceId,
        )
    }

    @Test
    fun `startSpan with no enclosing span roots a fresh trace`() = runTest {
        val spans = collectTrace {
            val manual = startSpan("manual")
            manual.end()
        }

        assertEquals("collected", 1, spans.size)
        assertNull("no ambient span, so it roots a fresh trace", spans.single().parentId)
    }

    @Test
    fun `span nested under startSpan misparents to the old ambient - not the manual span`() = runTest {
        val spans = collectTrace {
            span("outer") {
                // startSpan itself parents correctly off the thread-local mirror (child of outer)...
                val manual = startSpan("manual")

                // ...but it never installs its span into the coroutine context, so this nested suspend
                // span reads the *old* ambient SpanContext (still `outer`) as its parent — NOT `manual`.
                span("child") { }

                manual.end()
            }
        }

        assertEquals("all three collected", 3, spans.size)

        val outer = spans.first { it.name == "outer" }
        val manual = spans.first { it.name == "manual" }
        val child = spans.first { it.name == "child" }

        assertNull("outer is the root", outer.parentId)
        assertEquals("manual parents to outer via the mirror", outer.spanId, manual.parentId)
        assertEquals(
            "child misparents to the old ambient (outer), a sibling of manual, not its child",
            outer.spanId, child.parentId,
        )
        assertNotEquals("child is not under the manual span", manual.spanId, child.parentId)
        assertEquals("still one trace though", outer.traceId, child.traceId)
    }

    @Test
    fun `span nested under a root startSpan roots its own trace`() = runTest {
        lateinit var manual: Span
        val spans = collectTrace {
            // No enclosing suspend span, so nothing in the context and nothing in the mirror.
            manual = startSpan("manual")
            span("child") { } // reads an empty SpanContext -> roots a fresh, separate trace
            manual.end()
        }

        val collectedManual = spans.first { it.name == "manual" }
        val child = spans.first { it.name == "child" }

        assertNull("manual roots (no ambient anywhere)", collectedManual.parentId)
        assertNull("child roots too — startSpan never became the ambient parent", child.parentId)
        assertNotEquals(
            "child is a separate trace from the manual span",
            manual.traceId, child.traceId,
        )
    }
}
