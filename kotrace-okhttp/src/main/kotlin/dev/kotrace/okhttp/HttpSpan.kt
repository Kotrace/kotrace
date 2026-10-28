package dev.kotrace.okhttp

import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.TraceHandoff

/**
 * The span attribute keys this module writes on the HTTP span: emitted attributes, never traits (ADR-001). Public so
 * an adapter's [dev.kotrace.adapter.TracePolicy.acceptsAttribute] can admit them by name, since an adapter receives
 * no attribute it has not accepted, kotrace's own included (ADR-039).
 *
 * - [STATUS] is the response code (`"200"`), stamped once the response returns.
 * - [OUTCOME] says why a call with no response ended when OkHttp canceled it: [CALL_TIMEOUT] (a failure, recorded)
 *   or [CANCELED] (by its caller, not recorded). A symbol, so a sink without sensitive events can tell them apart.
 */
object HttpSpan {
    const val STATUS: String = "http.status"
    const val OUTCOME: String = "http.outcome"
    const val CALL_TIMEOUT: String = "call_timeout"
    const val CANCELED: String = "canceled"
}

/**
 * What [TracingCallFactory] hands [TracingInterceptor] in the request tag (ADR-023): the captured [TraceHandoff]
 * and the span's name and traits. No span yet: the interceptor opens one per execution. Keyed by this internal
 * class, so nothing outside the module can forge or read it.
 */
@OptIn(NonSuspendTracingBridge::class)
internal class HttpTrace(
    val handoff: TraceHandoff,
    val spanName: String,
    val traits: Map<String, String>,
)
