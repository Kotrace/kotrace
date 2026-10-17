# ADR-031: Split the core into feature packages

- **Date:** 2026-10-08
- **Status:** Accepted
- **Affects:** the package (and so the import and JVM name) of every core symbol listed below. No behavior or
  wire change.
  - `dev.kotrace.bridge`: `startSpan`, `Span.end`, `TraceHandoff`, `@NonSuspendTracingBridge`;
  - `dev.kotrace.adapter`: `TraceAdapter`, `LiveAdapter`, `ReportAdapter`, `TracePolicy`, `FaultHook`,
    `FaultPhase`, `Throwable.isJvmFatal`;
  - `dev.kotrace.report`: `SpanCollector.reportTrace`, `List<Span>.renderTree`;
  - `dev.kotrace.internal` (new, no public symbol): the fault-isolation guards and span id minting.

  Everything else keeps its package: `span {}`, `withScope`, `Kotrace`, `Span`, `SpanStatus`, the context
  elements and their `current*()` readers, `TraceConfig`, `TraceLink`, `toTraceparent`, `TraceStatus`,
  `TraceOutcome`, `ReturnVerdict`, `@KotraceTestApi` and `@UnredactedTraceRead` stay in `dev.kotrace`, and
  `dev.kotrace.event` is unchanged.
- **Builds on:** [ADR-028](adr-028-api-hygiene-0-5-0.md), which already made 0.5.0 a breaking release.

## Context

The core had grown to 22 files in one flat `dev.kotrace` package, next to the `dev.kotrace.event` package.
Three different audiences shared it: the code that traces (`span {}`, scopes, configuration), the code that consumes a
trace (adapters, policies, the report), and the non-suspend bridge that only integrations should touch. Internal
machinery (fault guards, the report's tree index) sat in the same files as public API. A reader could not tell
from the layout which file served which concern.

0.5.0 is unreleased and already breaks the ABI of 0.4.0 (ADR-028, and `startSpan` gained parameters in
ADR-023), so it is the cheap moment to move packages too.

## Decision

The directory is the package. One package per concern:

| Package | Holds | Audience |
|---|---|---|
| `dev.kotrace` | `span {}`, `withScope`, `Kotrace`, the span model, context elements, `TraceConfig`, statuses and outcomes, the opt-in markers every user meets | everyone who traces |
| `dev.kotrace.event` | events, records, the event verbs, span-less emits, `toJson` | everyone who logs or writes a sink |
| `dev.kotrace.adapter` | the sink contract: adapters, `TracePolicy`, the fault hook | whoever writes a sink |
| `dev.kotrace.report` | `reportTrace` (manual boundaries) and `renderTree` (debug read), plus the internal tree index | manual-boundary owners, tests |
| `dev.kotrace.bridge` | the opt-in non-suspend bridge | integrations (OkHttp, Room) |
| `dev.kotrace.internal` | `internal` helpers shared across packages: fault-isolation guards, span creation and id minting | nobody outside the module |

Placement rules:

- A symbol nearly every user touches stays in `dev.kotrace`, so the common path (`span`, `withScope`,
  `Kotrace.install`, `currentSpan`) needs the same imports as before.
- An `internal` helper used by one feature stays in that feature's package (`TraceTreeIndex`,
  `FrozenTimelines` and `rootsOf` in `report`; `accepts` in `adapter`). Only helpers shared by several packages
  go to `dev.kotrace.internal`.
- Configuration resolution (`resolvedThreadConfig`, `withMirroredConfig`) stays in `TraceConfig.kt`: it reads that
  file's private thread-local.

Files were split where one held two concerns: `Trace.kt` lost `startSpan`/`end` (`bridge/StartSpan.kt`) and
span creation (`internal/SpanFactory.kt`); `AdapterFault.kt` became `adapter/FaultHook.kt` (public) and
`internal/FaultIsolation.kt` (guards); `Report.kt` lost the tree index to `report/TraceTreeIndex.kt`.

Core tests follow the same packages (`src/test/kotlin/dev/kotrace/{adapter,bridge,event,report}`).

## Options considered

- **Folders only, keep `package dev.kotrace`: rejected.** No consumer change, but folder and package disagree,
  against the Kotlin convention, and the layout would claim a structure the API does not have.
- **Move only internals to `dev.kotrace.internal`: rejected.** No API change, but the public surface stays one
  flat package, which was the problem.
- **A package per concept, root left nearly empty (`dev.kotrace.span`, `dev.kotrace.config`, …): rejected.**
  Every consumer would import from several packages for the basic `span {}` + `install` path.

## Consequences

- Source-breaking for consumers who import a moved symbol: `import dev.kotrace.LiveAdapter` becomes
  `import dev.kotrace.adapter.LiveAdapter`, and so on. An opt-in written as a string
  (`optIn("dev.kotrace.NonSuspendTracingBridge")`) needs the new name. The README upgrade notes list the moves.
- Binary-breaking for the moved symbols, as their JVM class names change (`api/kotrace.api` records it). The
  0.5.0 bump already covers it.
- A new feature goes in the package of its concern; a new cross-package `internal` helper goes in
  `dev.kotrace.internal`.
