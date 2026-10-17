package dev.kotrace

/** The W3C Trace Context header name. Lower-case per the spec. */
const val TRACEPARENT_HEADER = "traceparent"

/**
 * The W3C `traceparent` value for this span: `version-traceid-spanid-flags`.
 *
 * This one header is the whole client↔backend stitch: the backend continues the trace by reading it,
 * with **no** OpenTelemetry dependency on either side — the wire format is the contract, not the SDK.
 * `flags = 01` marks the trace sampled.
 *
 * The ids are validated against the W3C grammar — [Span.traceId] 32 and [Span.spanId] 16 lowercase hex chars,
 * neither all zeros — and an [IllegalArgumentException] is thrown otherwise, so a malformed header never goes
 * on the wire, where a backend would discard it or, worse, start a new trace. kotrace mints W3C ids, but a span
 * inherits its trace id from its parent, so a span opened under a hand-constructed [Span] can fail too. Callers
 * on an instrumentation path must not let that fail the operation: the OkHttp interceptor sends the request
 * without the header instead.
 */
fun Span.toTraceparent(sampled: Boolean = true): String {
    require(isW3cId(traceId, 32)) { "traceId is not a W3C trace-id (32 lowercase hex, not all zero): '$traceId'" }
    require(isW3cId(spanId, 16)) { "spanId is not a W3C parent-id (16 lowercase hex, not all zero): '$spanId'" }
    return "00-$traceId-$spanId-${if (sampled) "01" else "00"}"
}

private fun isW3cId(id: String, length: Int): Boolean =
    id.length == length && id.all { it in '0'..'9' || it in 'a'..'f' } && id.any { it != '0' }
