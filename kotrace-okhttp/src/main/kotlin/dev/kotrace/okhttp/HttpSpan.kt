package dev.kotrace.okhttp

import dev.kotrace.NonSuspendTracingBridge
import dev.kotrace.TraceHandoff

/** The span info keys this module writes on completion: emitted info, never filter keys (ADR-001). */
internal object HttpSpan {
    const val STATUS = "http.status"

    /**
     * Why a call with no response ended when OkHttp canceled it: [CALL_TIMEOUT] (a failure, recorded) or
     * [CANCELED] (by its caller, not recorded). A symbol, so a sink without sensitive events can tell them apart.
     */
    const val OUTCOME = "http.outcome"
    const val CALL_TIMEOUT = "call_timeout"
    const val CANCELED = "canceled"
}

/**
 * What [TracingCallFactory] hands [TracingInterceptor] in the request tag (ADR-023): the captured [TraceHandoff]
 * and the span's name and attributes. No span yet: the interceptor opens one per execution. Keyed by this internal
 * class, so nothing outside the module can forge or read it.
 */
@OptIn(NonSuspendTracingBridge::class)
internal class HttpTrace(
    val handoff: TraceHandoff,
    val spanName: String,
    val attributes: Map<String, String>,
)
