package dev.kotrace

import java.util.concurrent.TimeUnit

/**
 * Renders a trace as an indented tree — a debug-log shape. Times are milliseconds relative to the root
 * span's start.
 */
fun List<Span>.formatTree(): String {
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
        // The birthplace is the deepest ERROR span — one whose children did not themselves error.
        // Show the throwable only there; ancestors merely carry ERROR status up the path.
        val isBirthplace = span.status == SpanStatus.ERROR && children.none { it.status == SpanStatus.ERROR }
        if (isBirthplace) span.error?.let { sb.append("$indent      error: ${it::class.simpleName}: ${it.message}\n") }
        children.forEach { render(it, depth + 1) }
    }
    render(root, 0)
    return sb.toString()
}

private fun List<Span>.childrenOf(parent: Span) =
    filter { it.parentId == parent.spanId }.sortedBy { it.startNanos }

private fun durMs(span: Span): Long =
    span.endNanos?.let { TimeUnit.NANOSECONDS.toMillis(it - span.startNanos) } ?: -1
