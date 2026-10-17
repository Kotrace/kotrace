package dev.kotrace.okhttp

import dev.kotrace.NonSuspendTracingBridge
import dev.kotrace.TraceHandoff

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

/**
 * What [TracingCallFactory] hands [TracingInterceptor] through the request tag (ADR-023): the flow's captured
 * [TraceHandoff] plus the span's name and birth attributes, fixed at `newCall`. **No span yet** — the interceptor
 * opens one per execution, so a cloned/retried call gets its own span and a call that is never executed opens
 * none. The tag is keyed by this internal class, so nothing outside the module can forge or read it.
 */
@OptIn(NonSuspendTracingBridge::class)
internal class HttpTrace(
    val handoff: TraceHandoff,
    val spanName: String,
    val attributes: Map<String, String>,
)
