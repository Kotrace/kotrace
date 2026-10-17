package dev.kotrace

/** The W3C Trace Context header name. Lower-case per the spec. */
const val TRACEPARENT_HEADER = "traceparent"

/**
 * The W3C `traceparent` value for this span, `version-traceid-spanid-flags`, by which a backend that reads W3C
 * Trace Context continues the trace. `flags = 01` marks it sampled.
 *
 * Throws [IllegalArgumentException] unless [Span.traceId] is 32 and [Span.spanId] 16 lowercase hex chars, neither
 * all zeros, so a malformed header never reaches the wire. kotrace mints valid ids, but a span inherits its trace
 * id, so one opened under a hand-built [Span] can fail; instrumentation should then send no header rather than
 * fail the operation.
 */
fun Span.toTraceparent(sampled: Boolean = true): String {
    require(isW3cId(traceId, 32)) { "traceId is not a W3C trace-id (32 lowercase hex, not all zero): '$traceId'" }
    require(isW3cId(spanId, 16)) { "spanId is not a W3C parent-id (16 lowercase hex, not all zero): '$spanId'" }
    return "00-$traceId-$spanId-${if (sampled) "01" else "00"}"
}

private fun isW3cId(id: String, length: Int): Boolean =
    id.length == length && id.all { it in '0'..'9' || it in 'a'..'f' } && id.any { it != '0' }
