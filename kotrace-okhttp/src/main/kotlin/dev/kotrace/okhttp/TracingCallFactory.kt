package dev.kotrace.okhttp

import dev.kotrace.NonSuspendTracingBridge
import dev.kotrace.TraceHandoff
import okhttp3.Call
import okhttp3.Request

/**
 * The Trap-1 bridge. Retrofit calls [newCall] on the coroutine's own thread (when it creates the raw call,
 * before handing it to OkHttp's dispatcher), so the flow's trace context — the active span, the collector, the
 * per-flow config override, the scope — still resolves here; it does **not** inside [TracingInterceptor], which
 * runs on OkHttp's thread. So the factory captures that context as a [TraceHandoff] and tags it on the request;
 * the interceptor opens the HTTP span **per execution** from it and emits under the flow's own config (ADR-023).
 * A cloned or retried call therefore gets its own span, and a call that is never executed opens none. A call kept
 * and executed only after its trace reported still opens its span under that captured context: live adapters see
 * it, but the report was already sent without it.
 *
 * With no active span the call still opens a **root** http span (untraced-request coverage), but only when
 * something would consume it — an active collector (fan-out) or a live adapter in the config a fan-out would use
 * (the per-flow override, else the process-wide [dev.kotrace.Kotrace] config). A call made with tracing fully off
 * is passed through untouched, so this is safe to wrap every client with.
 *
 * **The span name is a static symbol, never the raw URL (ADR-021).** A span's name becomes `operation` on
 * every record lifted off it — including the non-sensitive crash record and the `toJson` log line — and no
 * policy gate covers it. A REST path can embed user data (`/users/alice@example.com`), so the default
 * [spanName] is `http <METHOD>` only; the path stays on the `sensitive` breadcrumbs [TracingInterceptor]
 * logs. To tell endpoints apart, pass a [spanName] that maps a request to a **sanitized route template**
 * (`http GET /users/{id}`) — it must return a symbol, never interpolate the concrete path or query.
 */
class TracingCallFactory(
    private val delegate: Call.Factory,
    attributes: Map<String, String> = emptyMap(),
    private val spanName: (Request) -> String = ::defaultHttpSpanName,
) : Call.Factory {

    /**
     * The HTTP span's birth attributes, snapshotted here (ADR-001/028): the span itself is only opened when a call
     * executes (ADR-023), so without this copy a caller mutating its map would change calls already created —
     * or, mutating it concurrently, fail the span's own copy on OkHttp's thread.
     */
    private val attributes: Map<String, String> = attributes.toMap()

    // The genuine non-suspend bridge site: newCall runs on the coroutine thread with no coroutine frame, so the
    // thread mirrors are live here — exactly what NonSuspendTracingBridge opts in for (ADR-003/023).
    @OptIn(NonSuspendTracingBridge::class)
    override fun newCall(request: Request): Call {
        val handoff = TraceHandoff.captureOrNull() ?: return delegate.newCall(request)
        val trace = HttpTrace(handoff, nameOf(request), attributes)
        return delegate.newCall(request.newBuilder().tag(HttpTrace::class.java, trace).build())
    }

    /**
     * The caller's [spanName], or the default when it throws (a route table miss, a bug in the mapper): naming a
     * span is telemetry and must never fail the call (ADR-023). A JVM-fatal error still propagates (ADR-014).
     */
    private fun nameOf(request: Request): String = try {
        spanName(request)
    } catch (t: Throwable) {
        if (t.isJvmFatal()) throw t
        defaultHttpSpanName(request)
    }
}

/** The default [TracingCallFactory] span name: `http <METHOD>` — a symbol only, no path or query (ADR-021). */
private fun defaultHttpSpanName(request: Request): String = "http ${request.method}"
