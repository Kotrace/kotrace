package dev.kotrace.room

import dev.kotrace.KotraceLog
import dev.kotrace.LogLevel
import dev.kotrace.collectTrace
import dev.kotrace.trace
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The callback logs the executed SQL onto the active span (resolved via the `currentSpan()` ThreadLocal,
 * which is live on the query thread) and never logs the bound values.
 */
class TracingQueryCallbackTest {

    @After
    fun resetCapture() {
        KotraceLog.captureLevels = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
    }

    @Test
    fun `logs SQL text to the active span, never the bind args`() = runTest {
        KotraceLog.captureLevels = setOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.ERROR)
        val callback = TracingQueryCallback()

        val spans = collectTrace {
            trace("account.get") {
                callback.onQuery("SELECT * FROM account WHERE mail = ?", listOf("user@example.com"))
            }
        }

        val event = spans.single().events.single()
        assertEquals(LogLevel.DEBUG, event.level)
        assertTrue("logs the SQL template", event.message.contains("SELECT * FROM account WHERE mail = ?"))
        assertFalse("never logs the bound value", event.message.contains("user@example.com"))
    }

    @Test
    fun `DEBUG SQL is dropped under the default capture set`() = runTest {
        val spans = collectTrace {
            trace("account.get") {
                TracingQueryCallback().onQuery("SELECT 1", emptyList())
            }
        }
        assertTrue("DEBUG off by default", spans.single().events.isEmpty())
    }
}
