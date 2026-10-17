package dev.kotrace

import dev.kotrace.samples.SpanSamples
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `span` inside `flow { }`: trace the work, emit outside the span (the documented idiom and why). */
class FlowUsageTest {

    @Test fun `the sample traces the work and emits outside the span`() = runTest {
        val collector = SpanCollector()
        val prices = withContext(collector) { SpanSamples.spanInFlowUsage { 42 }.toList() }

        assertEquals(listOf(42), prices)
        assertEquals(listOf("price.fetch"), collector.spans.map { it.name })
    }

    @Test fun `emitting from inside a span breaks the flow invariant`() {
        val broken = flow { span("price.fetch") { emit(42) } }

        val failure = assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { broken.toList() } }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("Flow invariant is violated"))
    }
}
