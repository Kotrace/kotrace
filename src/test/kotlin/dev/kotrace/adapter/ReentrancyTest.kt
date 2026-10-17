// Tests inspect the raw span timeline (ADR-028); nothing here reaches a sink.
@file:OptIn(dev.kotrace.UnredactedTraceRead::class)

package dev.kotrace.adapter

import dev.kotrace.Kotrace
import dev.kotrace.TraceConfig
import dev.kotrace.collectTrace
import dev.kotrace.currentSpan
import dev.kotrace.currentThreadSpan
import dev.kotrace.event.LogEvent
import dev.kotrace.event.LogRecord
import dev.kotrace.event.TraceRecord
import dev.kotrace.event.emitLog
import dev.kotrace.event.log
import dev.kotrace.span
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Kotrace called back from inside itself on the same thread (ADR-025): a live adapter that logs through kotrace
 * (a Timber tree forwarding to [emitLog]) and an [Kotrace.install] provider that emits while it builds the
 * adapters. Both used to recurse until a fatal `StackOverflowError` reached the traced code.
 */
class ReentrancyTest {

    /** A live adapter that, like a forwarding logger, echoes every record it receives back through kotrace. */
    private class EchoingLive(private val echo: () -> Unit) : LiveAdapter {
        override val policy = object : TracePolicy {}
        val messages = mutableListOf<String>()
        override fun onLive(record: TraceRecord) {
            messages += (record as LogRecord).message
            echo()
        }
    }

    private class CollectingLive : LiveAdapter {
        override val policy = object : TracePolicy {}
        val messages = mutableListOf<String>()
        override fun onLive(record: TraceRecord) {
            messages += (record as LogRecord).message
        }
    }

    @Before fun clean() = Kotrace.resetForTest()
    @After fun reset() = Kotrace.resetForTest()

    @Test fun `a live adapter that logs through kotrace does not recurse`() {
        val live = EchoingLive { emitLog { "echo" } }
        Kotrace.install(listOf(live))

        emitLog { "first" }
        emitLog { "second" }

        assertEquals("each emit fans out once; the echo is not fanned back in", listOf("first", "second"), live.messages)
    }

    @Test fun `an adapter logging onto the current span stores the log but does not fan it back out`() = runTest {
        val live = EchoingLive { currentThreadSpan()?.log { "echo" } }

        val spans = collectTrace {
            withContext(TraceConfig(listOf(live))) {
                span("op") { currentSpan()?.log { "work" } }
            }
        }

        assertEquals("the adapter saw the span's own log only", listOf("work"), live.messages)
        val stored = spans.single { it.name == "op" }.events.filterIsInstance<LogEvent>().map { it.message }
        assertEquals("the echo is still stored on the span, for the report path", listOf("work", "echo"), stored)
    }

    @Test fun `a provider that emits while resolving is a no-op, not a recursion`() {
        val live = CollectingLive()
        Kotrace.install {
            emitLog { "while resolving" }
            listOf(live)
        }

        emitLog { "after" }

        assertEquals("the provider's own emit reached nothing; the configuration resolved normally", listOf("after"), live.messages)
    }

    @Test fun `under strict mode a provider that emits neither throws nor wipes the configuration`() {
        Kotrace.strictWhenNotInstalled()
        val live = CollectingLive()
        Kotrace.install {
            emitLog { "while resolving" }
            listOf(live)
        }

        emitLog { "after" }

        assertEquals("the nested emit saw an installed (empty) configuration, not a missing one", listOf("after"), live.messages)
    }
}
