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

    /** How events reach a sink — see [EmitMode]. Default [EmitMode.OnFailure] (fan out only on failure). */
    @Volatile
    var emitMode: EmitMode = EmitMode.OnFailure

    /**
     * The sink each event is emitted to immediately when [emitMode] is [EmitMode.Live] / [EmitMode.Both].
     * A local debug sink (Logcat); null disables live emission. It receives every event, `local` included.
     */
    @Volatile
    var liveSink: TraceSink? = null

    /**
     * Optional field-aware scrubber applied to every record on the **remote** fan-out ([SpanCollector.report]).
     * Null (default) means no scrubbing. Not the body guard — that is [SpanEvent.local]; see [Redactor].
     */
    @Volatile
    var redactor: Redactor? = null
}

/**
 * Emits [event] to [KotraceLog.liveSink] now, if live emission is on — the per-event path parallel to the
 * failure fan-out. Applies no [Redactor]: the live sink is a local debug sink, so it shows everything
 * (including captured bodies); redaction is a remote-fan-out concern ([SpanCollector.report]).
 */
private fun Span.emitLive(event: SpanEvent) {
    if (KotraceLog.emitMode == EmitMode.OnFailure) return
    val sink = KotraceLog.liveSink ?: return
    sink.emit(toRecord(event.level, event.message, atNanos = event.atNanos))
}

/**
 * Appends a log [message] to the span active on the coroutine — the primary, suspend log verb. Filtered
 * by the global [KotraceLog.captureLevels]; [message] is a lambda so a dropped level costs nothing to
 * build. No active span → no-op (safe to call anywhere).
 */
suspend fun logSpan(level: LogLevel, local: Boolean = false, message: () -> String) {
    if (level !in KotraceLog.captureLevels) return
    currentCoroutineContext()[SpanContext]?.span?.let { span ->
        val event = SpanEvent(level, message(), System.nanoTime(), local)
        span.events += event
        span.emitLive(event)
    }
}

/**
 * Non-suspend log verb for code running **on** a coroutine's thread without a `coroutineContext` handle —
 * a synchronous factory or callback invoked mid-flow. Resolves the span through the [currentSpan]
 * ThreadLocal bridge. No active span → no-op.
 */
fun logSpanHere(level: LogLevel, local: Boolean = false, message: () -> String) {
    currentSpan()?.log(level, local, message)
}

/**
 * Appends to a span **held directly** — for a bridge that already carries the span across a thread
 * boundary (the OkHttp request tag, a Room callback), where neither the coroutine context nor the
 * [currentSpan] ThreadLocal is available on the executing thread. Filters by [KotraceLog.captureLevels].
 */
fun Span.log(level: LogLevel, local: Boolean = false, message: () -> String) {
    if (level !in KotraceLog.captureLevels) return
    val event = SpanEvent(level, message(), System.nanoTime(), local)
    events += event
    emitLive(event)
}
