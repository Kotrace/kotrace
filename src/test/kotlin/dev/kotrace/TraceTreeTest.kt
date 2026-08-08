package dev.kotrace

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A nested failure — handler → service → store, where the store throws — builds a linked span tree with
 * ERROR carried from the leaf up to the root, the original throwable attached at the birthplace, and one
 * `traceId` across the whole trace.
 */
class TraceTreeTest {

    @Test
    fun `nested failure builds a linked span tree with ERROR to the leaf`() = runTest {
        val spans = collectTrace {
            trace("ui.handler") {
                trace("service") {
                    trace("store.write") {
                        throw IllegalStateException("disk write failed: SQLITE_FULL")
                    }
                }
            }
        }

        println("\n" + spans.formatTree())

        assertEquals("three layers stamped", 3, spans.size)
        assertEquals("one traceId across the trace", 1, spans.map { it.traceId }.distinct().size)

        val handler = spans.first { it.name == "ui.handler" }
        val service = spans.first { it.name == "service" }
        val store = spans.first { it.name == "store.write" }

        assertNull("root has no parent", handler.parentId)
        assertEquals("service's parent is the handler span", handler.spanId, service.parentId)
        assertEquals("store's parent is the service span", service.spanId, store.parentId)

        assertTrue("ERROR propagates from the leaf up to the root", spans.all { it.status == SpanStatus.ERROR })
        assertTrue("the original throwable is attached at the birthplace", store.error is IllegalStateException)
    }
}
