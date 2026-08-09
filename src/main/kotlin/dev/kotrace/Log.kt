package dev.kotrace

import kotlinx.coroutines.currentCoroutineContext

/**
 * Which [LogLevel]s are kept when appended to a span — **one global setting**, not a per-scope override.
 * Set once at startup; the default drops DEBUG so an uninstrumented build stays quiet. Every log verb
 * ([logSpan], [logSpanHere], [Span.log]) filters against this, so capture behaves identically wherever a
 * line is emitted.
 *
 * A `Set`, not a threshold: "INFO + ERROR without DEBUG" is not expressible as a single cut-off.
 */
object KotraceLog {
    @Volatile
    var captureLevels: Set<LogLevel> = setOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)
}

/**
 * Appends a log [message] to the span active on the coroutine — the primary, suspend log verb. Filtered
 * by the global [KotraceLog.captureLevels]; [message] is a lambda so a dropped level costs nothing to
 * build. No active span → no-op (safe to call anywhere).
 */
suspend fun logSpan(level: LogLevel, message: () -> String) {
    if (level !in KotraceLog.captureLevels) return
    currentCoroutineContext()[SpanContext]?.span?.let { it.events += SpanEvent(level, message(), System.nanoTime()) }
}

/**
 * Non-suspend log verb for code running **on** a coroutine's thread without a `coroutineContext` handle —
 * a synchronous factory or callback invoked mid-flow. Resolves the span through the [currentSpan]
 * ThreadLocal bridge. No active span → no-op.
 */
fun logSpanHere(level: LogLevel, message: () -> String) {
    currentSpan()?.log(level, message)
}

/**
 * Appends to a span **held directly** — for a bridge that already carries the span across a thread
 * boundary (the OkHttp request tag, a Room callback), where neither the coroutine context nor the
 * [currentSpan] ThreadLocal is available on the executing thread. Filters by [KotraceLog.captureLevels].
 */
fun Span.log(level: LogLevel, message: () -> String) {
    if (level !in KotraceLog.captureLevels) return
    events += SpanEvent(level, message(), System.nanoTime())
}
