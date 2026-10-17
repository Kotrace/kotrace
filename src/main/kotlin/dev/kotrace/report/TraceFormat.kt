package dev.kotrace.report

import dev.kotrace.Span
import dev.kotrace.SpanStatus
import dev.kotrace.UnredactedTraceRead
import dev.kotrace.event.ExceptionEvent
import dev.kotrace.event.LogEvent
import dev.kotrace.event.NamedEvent
import java.util.concurrent.TimeUnit

/**
 * Renders a trace as an indented tree for a **person** to read, not a [report][reportTrace]. No policy applies: it
 * shows every event, `sensitive` messages included, so its output must never reach a sink or crash report. Times
 * are milliseconds from each root's start; several trees (ADR-026) render one after another.
 */
@UnredactedTraceRead
fun List<Span>.renderTree(): String {
    if (isEmpty()) return "(no spans)"
    val roots = rootsOf(this)
    val tree = TraceTreeIndex(this, roots, FrozenTimelines(this))
    val sb = StringBuilder()
    roots.forEach { root -> sb.renderRoot(root, tree) }
    return sb.toString()
}

private fun StringBuilder.renderRoot(root: Span, tree: TraceTreeIndex) {
    val sb = this
    val base = root.startNanos

    fun failed(span: Span): Boolean = span.status == SpanStatus.ERROR || tree.childrenOf(span).any(::failed)
    sb.append(if (failed(root)) "✗ trace ${root.traceId} FAILED" else "✓ trace ${root.traceId}")
    sb.append("  (total ${durMs(root)}ms)\n")

    fun render(span: Span, depth: Int) {
        val indent = "   ".repeat(depth)
        val relStart = TimeUnit.NANOSECONDS.toMillis(span.startNanos - base)
        val relEnd = span.endNanos?.let { TimeUnit.NANOSECONDS.toMillis(it - base) }
        sb.append("$indent└─ ${span.name} [${relStart}ms → ${relEnd}ms] ${durMs(span)}ms ${span.status}\n")
        if (span.traits.isNotEmpty()) sb.append("$indent      traits: ${span.traits}\n")
        // A throwable shows only at its birthplace (ADR-015); ancestors just carry ERROR.
        val birthplaces = tree.birthplaceExceptionsOf(span)
        tree.eventsOf(span).sortedWith(byTime).forEach { event ->
            val line = when (event) {
                is LogEvent -> "${event.attributes}: ${event.message}"
                is NamedEvent -> "event ${event.name} ${event.attributes}"
                is ExceptionEvent -> if (event in birthplaces) "error: ${event.throwable::class.simpleName}: ${event.throwable.message}" else null
            }
            if (line != null) sb.append("$indent      $line\n")
        }
        tree.childrenOf(span).forEach { render(it, depth + 1) }
    }
    render(root, 0)
}

private fun durMs(span: Span): Long =
    span.endNanos?.let { TimeUnit.NANOSECONDS.toMillis(it - span.startNanos) } ?: -1
