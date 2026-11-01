# Changelog

What changed in each release, and what a consumer must do to upgrade. Every change cites the ADR that decided it,
in [DECISIONS.md](DECISIONS.md). A binary-breaking change bumps the version and gets an entry here in the same commit
(AGENTS.md § Build And Test).

## 0.7.0 (unreleased)

**Added**

- `TraceHandoff.withCapturedContext`: runs a block with the captured span, collector, configuration and ambient scope
  mirrored onto the calling thread, for a library callback that reads the thread mirrors itself (ADR-043).
- `kotrace-room`: `tracing(queryExecutor = …)`, the executor Room runs queries on, wrapped so each task carries the
  trace context of the thread that submitted it (ADR-043).

**Fixed**

- `kotrace-room`: on Room 2.6, a suspend `@Query` read logged no SQL. Room launches it outside the caller's
  coroutine, so no span reached its thread; `tracing()`'s query executor now carries it (ADR-043).

**Upgrading from 0.6.** 0.7.0 is binary-compatible: the 0.6.0 `tracing()` signature is kept. It changes how Room is
built:

- **Behaviour.** `tracing()` now sets Room's query executor. One set with `setQueryExecutor` before it is replaced,
  and one set after it replaces kotrace's, so suspend reads on Room 2.6 log nothing again.
- **Migrate.** Pass the executor you set with `setQueryExecutor` as `tracing(queryExecutor = …)` instead. An app that
  sets only `setTransactionExecutor` ran its queries there too, since Room copies one executor to the other: pass that
  executor as `queryExecutor` as well to keep them there.

## 0.6.0 (2026-11-01)

**Added**

- A process-wide fallback scope: `Kotrace.install(fallbackScopeId = …)` scopes every record emitted outside any
  `withScope` (ADR-038).
- `TracePolicy.acceptsAttribute(key, value)`: each adapter receives only the attributes it admits (ADR-039).
- `ExceptionOrigin.EXPLICIT` and `TracePolicy.acceptsExplicitException`: an `addException` record is marked apart
  from a birthplace, and a sink that ships it live can drop it from the report (ADR-040).

**Fixed**

- `kotrace-okhttp`: a telemetry failure after `proceed()` closes the response body first, so its connection is
  released.
- `kotrace-room`: on an inline executor, a `RejectedExecutionException` from the app's own callback propagates.

**Upgrading from 0.5.** 0.6.0 is binary-breaking for callers of `Kotrace.install`: recompile against it. It also
changes what every adapter receives: declare the attributes each one accepts.

- **Binary.** Both `Kotrace.install` overloads gained `fallbackScopeId` (ADR-038), so their 0.5.0 JVM signatures
  are gone. The parameter is defaulted, so source compiles unchanged. `TracePolicy.acceptsAttribute` is a defaulted
  member and `kotrace-okhttp`'s `HttpSpan` was internal, so both are additive.
- **Behavior.**
  - An adapter receives no attribute it has not accepted (ADR-039). `TracePolicy.acceptsAttribute(key, value)`
    defaults to `false`, so an adapter whose policy does not override it now gets every record, live and report,
    with empty `spanAttributes`, empty event `attributes` and links without attributes, and its `toJson` lines lose
    `"attributes"` and `"span_attributes"`. Records are still delivered as before: only their attributes are
    filtered. kotrace's own keys get no pass, so `http.status` and `kotrace.events_dropped` disappear too until a
    policy admits them.
  - Nothing changes until you pass a `fallbackScopeId`. With one, a record outside any `withScope` carries its
    `scope_id`, and `startSpan`'s default `scopeId` resolves it too; an explicit `scopeId = null` still opens a span
    with no scope.
- **Migrate.** In each adapter's policy, admit the keys that adapter may ship, by name for kotrace's own
  (`Span.EVENTS_DROPPED_ATTRIBUTE`, `kotrace-okhttp`'s now public `HttpSpan.STATUS` and `HttpSpan.OUTCOME`), or
  write `override fun acceptsAttribute(key: String, value: String) = true` for a debug adapter whose output stays on
  the device. `acceptsEvent` is unaffected: a `level` threshold still reads the event's own attributes.

An `addException` report record now has `ExceptionOrigin.EXPLICIT` (ADR-040), and `toJson` writes
`"exception_origin":"explicit"` for it. Thrown and returned failure climbs and attached orphans keep their origins.
The new `TracePolicy.acceptsExplicitException` defaults to `true`; set it to `false` only in a sink that records
every explicit exception live. Migrate every adapter reading `origin`: an exhaustive `when` needs an `EXPLICIT`
branch at source, and one compiled against 0.5.0 throws `NoWhenBranchMatchedException` on its first new origin.
A check for `origin == BIRTHPLACE` also stops matching explicit records.

## 0.5.0 (2026-10-17)

ADR-021 to ADR-037: feature packages, span `traits`, process-wide fault isolation, bounded hot paths, the consumer
dependency baseline, and the OkHttp and Room bridges reworked.

**Upgrading from 0.4.** 0.5.0 is a breaking release: recompile against it.

- **Binary.** `TracingCallFactory` gained `spanName` (ADR-021). In Room, `TracingQueryCallback` gained `delegate`
  and `tracing()` gained `delegate` and `delegateExecutor` (ADR-028). In the core, the opt-in `startSpan` gained
  `parent`, `collector` and `scopeId` (ADR-023). Their 0.4.0 JVM signatures are gone. `withScope` now compiles into
  `ScopeContextKt`, beside `currentScopeId` and `currentThreadScopeId`.
- **Packages.** The core is split by concern (ADR-031); fix the imports of moved symbols:
  - `dev.kotrace.adapter`: `TraceAdapter`, `LiveAdapter`, `ReportAdapter`, `TracePolicy`, `FaultHook`,
    `FaultPhase`, `isJvmFatal`;
  - `dev.kotrace.report`: `reportTrace`, `renderTree`;
  - `dev.kotrace.bridge`: `startSpan`, `end`, `TraceHandoff`, `NonSuspendTracingBridge`.

  `span`, `withScope`, `Kotrace`, `Span`, `TraceConfig` and the rest of `dev.kotrace`, and all of
  `dev.kotrace.event`, are where they were.
- **Source.** `Kotrace.strictWhenUninstalled()` is renamed `Kotrace.strictWhenNotInstalled()` (ADR-011), and
  `AdapterFaultHook.onAdapterFault` is renamed `FaultHook.onFault` (ADR-014).
  A span's two string maps are renamed (ADR-032): the filter dimensions `Span.attributes` (the `attributes`
  parameter of `span`, `startSpan` and `TracingCallFactory`) are now `traits`, and the payload `Span.info` /
  `putInfo` is now `Span.attributes` / `putAttribute`. `TraceRecord.info` is `spanAttributes`,
  `Span.EVENTS_DROPPED_INFO` is `EVENTS_DROPPED_ATTRIBUTE`, and the `info` parameter of `addException` /
  `emitException` is `attributes`. **A policy reading `span.attributes` still compiles but now reads the
  payload: switch it to `span.traits`.**
  `Kotrace.resetForTest()` needs `@OptIn(KotraceTestApi::class)`, best granted to the test source set
  only, and reading `Span.events` or `Span.exception` needs `@OptIn(UnredactedTraceRead::class)` (ADR-028).
- **Behavior and wire.**
  - `toJson` renders the span's attributes under `"span_attributes"`, formerly `"info"` (ADR-032). A log query
    on `info.http.status` becomes `span_attributes.http.status`.
  - HTTP spans are named `http <METHOD>`, never the URL path, so the `operation` field changes (ADR-021).
  - `CANCELLED` means the root's coroutine was really cancelled. A timeout inside the operation is `ERROR`, and a
    cancelled span records no exception (ADR-022).
  - Each execution of a call opens its own HTTP span, emitting under the flow's configuration (ADR-023). A call its caller
    canceled records no exception, while one that hit its `callTimeout` does; both mark the span's `http.outcome`
    attribute (ADR-022/023).
  - `Level.BODY` is bounded and reads a response only as your code does (ADR-024).
  - An emit made from inside `onLive` is stored but not fanned out live again, and an HTTP call made there is not
    traced (ADR-025).
  - A report covers every root of a collector, not only the first (ADR-026).
  - `toTraceparent()` throws on an id that is not W3C (ADR-028).
  - A span keeps up to 1,000 events, dropping the oldest named event first, then the oldest log, an exception only
    when nothing else is left (ADR-029). `TraceConfig.maxEventsPerSpan` and
    `Kotrace.install(maxEventsPerSpan = …)` change the cap (ADR-033).
  - A fault of kotrace's own never changes what `span { }` returns or throws: a span that fails to open runs its
    block untraced, and a report fault is contained. Both reach the `FaultHook` (`FaultPhase.SPAN_OPEN`,
    `FaultPhase.REPORT`). Only `strictWhenNotInstalled` still throws on purpose (ADR-034).
  - The OkHttp and Room integrations report the faults they contain to the `FaultHook` (`FaultPhase.HTTP`,
    `FaultPhase.DATABASE`), and an armed `strictWhenNotInstalled` now reaches their callers too (ADR-035).
  - `FaultHook.onFault(phase, adapter, cause)` is now `onFault(phase, faultMessage, cause)`: a symbol naming the
    step that failed (`live.deliver`, `http.response.body`) replaces the adapter (ADR-036). An adapter that tracks
    its own health catches its own faults.
  - The fault hook is no longer a `TraceConfig` or `Kotrace.install` parameter: install it once with
    `Kotrace.installFaultHook(hook)`, ideally first, so it also hears of a throwing `install` provider
    (`FaultPhase.INSTALL`) (ADR-037).

## 0.4.0 (2026-09-15) and earlier

Not recorded here. ADR-001 to ADR-020 describe what those releases decided; `git log v0.4.0` has the rest.
