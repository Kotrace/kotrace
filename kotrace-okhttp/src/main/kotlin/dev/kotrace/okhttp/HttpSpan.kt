package dev.kotrace.okhttp

/**
 * The span keys this module owns. [STATUS] is late **emitted info** written on completion
 * ([TracingInterceptor]), never a filter key (ADR-001). The birth-time filter attributes (e.g. `layer=http`)
 * are no longer owned here — they are passed into [TracingCallFactory] by the caller. Constants, not an enum:
 * the values ride a `Map<String, String>`, and kotrace holds no closed attribute vocabulary — these are only
 * this adapter's own conventions.
 */
internal object HttpSpan {
    const val STATUS = "http.status"
}
