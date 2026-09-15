# ADR-016 — A return-aware `span` overload: auto-root maps the *returned value* to the trace outcome (failure-as-value)

- **Date:** 2026-09-14
- **Status:** Accepted
- **Affects:** `dev.kotrace.span` (`Trace.kt`) gains a **second, advanced overload** carrying a required
  `returnedOutcome: (T) -> TraceOutcome` mapper; a new public `TraceOutcome` value type
  (`TraceStatus` + `attached: List<Throwable>`). The existing zero-config `span` overload is **unchanged**.
  `autoRootSpan` learns to consult the mapper on a normal return; escaping-throwable and cancellation
  semantics are unchanged and stay owned by core. A new `FaultPhase.RETURNED_OUTCOME` is added to
  `AdapterFault.kt` so a mapper fault routes through the existing `AdapterFaultHook`. Additive public API on
  an **unreleased 0.4.0**: because the original overload is **retained unchanged**, every existing call to
  `span` — including the four-positional-argument shape — resolves to it untouched; the return-aware form is a
  **separate overload** (see § Source compatibility). (The one non-additive edge is the new public
  `FaultPhase.RETURNED_OUTCOME` constant, which would break a consumer's *exhaustive* `when (phase)` over
  `FaultPhase` — moot on an unreleased version, but noted honestly.) **Amends [ADR-013](adr-013-auto-root-span.md) §
  "Failure-as-data stays on the explicit escape hatch"**: failure-as-value is no longer *only* expressible
  through a hand-seeded collector.
- **Builds on:** [ADR-013](adr-013-auto-root-span.md) (auto-root: a top-level `span` self-owns its collector
  and reports at its outcome; status from block **completion**), [ADR-012](adr-012-reporttrace-attached-orphan-failures.md)
  (`reportTrace(attached)`: trace-level orphan failures keyed to the root, past the birthplace dedup),
  [ADR-003](adr-003-span-verb-rename-and-startspan-optin.md) (`span` is the sole node-opener; no second verb),
  [ADR-011](adr-011-strict-uninstalled-optin.md) (strict-uninstalled fail-fast), [ADR-015](adr-015-exception-origin-token.md)
  (birthplace lineage key).
- **Depends on:** [ADR-014](adr-014-adapter-fault-isolation.md) (per-adapter fault isolation) — the report
  fan-out is already guarded, so a faulty adapter never reaches the auto-root outcome code this ADR adds.

## Context

Auto-root (ADR-013) derives the trace `TraceStatus` from how the block **completes**: a normal return is
`OK`, an escaping `CancellationException` is `CANCELLED`, any other escaping throwable is `ERROR`
([`Trace.kt:92`](../src/main/kotlin/dev/kotrace/Trace.kt:92)). It reports via `reportTrace(status)` with **no**
`attached` argument ([`Trace.kt:114`](../src/main/kotlin/dev/kotrace/Trace.kt:114)). `autoRootSpan` /
`reportAutoRoot` are `private`, so a consumer has no lever over the status auto-root picks.

That is exactly right for a throw-based failure model. It is **wrong** for a failure-as-**value** model, and
the reference consumer (Camailux, [`ARCHITECTURE.md:477`](../ARCHITECTURE.md:477)) is one: a domain failure is
a **returned** `Result.Failure`, not a throw. Under a bare auto-root:

1. A failing flow **returns** `Result.Failure` → auto-root sees "normal return" → reports `OK` → a
   `status != ERROR` self-gating crash adapter drops the report — including a real birthplace throwable that
   a deeper span recorded. A monitoring **regression**, not a refactor.
2. A saga's suppressed rollback throwables live on the failed **result value** (the ADR-012 orphan case).
   Auto-root passes no `attached`, so they have **no channel** and are silently dropped.

"Just throw to force `ERROR`" is not a fix: it loses the `Result` value `observe` must return; for a *mapped*
domain error there is no real throwable to throw, so it would stamp a **synthetic** one and pollute the crash
report; and it still cannot carry `attached`.

ADR-013 anticipated this and routed it to the manual escape hatch — "a consumer with that need pre-seeds a
manual collector and calls `reportTrace(status, attached)` itself"
([`ADR-013:143`](adr-013-auto-root-span.md:143)), which [`ARCHITECTURE.md:471`](../ARCHITECTURE.md:471)
restates. That path **works and is sanctioned**, so this ADR does not exist merely to delete boilerplate.
What tips it is that a hand-rolled boundary is a lifecycle **state machine** — application failure,
cancellation, return-value classification, classifier failure, report failure, strict-mode precedence,
JVM-fatal precedence, and *not reporting twice* — and getting it wrong is silent. The reference consumer
already got it wrong: its manual boundary catches **only** `CancellationException` (its `withContext(collector)`
`try` has a single `catch (CancellationException)`), so an *ordinary escaping throwable* skips reporting
entirely — the exact hole auto-root was introduced to close, reopened by hand. Centralizing that state machine is the point; failure-as-value is a mainstream
Kotlin idiom, not an exotic edge.

## Decision

**Add a second `span` overload whose auto-root maps the *returned value* to the trace outcome. Core keeps
ownership of the thrown/cancelled outcomes.**

```kotlin
/** The trace's verdict plus any trace-level orphan failures (ADR-012), returned by a value mapper. */
data class TraceOutcome(val status: TraceStatus, val attached: List<Throwable> = emptyList())

suspend fun <T> span(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    returnedOutcome: (T) -> TraceOutcome,   // required — this overload's reason to exist
    block: suspend () -> T,
): T
```

Auto-root outcome (this overload only):

- **normal return** → `returnedOutcome(value)` decides `(status, attached)`;
- **escaping `CancellationException`** → `CANCELLED`, `attached` empty — **not** the mapper's to touch;
- **any other escaping throwable** → `ERROR`, `attached` empty — **not** the mapper's to touch;

then `reportTrace(status, attached)` at exactly the ADR-013 ordering point: **after** the root's `markEnd`,
**before** the collector context exits. The application throwable is rethrown unchanged.

On the normal-return path the mapper owns the verdict **fully**: it may return any `TraceStatus` — `OK`,
`ERROR`, or even `CANCELLED` (a value the consumer treats as a cancellation outcome) — and core does not
second-guess it. Core hard-codes a status only for the two **escaping** paths above, which the mapper never
sees.

Camailux's `observe` root path then collapses to one call, and answers this ADR's own § "birthplace stays in
the block":

```kotlin
span(name, links = …, returnedOutcome = { r ->
    (r as? Result.Failure)?.let { TraceOutcome(TraceStatus.ERROR, it.suppressedFailures) }
        ?: TraceOutcome(TraceStatus.OK)
}) {
    currentSpan()?.traceId?.let(onOpen)
    block().also { r ->
        // Recording the technical throwable stays here, on the root (== currentSpan() under auto-root),
        // via the public suspend-safe verb addException (TraceException.kt) — NOT Span.end, which is the
        // @NonSuspendTracingBridge API and would stamp the span's end early. addException appends the
        // throwable to the span's timeline as its own lineage; it does not set SpanStatus.ERROR, and it
        // need not — the crash sink self-gates on the trace TraceStatus (ERROR from the mapper), not on the
        // root SpanStatus.
        if (r is Result.Failure<*>) (r.error as? Throwable)?.let { currentSpan()?.addException(it) }
    }
}
```

### Why return-only, not a full completion mapper

An earlier sketch (plan-025 "Option B", and a `TraceCompletion<T>` = `Returned | Threw` refinement) let the
consumer classify the **escaping throwable** too. Rejected: it buys nothing the reference consumer needs — a
`Result.Failure` is a *return*, and its escaping-throwable behavior is already exactly what core owns — while
opening questions with no good answer: may a caller reclassify an escaping exception as `OK` (swallowing a
real crash from the report)? may `CancellationException` become `ERROR`? if the classifier itself throws
*while an application throwable is already escaping*, whose throwable wins? A **return-only** mapper never
sees a throwable, so none of these arise: throw/cancel precedence stays entirely in core, unchanged from
ADR-013.

### The mapper is root-report configuration, not block behavior

`returnedOutcome` runs **exactly once**, **only** when this invocation auto-roots, and **only** on a normal
return. In the three non-auto-root context states (child; manual-collector root; identified-but-uncollected —
ADR-013's four-state table) it is **never invoked** and its result would be meaningless: the enclosing root
or a manual `reportTrace` owns the single report.

- It must be **pure, fast, non-suspending** — it is telemetry configuration evaluated on the traced
  coroutine's critical path, not application logic. Documented as such.
- It is **not** invoked-then-discarded on the nested path. Running it and dropping the result would let its
  side effects fire and its exceptions alter control flow, and would falsely imply the returned outcome
  affects the enclosing trace. It simply does not run there.
- **No runtime guard** rejects "mapper supplied to a span that turned out nested." A throw-on-nested-use
  check is the precise production landmine ADR-013 rejected (constraint 2). The parameter is inert by
  omission, not by explosion. The reference consumer sidesteps the sharp edge anyway: its `observe` already
  branches on nesting (a `currentCoroutineContext()[SpanContext] != null` check takes the child path) and
  passes the mapper only on its root path.

### Mapper-fault isolation

`returnedOutcome` is consumer code and can throw. A **non-fatal** mapper throw is an instrumentation /
configuration fault, not an application failure. It must **not**:

- run inside the inner `span`'s `try/catch` ([`Trace.kt:53`](../src/main/kotlin/dev/kotrace/Trace.kt:53)) — it
  would mark the root `ERROR` and be recorded as the root's birthplace throwable;
- replace the application's returned value or its control flow.

So it is evaluated **after** the inner span has ended (its `finally` at
[`Trace.kt:72`](../src/main/kotlin/dev/kotrace/Trace.kt:72) already ran) and its throw is **contained** with a
defined contract:

- **Fall back to `TraceOutcome(OK, emptyList())`.** `OK` because it is the completion the *original*
  overload would have reported for this same normal return — the mapper failing must not invent a failure
  verdict the block did not produce, nor suppress the trace. `attached` empty for the same reason.
- **Route the fault through the existing `AdapterFaultHook`** (ADR-014). Today `FaultPhase` models only
  `LIVE / SPANLESS_LIVE / REPORT` ([`AdapterFault.kt:7`](../src/main/kotlin/dev/kotrace/AdapterFault.kt:7)) and
  the notify helper is `private` ([`AdapterFault.kt:53`](../src/main/kotlin/dev/kotrace/AdapterFault.kt:53)),
  so this ADR **adds `FaultPhase.RETURNED_OUTCOME`** and reports the mapper fault with `adapter = null` (the
  same "not attributable to one adapter" convention `guardShared` uses,
  [`AdapterFault.kt:88`](../src/main/kotlin/dev/kotrace/AdapterFault.kt:88)), broadening the hook's KDoc to
  name the new phase. This **does widen** ADR-014's *hook* surface by one phase — stated honestly — while
  reusing its containment machinery unchanged; it does **not** change how *adapter* faults on the fan-out
  phases are handled. The hook config is resolved the same way the report path resolves it, so this does not
  move strict resolution ahead of the mapper.
- **A mapper-thrown `CancellationException` is a contained mapper fault, not trace cancellation.** Trace
  cancellation is only an *escaping* `CancellationException` from the block (owned by core, § Decision); a
  `CancellationException` from the mapper on a normal return is contained like any other non-fatal mapper
  throw → `OK` fallback + hook. (The block already completed normally; there is nothing to cancel.)
- **A JVM-fatal mapper fault** (`VirtualMachineError`, `ThreadDeath`, `LinkageError` —
  [`AdapterFault.kt:36`](../src/main/kotlin/dev/kotrace/AdapterFault.kt:36)) is **rethrown**, as everywhere; no
  report is attempted, matching how a fatal fault wins over the traced outcome across the library.

### Strict-mode & report precedence unchanged

The existing `reportAutoRoot` precedence ([`Trace.kt:112`](../src/main/kotlin/dev/kotrace/Trace.kt:112)) is
untouched: under strict-uninstalled mode `resolvedThreadConfig()` throws
([`TraceConfig.kt:36`](../src/main/kotlin/dev/kotrace/TraceConfig.kt:36)); with an application throwable
escaping, a non-fatal report failure is attached via `addSuppressed` and the app throwable wins; on a normal
return a report failure propagates as itself. On the normal-return path the *sequence* is now: end the root →
run `returnedOutcome` (contained) → `reportTrace(status, attached)` (existing precedence). Note the
consumer's own birthplace recording (`Span.addException` → `emit` → `resolvedThreadConfig`,
[`TraceException.kt:169`](../src/main/kotlin/dev/kotrace/event/TraceException.kt:169)) can already trip strict
resolution *inside the block, before the mapper sees the value* — pre-existing behavior, but this ADR's tests
must cover it.

### Trace status vs. root span status stay distinct

`TraceOutcome(ERROR)` sets the **trace** verdict handed to `onReport`; it does **not** by itself flip the root
`SpanStatus` to `ERROR`, and it need not. A crash adapter self-gates on the trace `status` (the mapper's
`ERROR`), so the failed trace reports even though the auto-root's own `finally` closes the root `OK`
([`Trace.kt:72`](../src/main/kotlin/dev/kotrace/Trace.kt:72)). Recording the technical throwable so it reaches
the crash sink is the block's job, via the **public suspend-safe** `Span.addException`
([`TraceException.kt:168`](../src/main/kotlin/dev/kotrace/event/TraceException.kt:168)) on `currentSpan()` —
**not** `Span.end`, which is the `@NonSuspendTracingBridge` verb ([`Trace.kt:144`](../src/main/kotlin/dev/kotrace/Trace.kt:144))
built for `startSpan` (it requires an opt-in and stamps the span's end via `markCompleted`, closing a
suspend-owned span early). Keeping trace verdict and span status distinct is deliberate (ADR-013 already
separates `TraceStatus` from the binary `SpanStatus`).

### `attached` carries orphans only — never the birthplace throwable

`attached` throwables are appended **after** the tree walk, past the birthplace dedup
([`Report.kt:62`](../src/main/kotlin/dev/kotrace/Report.kt:62)), and keyed to the root (ADR-012). Re-supplying
a throwable that a span already records as its birthplace would emit a **duplicate** `ExceptionRecord`. So the
mapper puts *only* orphan failures (a saga's `suppressedFailures`) in `attached`; the technical birthplace
throwable reaches the report through the span tree, recorded on the root by the block's `addException`
(above). `attached` is read synchronously inside `reportTrace`
([`Report.kt:62`](../src/main/kotlin/dev/kotrace/Report.kt:62)) and not retained past the call, so no
defensive copy is required — the concern is duplication (do not put a span-recorded throwable here), not
retention.

## Source compatibility

**Additive, and a drop-in because the original overload is retained.** The hazard is precise, and it is *not*
the trailing-lambda form: a trailing lambda always binds to the final `block` parameter, and Kotlin *can*
skip a defaulted parameter that precedes it, so `span(name, attributes, links) { … }` would still resolve. The
break is the fully **positional in-parentheses** call `span(name, attributes, links, block)` — kotrace's own
recursion does exactly this ([`Trace.kt:95`](../src/main/kotlin/dev/kotrace/Trace.kt:95)), passing the lambda
as the fourth positional argument, not as a trailing lambda. A positional argument cannot skip an inserted
fourth parameter: `block` would bind to `returnedOutcome`. So *had* we added `returnedOutcome` to the single
existing `span`, that call shape would break. Therefore:

- the **existing** overload is kept verbatim (no new parameter, no behavior change), so `Trace.kt:95` and
  every other existing call site resolves to it untouched — the two-overload API is a drop-in;
- the return-aware form is a **separate overload** whose `returnedOutcome` is **required** (no default), so
  the two never collide — the presence or absence of the mapper argument selects the overload unambiguously.

0.4.0 is unreleased, so even the rejected single-overload param-insertion would carry no external burden; the
two-overload shape is chosen for call-site clarity and to keep the existing recursion untouched, not forced by
compat.

## Options considered

- **Return-aware second overload (chosen).** One verb (`span`) preserved — ADR-003/013's "the consumer only
  ever writes `span { }`" holds; composition is unchanged (root standalone, child when nested). Core keeps
  thrown/cancelled semantics. Smallest surface that closes the failure-as-value gap.
- **New `trace { }` value-aware verb (plan-025 Option B) — rejected.** Reintroduces the second verb ADR-003
  removed and ADR-013 explicitly rejected ([`ADR-013:206`](adr-013-auto-root-span.md:206)); and it has an
  unsolved nesting trilemma — throw when nested (the ADR-013 landmine), silently become a child (a `trace`
  that owns no trace), or force an independent nested trace (overlapping lifecycles, a second `trace_id`,
  surprising double reports). The chosen overload inherits none of these.
- **Full `TraceCompletion<T>` (Returned | Threw) mapper — rejected.** Lets consumers reclassify escaping
  throwables and cancellation, which the reference consumer never needs and which reopens throw-precedence
  questions core already answers. See § Why return-only.
- **New parameter on the single existing `span` (a literal B′) — rejected on compat.** Breaks fully-positional
  in-parentheses callers (§ Source compatibility). The separate-overload form is the same idea without the
  resolution hazard.
- **Do nothing in core; keep the manual path — considered, rejected for this consumer.** Coherent if kotrace
  wants to stay strictly completion-semantic. But it leaves every failure-as-value host re-implementing the
  full boundary state machine by hand, and the reference consumer's hand-rolled one already has the
  escaping-throwable reporting hole. If this ADR is *not* taken, the consumer's manual boundary must still be
  fixed (catch all throwables, not only cancellation) and encapsulated.
- **Auto-derive `ERROR` from any `ERROR` descendant span — rejected (as in ADR-013).** A read-time tree scan
  cannot tell a handled-and-recovered failure from an escaped one, and cannot express `CANCELLED`
  ([`ADR-013:210`](adr-013-auto-root-span.md:210)). A returned-value mapper states the verdict the value
  actually carries.

## Consequences

- **Failure-as-value is a first-class auto-root outcome.** A host whose domain failures are returned values
  gets correct `ERROR`/`attached` reporting from a single `span(returnedOutcome = …) { }`, with the same
  lifecycle guarantees (report once, right status, in-context, right ordering) auto-root already gives
  throw-based hosts. ADR-013 § "failure-as-data stays manual" is **amended**: the manual `SpanCollector` +
  `reportTrace(attached)` path remains for genuinely bespoke boundaries, no longer the *only* route for
  failure-as-value.
- **`span` stays the sole verb; the mapper is opt-in.** Hosts that do not need value mapping never see the
  new overload. No per-use `span`-vs-`trace` choice is introduced.
- **A new inert-when-nested parameter is a documented sharp edge.** The mapper's execution is
  context-dependent (runs iff auto-rooting); it is specified as pure/fast root-report configuration, tested to
  run zero times on the three non-auto-root states, and never invoked-and-discarded.
- **The reference consumer's escaping-throwable hole closes on adoption.** Rewiring `observe` onto this
  overload replaces its cancellation-only `catch` with core's complete outcome handling.
- **Docs move with the code.** `Trace.kt` KDoc documents the overload and the mapper contract;
  `ARCHITECTURE.md` § Consumer boundary notes that failure-as-value now has a first-class `span` form
  alongside the manual path; ADR-013 carries an "Amended by ADR-016" callout at its failure-as-data section.

## Test matrix

On the return-aware overload: mapper maps a returned failure value → `ERROR` + `attached` (one report,
orphan failures present, keyed to root, surviving the birthplace dedup); mapper maps success → `OK` (one
report, no `attached`); a returned value the mapper maps to `CANCELLED` (permitted — the mapper owns the
returned-value verdict fully; core does not second-guess it) reports `CANCELLED`; an escaping non-cancellation
throwable → `ERROR`, mapper **not** invoked, throwable rethrown unchanged; an escaping `CancellationException`
→ `CANCELLED`, mapper **not** invoked; the mapper invoked **exactly once** on a normal auto-root return, and
**zero** times in each of the three non-auto-root states (child, manual-collector root,
identified-but-uncollected).

Mapper faults: a **non-fatal throwing mapper** on a normal return → contained, `OK`/no-`attached` fallback,
value returned unchanged, fault surfaced via `AdapterFaultHook` **with `phase = RETURNED_OUTCOME` and
`adapter == null`** (assert the exact attribution); a mapper-thrown **`CancellationException`** → contained
the same way (**not** treated as trace cancellation, trace reports `OK`); a **JVM-fatal** mapper fault →
rethrown, **no report attempted**.

Precedence & wiring: **strict-mode** — with strict armed and an application throwable, the app throwable wins
and the strict failure is suppressed (both at the in-block `addException` recording point — a returned failure
whose birthplace recording trips strict escapes before the mapper is consulted — and at the report point),
while a strict failure on a normal return propagates as itself; **ordering** — the root is already ended
(`markEnd`) both when the mapper runs and when `onReport` sees the trace; **overload resolution** — a fully
positional `span(name, attrs, links, block)` (block as the fourth positional argument, the actual compat
hazard shape) and a trailing-lambda `span(name, attrs, links) { }` both still bind the original overload;
sequential top-level return-aware calls (N independent traces); parallel structured children under a
return-aware root (complete tree).
(Adapter-fault containment on the fan-out phases itself stays covered by
[ADR-014](adr-014-adapter-fault-isolation.md).)

---

[← All decisions](../DECISIONS.md)
