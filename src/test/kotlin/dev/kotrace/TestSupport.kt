package dev.kotrace

import kotlinx.coroutines.withContext

/**
 * Runs [block] as the root of a fresh trace and returns every span collected, error path included — a
 * **test-only** inspection helper. The failure is swallowed here so a test can assert on the tree; a real
 * sink instead reads [SpanCollector] from context at the report site and leaves the throwable to propagate,
 * so this swallow-and-return shape has no place in production and lives in test source (not published API).
 */
internal suspend fun collectTrace(block: suspend () -> Unit): List<Span> {
    val collector = SpanCollector()
    withContext(collector) {
        runCatching { block() }
    }
    return collector.spans
}
