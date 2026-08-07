package dev.luxgroup.kotrace

/** The W3C Trace Context header name. Lower-case per the spec. */
const val TRACEPARENT_HEADER = "traceparent"

/**
 * The W3C `traceparent` value for this span: `version-traceid-spanid-flags`.
 *
 * This one header is the whole client↔backend stitch: the backend continues the trace by reading it,
 * with **no** OpenTelemetry dependency on either side — the wire format is the contract, not the SDK.
 * `flags = 01` marks the trace sampled.
 */
fun Span.toTraceparent(sampled: Boolean = true): String =
    "00-$traceId-$spanId-${if (sampled) "01" else "00"}"
