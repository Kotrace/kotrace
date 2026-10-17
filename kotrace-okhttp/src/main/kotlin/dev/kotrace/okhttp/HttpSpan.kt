package dev.kotrace.okhttp

import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.TraceHandoff

/** The span attribute keys this module writes on completion: emitted attributes, never traits (ADR-001). */
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
 * and the span's name and traits. No span yet: the interceptor opens one per execution. Keyed by this internal
 * class, so nothing outside the module can forge or read it.
 */
@OptIn(NonSuspendTracingBridge::class)
internal class HttpTrace(
    val handoff: TraceHandoff,
    val spanName: String,
    val traits: Map<String, String>,
)
