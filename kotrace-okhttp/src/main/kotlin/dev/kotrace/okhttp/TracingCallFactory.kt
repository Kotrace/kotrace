package dev.kotrace.okhttp

import dev.kotrace.adapter.FaultPhase
import dev.kotrace.bridge.NonSuspendTracingBridge
import dev.kotrace.bridge.TraceHandoff
import dev.kotrace.bridge.runIsolated
import okhttp3.Call
import okhttp3.Request

/**
 * Carries the flow's trace context to [TracingInterceptor]. Retrofit calls [newCall] on the coroutine's thread,
 * where the active span, collector, configuration override and scope still resolve; the interceptor runs on OkHttp's
 * thread, where they do not. So the factory captures them as a [TraceHandoff] tagged on the request, and the
 * interceptor opens one span **per execution** from it (ADR-023): a retried call gets its own span, and a call
 * never executed opens none. A call executed after its trace reported still opens its span, seen only by live
 * adapters.
 *
 * With no active span the call opens a **root** HTTP span, but only when something would consume it: an active
 * collector, or a live adapter in the configuration that applies. With tracing fully off the call passes through
 * untouched.
 *
 * **The span name is a symbol, never the raw URL (ADR-021).** It becomes `operation` on every record, including
 * non-sensitive ones, and a REST path can embed user data, so the default is `http <METHOD>`; the path stays on
 * [TracingInterceptor]'s sensitive breadcrumbs. To tell endpoints apart, pass a [spanName] that returns a
 * sanitized route template (`http GET /users/{id}`), never the concrete path or query.
 */
class TracingCallFactory(
    private val delegate: Call.Factory,
    traits: Map<String, String> = emptyMap(),
    private val spanName: (Request) -> String = ::defaultHttpSpanName,
) : Call.Factory {

    /** Snapshotted (ADR-028): the span opens later, so a caller mutating its map must not reach it. */
    private val traits: Map<String, String> = traits.toMap()

    @OptIn(NonSuspendTracingBridge::class)
    override fun newCall(request: Request): Call {
        val handoff = TraceHandoff.captureOrNull() ?: return delegate.newCall(request)
        val trace = HttpTrace(handoff, nameOf(request), traits)
        return delegate.newCall(request.newBuilder().tag(HttpTrace::class.java, trace).build())
    }

    /**
     * The caller's [spanName], or the default when it throws: naming a span must never fail the call. The fault is
     * reported to the `FaultHook` as [FaultPhase.HTTP] (ADR-035).
     */
    private fun nameOf(request: Request): String =
        runIsolated(FaultPhase.HTTP, "http.span_name", recover = { defaultHttpSpanName(request) }) { spanName(request) }
}

/** The default [TracingCallFactory] span name: `http <METHOD>`, with no path or query (ADR-021). */
private fun defaultHttpSpanName(request: Request): String = "http ${request.method}"
