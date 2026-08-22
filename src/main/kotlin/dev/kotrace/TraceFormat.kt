package dev.kotrace

import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import dev.kotrace.event.exception
import java.util.concurrent.TimeUnit

/**
 * Renders a trace as an indented tree — a **human debug read**, not a [report][reportTrace]. It is egress
 * to a `String` for a person, ungated: it walks every span and every event (`NamedEvent`s and `sensitive`
 * log messages included), so its output must never reach a sink or crash report. `report` is the machine
 * counterpart — flat, id-stamped [dev.kotrace.event.TraceRecord]s, policy-gated, fanned out to adapters.
 * Times are milliseconds relative to the root span's start.
 */
@UnredactedTraceRead
fun List<Span>.renderTree(): String {
    if (isEmpty()) return "(no spans)"
    val root = firstOrNull { it.parentId == null } ?: first()
    val base = root.startNanos
    val failed = any { it.status == SpanStatus.ERROR }

    val sb = StringBuilder()
    sb.append(if (failed) "✗ trace ${root.traceId} FAILED" else "✓ trace ${root.traceId}")
    sb.append("  (total ${durMs(root)}ms)\n")

    fun render(span: Span, depth: Int) {
        val indent = "   ".repeat(depth)
        val relStart = TimeUnit.NANOSECONDS.toMillis(span.startNanos - base)
        val relEnd = span.endNanos?.let { TimeUnit.NANOSECONDS.toMillis(it - base) }
        val children = childrenOf(span)
        sb.append("$indent└─ ${span.name} [${relStart}ms → ${relEnd}ms] ${durMs(span)}ms ${span.status}\n")
        if (span.attributes.isNotEmpty()) sb.append("$indent      attrs: ${span.attributes}\n")
        span.events.sortedBy { it.atNanos }.forEach { event ->
            val line = when (event) {
                is LogEvent -> "${event.attributes}: ${event.message}"
                is NamedEvent -> "event ${event.name} ${event.attributes}"
                is ExceptionEvent -> null
            }
            if (line != null) sb.append("$indent      $line\n")
        }
        // Show the throwable only at the birthplace; ancestors merely carry ERROR status up the path.
        if (span.isBirthplaceAmong(this@renderTree)) {
            span.exception?.let { sb.append("$indent      error: ${it::class.simpleName}: ${it.message}\n") }
        }
        children.forEach { render(it, depth + 1) }
    }
    render(root, 0)
    return sb.toString()
}

private fun durMs(span: Span): Long =
    span.endNanos?.let { TimeUnit.NANOSECONDS.toMillis(it - span.startNanos) } ?: -1
