package dev.kotrace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A traced fan-out has parallel children [SpanCollector.add]-ing spans to one collector at once. With a
 * plain `ArrayList` this loses writes or throws; the `CopyOnWriteArrayList` backing makes it safe. Real
 * threads (`Dispatchers.Default`), not the test dispatcher, so the concurrency is genuine.
 */
class SpanCollectorTest {

    @Test
    fun `parallel children add to the collector without loss`() = runBlocking {
        val childCount = 500

        val spans = collectTrace {
            coroutineScope {
                repeat(childCount) { i ->
                    launch(Dispatchers.Default) { span("child-$i") { } }
                }
            }
        }

        assertEquals("every concurrent add survives", childCount, spans.size)
    }
}
