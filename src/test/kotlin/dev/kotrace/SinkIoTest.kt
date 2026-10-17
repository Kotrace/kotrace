package dev.kotrace

import dev.kotrace.event.LogRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.log
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A sink's own I/O is never traced (ADR-025): a bridge asked to trace a call made from inside `onLive` gets no
 * handoff, so an adapter uploading records through an instrumented client cannot feed its own uploads back to
 * itself — a loop the same-thread fan-out guard alone does not stop, since the client's span is fanned out from
 * another thread.
 */
@OptIn(NonSuspendTracingBridge::class)
class SinkIoTest {

    @Test fun `a bridge called from inside a live adapter captures no handoff`() = runTest {
        var sinkCalled = false
        var capturedInSink: TraceHandoff? = null
        val sink = object : LiveAdapter {
            override val policy = object : TracePolicy {}
            val messages = mutableListOf<String>()
            override fun onLive(record: TraceRecord) {
                messages += (record as LogRecord).message
                sinkCalled = true
                capturedInSink = TraceHandoff.captureOrNull() // what TracingCallFactory.newCall does
            }
        }

        val outside = withContext(SpanCollector() + TraceConfig(listOf(sink))) {
            span("flow") {
                currentSpan()!!.log { "work" }
                TraceHandoff.captureOrNull()
            }
        }

        assertEquals(listOf("work"), sink.messages)
        assertEquals(true, sinkCalled)
        assertNull("the sink's own call is not traced", capturedInSink)
        assertNotNull("the flow's own calls still are", outside)
    }
}
