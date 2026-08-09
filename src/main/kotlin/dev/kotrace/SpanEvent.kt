package dev.kotrace

/**
 * A single log line bound to one [Span] — the searchable unit this tracer fans out on failure.
 *
 * [message] is a **static symbol only**, same rule as [Span.name] and [Span.attributes]: an event can
 * reach a crash report, so it must carry no user data. [atNanos] is `System.nanoTime()` at emit,
 * used only to order events within the tree render — not a wall clock.
 */
data class SpanEvent(
    val level: LogLevel,
    val message: String,
    val atNanos: Long,
)