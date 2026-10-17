# ADR-023 — The OkHttp bridge carries the whole trace context (`TraceHandoff`), opens one span per execution, and never alters the call

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** new public `dev.kotrace.TraceHandoff` (opt-in `@NonSuspendTracingBridge`, whose `@Target` widens to
  `CLASS`); `Span.end` becomes exactly-once and contains a failure to record its throwable;
  `TracingCallFactory` tags a captured context instead of an opened `Span`; `TracingInterceptor` opens the HTTP
  span per execution, emits under the captured config, and contains its own telemetry faults.
- **Builds on:** [ADR-003](adr-003-span-verb-rename-and-startspan-optin.md) (the non-suspend bridge),
  [ADR-006](adr-006-span-terminal-state-safe-publication.md) (terminal state), [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md)
  (per-flow override wins over the global), [ADR-013](adr-013-auto-root-span.md) (the application throwable
  always wins over a telemetry failure), [ADR-021](adr-021-http-span-name-no-url-path.md) (span name).

## Context

`TracingCallFactory` opened the HTTP span at `newCall` (coroutine thread) and tagged the **`Span`** on the request;
`TracingInterceptor` finished it on OkHttp's dispatcher thread. Only the parent span crossed that boundary, and the
span's life was tied to `Call` creation rather than execution. Verified consequences:

1. **The per-flow config override was lost.** On the dispatcher thread `currentThreadConfig()` is null, so every
   `log`/`end` there resolved the process-wide config: a flow with override A and a global B sent its (sensitive)
   HTTP breadcrumbs to B; with no global, they went nowhere.
2. **A telemetry fault replaced or broke the call.** Under strict-uninstalled (ADR-011) with the only config being a
   per-flow override, the interceptor's `log` threw an `IllegalStateException` on the dispatcher thread — an uncaught
   exception there (an app crash on Android), the call failing with "canceled due to …". `Span.end(ERROR, e)` could
   throw instead of letting the caller rethrow `e`, unlike the suspend path, which attaches such a failure as
   suppressed (ADR-013).
3. **A retry erased the failed attempt.** `Call.clone()` keeps request tags, so a clone reused the same `Span`; the
   retry's `end(OK)` overwrote the first attempt's `ERROR`, end time and `http.status`. A call created but never
   executed (or whose earlier interceptor short-circuited) left a span open forever.
4. **`untraced` ignored the process-wide config**, so with only `Kotrace.install(liveAdapters)` a call outside any
   span was never traced, contrary to the factory's KDoc.

## Decision

- **`TraceHandoff`** (core, opt-in bridge API) — an immutable capture of the calling thread's mirrored span,
  collector, per-flow config override and scope id. `captureOrNull()` returns `null` when nothing would consume a
  span (no span, no collector, no live adapter in the override-else-global config — fixing 4).
  It exposes the captured `parent`, `collector` and `scopeId`; `startSpan(…, parent, collector, scopeId)`, whose
  defaults read the thread mirrors, opens a child under them on **any** thread.
  `withCapturedConfig { … }` mirrors the captured override onto the current thread for the block and restores it
  (fixing 1).
- **The factory captures, the interceptor opens.** `newCall` tags an internal `HttpTrace(handoff, spanName,
  attributes)`; the interceptor opens the HTTP span **per execution** — a clone or retry gets its own span, an
  unexecuted call opens none, and the span's duration is the execution, not the time since `newCall` (fixing 3).
  The tag key is module-internal, so it cannot be forged or read from outside.
- **`Span.end` is exactly once**: a CAS on the terminal state; a later `end` is a no-op and does not re-record. If
  recording the throwable fails, the failure is attached to it as suppressed and `end` returns normally, so the
  caller's `throw e` still throws the application's throwable (fixing the `end` half of 2).
- **The interceptor only observes.** Each telemetry step runs under a local guard that contains non-fatal faults;
  the response is returned and the exception rethrown exactly as without kotrace (fixing the rest of 2). A JVM-fatal
  error still propagates (ADR-014).
- **A canceled call is a cancellation (amendment, review).** When `proceed` throws an `IOException` and the call
  itself is canceled (`call.isCanceled()`: the caller's coroutine was cancelled and Retrofit cancelled the call,
  or an explicit `cancel()`), the HTTP span is ended `ERROR` **without** that exception, mirroring ADR-022's
  genuinely cancelled spans. Before this, every trace cancelled with a call in flight carried a spurious
  `IOException("Canceled")` birthplace, so a `CANCELLED` trace held a crash record and a failing sibling produced
  two. Only an `IOException` qualifies: any other throwable from a canceled call is recorded.
- **A call timeout is a failure (amendment, second review).** OkHttp enforces `callTimeout` by canceling the call,
  so the first version of the rule above dropped timeouts too. A timeout is a failure (ADR-022), and the HTTP span
  is often the only place it can be recorded: a call made outside any span, or a timeout the caller handles. A
  canceled call whose call-timeout budget (`call.timeout()`) is spent is therefore recorded, and logged as
  `call timeout`. The budget is measured from the span's start, which trails the call's by whatever interceptors
  run before this one, so the last tenth of the budget counts too: a cancel that late reads as a timeout, which
  errs toward a record rather than a lost failure.
- **`http.outcome` tells the two apart (amendment, third review).** The throwable a timeout records is OkHttp's
  own cancellation exception (`IOException("Canceled")` on OkHttp 4, `SocketException("Socket closed")` on 5), and
  the "call timeout" breadcrumb is `sensitive`, so a default crash sink saw only "Canceled". Both cases now stamp
  the HTTP span's emitted info `http.outcome` (`call_timeout` or `canceled`), a symbol every record of the span
  carries — an additive wire field, like `http.status`. A timeout that escapes into a caller's span is recorded
  twice: at the HTTP span, and at the caller's span as the `InterruptedIOException("timeout")` OkHttp wraps it
  in. That wrapper is a different throwable, so a different lineage (ADR-015); the two records share a trace id,
  and `http.outcome` on the HTTP one says it was the timeout.

## Options considered

- **Tag the config next to the `Span` (two tags) — rejected.** Fixes 1 but not 3: the span would still be born at
  `newCall`, shared by clones, and leaked by unexecuted calls.
- **Wrap `Call` to intercept `clone()` — rejected.** OkHttp and Retrofit can bypass a wrapper (`RealCall` internals,
  `call.request()` rebuilding); opening the span where the call actually executes is exact.
- **Keep strict mode loud on the dispatcher thread — rejected.** An uncaught exception on OkHttp's thread crashes an
  Android app and cancels an unrelated call; the flow's own suspend code (report, span catch) still surfaces a
  missing config loudly, which is where ADR-011's signal belongs.

## Consequences

- New public, opt-in API (`TraceHandoff`); `@NonSuspendTracingBridge` may now annotate a class. Additive for ABI.
- **Behavior changes:** the HTTP span starts when the call executes, not at `newCall`; `Span.end` is idempotent;
  interceptor breadcrumbs follow the flow's override. A consumer that hand-tagged a `Span` on a request for the
  interceptor is no longer supported — the tag is internal.
- A call created inside a trace but executed only after that trace reported (a `Call` kept and run later) still
  opens its span under the captured context. Its records reach live adapters, but the report was already sent
  without it: a report is the trace as it stood at its end (ADR-029), not a subscription.
- Bounded `Level.BODY` capture is a separate decision (ADR-024).
