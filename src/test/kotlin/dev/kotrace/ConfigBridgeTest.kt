package dev.kotrace

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Proves the [TraceConfig] getter pair, mirroring [SpanBridgeTest]: the suspend [currentConfig] reads the
 * config from the `CoroutineContext` directly (the source of truth), while the non-suspend
 * [currentThreadConfig] reads the [ThreadContextElement] mirror — and both resolve to the same element
 * active for the flow. The suspend read is the public surface a consumer's own suspend helper uses; the
 * mirror serves the non-suspend bridge (the OkHttp `Call.Factory`, a Room callback, the log verbs).
 */
class ConfigBridgeTest {

    @Test
    fun `currentConfig resolves the ambient config and both getters agree`() = runTest {
        assertNull("no config before any is installed", currentConfig())

        val config = TraceConfig(emptyList())
        withContext(config) {
            assertSame("the suspend read returns the context element", config, currentConfig())
            assertSame("the mirror agrees on the same thread", config, currentThreadConfig())
        }

        assertNull("cleared once the scope unwinds", currentConfig())
    }

    @Test
    fun `a nested config overrides, and the suspend read sees the innermost, restored on unwind`() = runTest {
        val outer = TraceConfig(emptyList())
        val inner = TraceConfig(emptyList())
        withContext(outer) {
            assertSame("outer active", outer, currentConfig())
            withContext(inner) {
                assertSame("innermost element wins on the direct read", inner, currentConfig())
            }
            assertSame("restored to outer after the inner scope unwinds", outer, currentConfig())
        }
    }
}
