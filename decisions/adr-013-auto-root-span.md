# ADR-013 — A top-level `span` self-owns its trace and reports at the outcome (auto-root)

- **Date:** 2026-09-12
- **Status:** Accepted
- **Affects:** `dev.kotrace.span` (`Trace.kt`) gains **auto-root** behavior; `TraceStatus.kt` KDoc is
  corrected. One existing test changes its expectation (`SpanlessScopeTest`); several other top-level-span
  tests newly exercise the report path but keep their assertions (no `ReportAdapter`, or an existing
  collector). A **behavioral change** (a top-level `span` with no enclosing collector now attempts a report,
  delivered when a `ReportAdapter` is configured)
  that is **source-compatible**. Minor version bump. Per-adapter fault isolation is **not** part of this ADR
  — it is split into [ADR-014](adr-014-adapter-fault-isolation.md), which this ADR depends on for its
  strongest outcome guarantee (see Decision § Adapter faults).
- **Builds on:** [ADR-003](adr-003-span-verb-rename-and-startspan-optin.md) (`span` is the sole node-opener;
  `trace` = tree, reserved), [ADR-006](adr-006-span-terminal-state-safe-publication.md) (span terminal
  state), [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md) (`scope_id`, per-flow config resolution,
  span-less identity), [ADR-011](adr-011-strict-uninstalled-optin.md) (release telemetry must not crash the
  process), [ADR-012](adr-012-reporttrace-attached-orphan-failures.md) (`attached` / failure-as-data).
- **Depends on:** [ADR-014](adr-014-adapter-fault-isolation.md) (per-adapter fault isolation) for the
  "telemetry never corrupts the traced outcome" guarantee. Until ADR-014 lands, this ADR states the weaker
  guarantee below.

## Context

No core primitive owns a trace's lifecycle. Today the consumer hand-assembles it:

```kotlin
val collector = SpanCollector()
withContext(collector + TraceConfig(adapters)) {
    span("root") { … }
    collector.reportTrace(/* status? */)   // must run, with the right status, in the right place
}
```

`span` is instrumentation-only: it marks `ERROR`, appends the exception, stamps end, and **rethrows** — it
never reports ([`Trace.kt:35`](../src/main/kotlin/dev/kotrace/Trace.kt:35)–44). `reportTrace` is a separate,
consumer-invoked extension on `SpanCollector` taking a caller-supplied status
([`Report.kt:41`](../src/main/kotlin/dev/kotrace/Report.kt:41)). The consequence, verified against source:

**Forgetting the boundary silently loses the whole trace.** With no `SpanCollector` in context, a `span`
opens, runs, appends events to itself, and is garbage-collected — `currentCollector()?.add` is null-safe and
drops it ([`Trace.kt:30`](../src/main/kotlin/dev/kotrace/Trace.kt:30)); with no collector there is nothing
for `reportTrace` to walk, so **no report is produced at all**. Live events still fire off the resolved
config, independent of the collector ([`Emit.kt:18`](../src/main/kotlin/dev/kotrace/event/Emit.kt:18)) — so
the failure is invisible: the app runs correctly, live breadcrumbs may appear, and the crash/report path is
simply empty ("empty dashboard").

An earlier draft of this ADR proposed a dedicated `trace(name) { }` boundary primitive. It was rejected by
the maintainer on two grounds that this ADR takes as constraints:

1. **No second verb / no per-use choice.** ADR-003 deliberately removed the `trace` verb so a consumer never
   weighs `trace` vs `span`. A new `trace { }` — even as a boundary rather than a node-opener —
   reintroduces a decision they intentionally eliminated. The desired shape is *all-in-one*: the consumer
   writes `span { }` and nothing else.
2. **No runtime landmine.** A `trace { }` that throws on nested use raises a **runtime** exception that only
   fires when a user reaches the feature — a latent production fault, which a telemetry library must never
   introduce (ADR-011: release telemetry must not crash the process).

And explicit `trace { }` does not even remove the silent-loss failure above: forgetting it loses the trace
just as forgetting the manual boundary does. A debug-only strict check (ADR-011-style) could catch omission
in dev, but only in builds where it is armed, and it still adds the second verb.

Process death (OOM-kill, force-stop, swipe-away, SIGKILL, native) stays out of scope — [D02](../BACKLOG.md:36).
An in-process, in-memory tail buffer cannot survive the death of its own process; that is the platform crash
reporter's job. This ADR closes the **ordinary** completion / exception / cancellation hole, which is
entirely in reach.

## Decision

**Make trace-rootedness implicit: a `span` self-owns a collector and reports at its outcome exactly when it
opens with no trace already in context.** The consumer only ever writes `span { }`.

```kotlin
// The one rule:
//   span self-owns a collector and reports  iff  currentSpan() == null && currentCollector() == null
suspend fun <T> span(name: String, attributes: Map<String, String> = emptyMap(),
                     links: List<TraceLink> = emptyList(), block: suspend () -> T): T
```

- **Auto-root case — `currentSpan() == null && currentCollector() == null`.** The span wraps its ordinary
  node-opening in an outer boundary with this **exact ordering** (a naive "wrap only the block in
  `withContext(collector)`" is wrong — `currentCollector()?.add(opened)` runs *before* the collector is
  installed, so the root never enters the collector and `reportTrace` finds no root and returns silently,
  [`Report.kt:45`](../src/main/kotlin/dev/kotrace/Report.kt:45)):
  1. detect the null/null state;
  2. install the new `SpanCollector` on the context;
  3. create and register the root span **while that collector is current** (so `add` targets it);
  4. run `block`, then close the root (`markEnd`) — the existing `span` finally;
  5. call `reportTrace(status)` **after** `markEnd` (never before — that would report an unfinished root)
     and **before** the collector context exits (so the collector and any ambient `TraceConfig` are still
     installed);
  6. exit the collector context and return, or rethrow the block's throwable.

  Status is derived from the block's own outcome: normal return → `OK`, an escaping `CancellationException`
  → `CANCELLED`, any other escaping throwable → `ERROR`. In normal/release operation the application
  throwable is **rethrown unchanged** (see § Adapter faults for the strict-mode and adapter-failure
  qualifications).
- **Child / manual case — a `SpanCollector` is already in context.** Unchanged from today: the span parents
  under the current span and registers into the active collector; it does **not** report. The enclosing
  auto-root, or a manual `reportTrace`, owns the single report.
- **Identified-but-uncollected case — a span is in context but no collector.** Unchanged: the span is a
  child for identity and live fan-out only. It is **not** converted into a report; auto-root does **not**
  fire here. (This is the case the naive "no collector" rule gets fatally wrong — see Rejected.)

**Why both getters, why coroutine-context.** The decision reads `currentSpan()` and `currentCollector()`
from the coroutine context ([`SpanContext.kt:16`](../src/main/kotlin/dev/kotrace/SpanContext.kt:16),
[`SpanCollector.kt:16`](../src/main/kotlin/dev/kotrace/SpanCollector.kt:16)), never the thread-local mirrors
— the mirrors exist for the non-suspend `startSpan` bridge and can lag a suspend frame. Keying on
**both being null** is what prevents the silent-loss regression: a span present without a collector would
otherwise cause `createSpan` to mint a *child* (non-null `parentId`) into a freshly created collector, whose
walk then finds no root and returns silently.

### The four context states

| `currentSpan()` | `currentCollector()` | Meaning | Auto-root does |
|---|---|---|---|
| null | null | genuine new root | **creates collector, roots, reports at outcome** |
| non-null | non-null | inside an active trace | child span, no report (root/manual owns it) |
| null | non-null | manual boundary (consumer owns `reportTrace`) | root span, **no** auto-report — manual owner reports |
| non-null | null | identified-but-uncollected (an explicitly installed `SpanContext` with no collector) | child for identity/live only, **no** report — preserves today's behavior, does not itself remove silent loss |

**Collector/span consistency is a caller invariant, not something auto-root repairs.** The two context
elements are independently overlayable ([`ARCHITECTURE.md:197`](../ARCHITECTURE.md:197)); a caller can
construct a mismatched pair, and auto-root cannot fix that pre-existing misuse. The one-collector/one-root
invariant also stands: a manual collector accumulating multiple sequential roots is reported only from the
first root `reportTrace` finds ([`Report.kt:45`](../src/main/kotlin/dev/kotrace/Report.kt:45)).

### Config, scope, and the bridge

- **Auto-root overlays the `SpanCollector` only — never a config.** `withContext` inherits the ambient
  context, so an outer per-flow `TraceConfig` remains active while the auto-root runs and reports; with no
  override, `resolvedThreadConfig()` falls back to `Kotrace.defaultConfig()`
  ([`TraceConfig.kt:36`](../src/main/kotlin/dev/kotrace/TraceConfig.kt:36); the outer config's thread mirror
  is restored only when its own context exits, [`TraceConfig.kt:80`](../src/main/kotlin/dev/kotrace/TraceConfig.kt:80)).
  Reporting *before* the collector context exits keeps that resolution correct. Overlaying a resolved config
  here would wrongly freeze the override-then-global rule.
- **`withScope` is inherited unchanged**: the root span reads `currentScopeId` at creation, so an enclosing
  scope keeps stamping `scope_id` (ADR-010).
- **`startSpan` (the non-suspend bridge, ADR-003) is not auto-rooted** — it has no `finally` to guarantee a
  report, and it never installs its span as ambient `SpanContext`
  ([`Trace.kt:54`](../src/main/kotlin/dev/kotrace/Trace.kt:54)). Its existing behavior and limitations are
  unchanged.

### Failure-as-data stays on the explicit escape hatch

> **Amended by [ADR-016](adr-016-auto-root-returned-outcome.md) (2026-09-14), then
> [ADR-017](adr-017-merge-span-overloads-default-returned-outcome.md) (2026-09-18).** Failure-as-**value** is no
> longer expressible *only* through a hand-seeded collector: auto-root can map the returned value to
> `(TraceStatus, attached)`. ADR-016 delivered this as a **second overload**; ADR-017 merged it into the single
> `span`'s **defaulted** `returnedOutcome` parameter and collapsed this ADR's OK-only `autoRootSpan` and
> ADR-016's `autoRootSpanReturning` into **one** mapper-carrying `autoRootSpan`. The paragraph below describes
> the **zero-config normal-return behavior** (`OK`), which is unchanged — it is now the default mapper's verdict;
> the manual `reportTrace(status, attached)` path remains **an** option for bespoke boundaries. The paragraph as
> originally written follows.

A standalone `span` that *returns* a `Result.failure` (or a domain failure value) auto-reports **`OK`** —
nothing escaped. Terminal-failure-as-data (a saga's suppressed rollback throwables, the ADR-012 case) is
**not** expressible through the zero-arg `span`; a consumer with that need pre-seeds a manual collector and
calls `reportTrace(status, attached)` itself. This keeps `span` zero-argument and all-in-one; the manual
path remains the documented route for the advanced case.

### Adapter faults — the outcome guarantee, and its qualifications

The boundary's "rethrow unchanged" is only fully real once telemetry cannot replace the traced outcome, and
today it can — on **every** phase. A throwing adapter is unguarded: the report fan-out is a bare `forEach`
([`Report.kt:64`](../src/main/kotlin/dev/kotrace/Report.kt:64)); live and span-less fan-out invoke `onLive`
with no guard ([`Emit.kt:23`](../src/main/kotlin/dev/kotrace/event/Emit.kt:23),
[`SpanlessEmit.kt:29`](../src/main/kotlin/dev/kotrace/event/SpanlessEmit.kt:29)); and failures can precede
the callback too — in `TracePolicy` evaluation ([`Emit.kt:20`](../src/main/kotlin/dev/kotrace/event/Emit.kt:20)),
in record construction, and in the lazy report sequence a `ReportAdapter` consumes
([`Report.kt:91`](../src/main/kotlin/dev/kotrace/Report.kt:91)). Because live fan-out runs **while `span`
records an escaping throwable** — before the rethrow — a throwing live adapter can replace the application's
throwable even before any report.

Because this spans live, span-less, and report fan-out and needs a fully specified hook contract, **it is a
separate decision — [ADR-014](adr-014-adapter-fault-isolation.md)** — that this ADR **depends on** for its
strongest guarantee. Until ADR-014 lands, ADR-013 states the **weaker** guarantee explicitly:

> Auto-root derives status from the block outcome and, in normal operation, rethrows the block's throwable
> ("unchanged" = kotrace neither swallows nor intentionally replaces it; coroutine stacktrace recovery may
> still copy the throwable *object* across a `withContext` boundary, [`Trace.kt:37`](../src/main/kotlin/dev/kotrace/Trace.kt:37)).
> A **faulty adapter** (throwing on any fan-out phase) can still replace that throwable or turn a success
> into a failure until **[ADR-014](adr-014-adapter-fault-isolation.md)** lands (this ADR depends on it for
> the full guarantee).

**Strict-mode precedence (owned here, not by ADR-014).** Under **strict-uninstalled mode**
([ADR-011](adr-011-strict-uninstalled-optin.md)) `resolvedThreadConfig()` intentionally throws
([`TraceConfig.kt:36`](../src/main/kotlin/dev/kotrace/TraceConfig.kt:36)). This can fire at **two** points,
not only at report: first while `span` records the escaping throwable (`addException` → `emit` →
`resolvedThreadConfig`, [`Trace.kt:41`](../src/main/kotlin/dev/kotrace/Trace.kt:41)), *before* the rethrow;
and again at the auto-root report step. The rule, at **both** points: **when an application throwable
already exists, preserve it and attach the strict-mode failure via `addSuppressed`** — the application
throwable always wins the propagation. When the block completed normally (no application throwable), a
strict-mode failure propagates as itself. Strict is a debug-only opt-in that release never arms, so this
only shapes debug behavior; it is defined here because it is an outcome-precedence rule, not adapter
containment.

## Options considered

- **Auto-root keyed on both getters null (chosen).** Zero new API surface, no verb choice, no runtime
  exception, and the common omission (forgetting a boundary) is impossible by construction — a bare
  top-level `span` always **attempts** `reportTrace` at its outcome (actual delivery still requires a
  configured `ReportAdapter`, [`Report.kt:43`](../src/main/kotlin/dev/kotrace/Report.kt:43)). Composition is
  free: a `span`-wrapped function reports as its own trace when called standalone and folds into a child when
  nested.
- **Naive "no collector" signal — rejected (silent-loss regression).** Ignoring `currentSpan()` breaks the
  identified-but-uncollected state: auto-root mints a collector, `createSpan` still sees the ambient span and
  builds a *child*, only the child enters the collector, and `reportTrace` finds no root and returns
  silently — reintroducing the exact bug this ADR sets out to kill, in a subtler form.
- **Explicit `trace { }` boundary (+ optional debug strict) — rejected.** Reintroduces a second verb ADR-003
  removed and a per-use decision; the nested-throw form is a runtime landmine (constraint 2); and it does
  not remove silent loss (forgetting it loses the trace), catching omission only in builds where a strict
  mode is armed. It buys visibility of the boundary at the cost of everything the maintainer optimizes for.
- **Auto-derive status from the finished tree ("any descendant `ERROR`") — rejected.** A read-time search
  cannot tell a handled-and-recovered failure from an escaped one; both leave the same birthplace error
  ([`ARCHITECTURE.md:235`](../ARCHITECTURE.md:235)). Status must come from whether the throwable escaped the
  root, which the block's completion states exactly, and which alone distinguishes `CANCELLED`.
- **Overlay a resolved config in the auto-root — rejected.** Freezes the override-then-global resolution
  ADR-010 keeps dynamic; inheriting the ambient config and falling back to the installed default is correct
  and needs no overlay.

## Consequences

- **Ordinary completion, exception, and cancellation are lifecycle-safe in core, with zero new API.** The
  consumer writes only `span { }`; the outermost one attempts `reportTrace` once, with the right status,
  in-context (delivered when a `ReportAdapter` is configured). The
  specific footgun this closes is *forgetting a boundary* on a bare top-level span — that omission can no
  longer silently drop the trace. (It is not a claim that *no* trace can go unreported: the
  identified-but-uncollected state stays live-only by design, failure-as-data returned by the block reports
  `OK` **through this (zero-config) overload** — a returned failure gets a non-`OK` verdict only through the
  return-aware overload of [ADR-016](adr-016-auto-root-returned-outcome.md) — and process death is out of
  scope — see below.) The manual `SpanCollector` + `reportTrace` path stays for bespoke control and remains
  an option for failure-as-data.
- **Behavioral migration: a formerly live-only top-level `span` now reports.** A top-level span opened with
  no collector previously reached only live adapters; it now also invokes report adapters at its outcome. An
  adapter that is both live and report will see the live record and then the report record — intended
  two-phase behavior. The one existing assertion that changes is
  [`SpanlessScopeTest.kt:194`](../src/test/kotlin/dev/kotrace/SpanlessScopeTest.kt:194) ("no `SpanCollector`,
  no `reportTrace` — live-only by design", at the test starting
  [`:188`](../src/test/kotlin/dev/kotrace/SpanlessScopeTest.kt:188)); several other top-level-span tests now
  run the report path but keep their assertions (no `ReportAdapter`, or an already-supplied collector) and
  are worth explicit report-count coverage.
- **N top-level `span` calls ⇒ N traces / N report attempts.** This follows from "each top-level span is an
  operation," but amplifies allocation and sink traffic in hot paths (each attempt allocates a collector, and
  a delivered report walks the tree and materializes `WalkEntry`s before adapters gate). A consumer wanting
  one trace around a batch introduces one enclosing `span`, or uses the manual collector.
- **Completeness requires structured concurrency.** Parallel children are storage-safe (the collector is a
  `CopyOnWriteArrayList`, [`SpanCollector.kt:47`](../src/main/kotlin/dev/kotrace/SpanCollector.kt:47), tested
  at 500 concurrent adds, [`SpanCollectorTest.kt:18`](../src/test/kotlin/dev/kotrace/SpanCollectorTest.kt:18)),
  but a complete report requires children to inherit the collector context and join
  before the root returns (ADR-006's structured-concurrency assumption). Work launched on an
  external/captured `CoroutineScope` lacking the collector becomes its own auto-root trace; unstructured work
  outliving the root can append after the report snapshot ([`Report.kt:44`](../src/main/kotlin/dev/kotrace/Report.kt:44)),
  a partial report. Structured lifetime is an explicit invariant.
- **Adapter failures still corrupt the traced operation until [ADR-014](adr-014-adapter-fault-isolation.md)
  lands.** This ADR depends on ADR-014 for the "telemetry never replaces the outcome" guarantee; see
  Decision § Adapter faults for the interim weaker guarantee and the strict-mode qualification.
- **Process death is unchanged and out of scope.** Auto-root fixes ordinary/exception/cancellation only; the
  `finally` cannot run through a hard kill. D02 stands; platform crash tooling owns fatal capture.
- **Docs move with the code.** `TraceStatus` KDoc is corrected (the verdict is the block-outcome-derived
  `TraceStatus`, not a projection of the binary root `SpanStatus`, which cannot express `CANCELLED` —
  [`TraceStatus.kt:3`](../src/main/kotlin/dev/kotrace/TraceStatus.kt:3)). ARCHITECTURE § Vocabulary and
  § Consumer boundary describe the auto-root rule; the live-vs-report and "Tail, not head" sections keep the
  wording corrections (live is synchronous handoff and awareness, not a durable verdict).

## Test matrix

Cover all four context states (auto-root; child; manual-collector root with no auto-report;
identified-but-uncollected with no report); the root is registered *before* the report and already ended
(`markEnd`) when the walk sees it; success (`OK`, one report), an escaping failure (`ERROR`, throwable
rethrown unchanged), cancellation (`CANCELLED`); a recovered child failure (root stays `OK`); failure-as-data
returned by the block reports `OK`; inherited `TraceConfig` visible at the report site, and fallback to the
installed default with no override; `withScope` inheritance; `startSpan` remains non-auto-rooting; sequential
top-level calls (N independent traces); parallel structured children (complete tree); and the existing
manual-collector path (unchanged, no double report); and **strict-mode precedence** — with strict armed and
an application throwable, the application throwable propagates and the strict failure is suppressed (both at
the exception-recording point and the report point), while a strict failure on a normally-completing block
propagates as itself. (Pure adapter-failure containment tests belong to
[ADR-014](adr-014-adapter-fault-isolation.md).)

---

[← All decisions](../DECISIONS.md)
