package dev.kotrace

/**
 * A single log line bound to one [Span] — the searchable unit this tracer fans out on failure.
 *
 * [message] is a **static symbol only**, same rule as [Span.name] and [Span.attributes]: an event can
 * reach a crash report, so it must carry no user data. [atNanos] is `System.nanoTime()` at emit,
 * used only to order events within the tree render — not a wall clock.
 *
 * [local] marks an event that must **never leave the device** — an HTTP request/response body captured
 * for on-device debugging, which is PII. `SpanCollector.report` (the remote fan-out) drops every [local]
 * event unconditionally; only a live/Logcat sink and [Span] render show it. This is the hard boundary
 * that lets a body be captured at all without risking it reaching a crash report (EH-MON-4 / OWASP).
 */
data class SpanEvent(
    val level: LogLevel,
    val message: String,
    val atNanos: Long,
    val local: Boolean = false,
)