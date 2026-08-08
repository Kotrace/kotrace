package dev.kotrace

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves the thread-local bridge: [SpanContext] mirrors the ambient span into the thread-local that
 * [currentSpan] reads, so a non-suspend caller on the coroutine's thread sees the span active for the
 * flow — and the previous value is restored as each [trace] unwinds.
 */
class SpanBridgeTest {

    @Test
    fun `currentSpan tracks the active trace and restores as traces unwind`() = runTest {
        assertNull("no span before any trace", currentSpan())

        trace("root") {
            assertEquals("root active on the thread", "root", currentSpan()?.name)

            trace("child") {
                assertEquals("child active on the thread", "child", currentSpan()?.name)
            }

            assertEquals("restored to root after the child unwinds", "root", currentSpan()?.name)
        }

        assertNull("restored to none after the root unwinds", currentSpan())
    }
}
