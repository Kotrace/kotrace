# ADR-014: Per-adapter fault isolation across every fan-out phase

- **Date:** 2026-09-12
- **Status:** Accepted (amended 2026-09-27; see [Amendment](#amendment-2026-09-27-one-public-jvm-fatal-predicate))
- **Amended:** 2026-10-08: `AdapterFaultHook.onAdapterFault` renamed `FaultHook.onFault`. Since ADR-016 and
  ADR-018/020 the hook also hears of mapper and classifier faults, which belong to no adapter. 2026-10-14:
  [ADR-034](adr-034-own-faults-never-change-the-outcome.md) extends isolation to kotrace's own faults while opening
  a span (`FaultPhase.SPAN_OPEN`) and reporting (`FaultPhase.REPORT`, `adapter = null`). Same day:
  [ADR-036](adr-036-fault-hook-names-the-step.md) replaces the hook's `adapter` with `faultMessage`, a symbol naming
  the failing step. 2026-10-17: [ADR-037](adr-037-process-wide-fault-hook.md) moves the hook off `TraceConfig` to
  one process-wide `Kotrace.installFaultHook`.
- **Affects:** the three fan-out sites (live (`Emit.kt`), span-less live (`SpanlessEmit.kt`), and report
  (`Report.kt`)), plus a diagnostic hook carried on `TraceConfig` (`TraceConfig.kt`, added as a second
  constructor parameter defaulting to null). `ReportAdapter` KDoc gains a
  "consume the record sequence synchronously within `onReport`" note. No change to the record model, the
  walk, or adapter interfaces. Additive → minor version bump.
- **Builds on:** [ADR-002](adr-002-remove-capture-gate.md) (fan-out is the single filtering authority),
  [ADR-011](adr-011-strict-uninstalled-optin.md) (release telemetry must not crash the process observing it).
- **Enables:** [ADR-013](adr-013-auto-root-span.md), whose "telemetry never corrupts the traced outcome"
  guarantee depends on this ADR.

## Context

kotrace's contract is that instrumentation observes and does not alter control flow: `span` rethrows the
application throwable unchanged (ADR-013), and ADR-011 states outright that release telemetry must never
crash the process it observes. But fan-out is currently unguarded on **every** phase, so a single faulty
adapter breaks that contract:

- **Live fan-out** invokes `onLive` in a bare `forEach`
  ([`Emit.kt:23`](../src/main/kotlin/dev/kotrace/event/Emit.kt:23)); a throw propagates to the caller.
- **Span-less live** is the same shape
  ([`SpanlessEmit.kt:29`](../src/main/kotlin/dev/kotrace/event/SpanlessEmit.kt:29)).
- **Report fan-out** calls each `onReport` in a bare `forEach`
  ([`Report.kt:64`](../src/main/kotlin/dev/kotrace/Report.kt:64)); a throw skips the remaining adapters and
  propagates out of `reportTrace`.
- Failures also occur **before** the callback: `TracePolicy` evaluation
  ([`Emit.kt:20`](../src/main/kotlin/dev/kotrace/event/Emit.kt:20),
  [`SpanlessEmit.kt:26`](../src/main/kotlin/dev/kotrace/event/SpanlessEmit.kt:26)), record construction and
  the lazy message supplier ([`Emit.kt:22`](../src/main/kotlin/dev/kotrace/event/Emit.kt:22)), and the lazy
  report **sequence** an adapter consumes inside `onReport`
  ([`Report.kt:91`](../src/main/kotlin/dev/kotrace/Report.kt:91)).

The most damaging case: live fan-out runs **while `span` records an escaping throwable**, before the rethrow
([`Trace.kt:35`](../src/main/kotlin/dev/kotrace/Trace.kt:35)), so a throwing live adapter can *replace* the
application's own throwable, or turn a success into a failure. This is exactly the corruption ADR-013's
boundary must forbid, and it cannot be fixed by isolating the report phase alone.

## Decision

**Contain every adapter-attributable fault at each fan-out phase**, so one adapter can neither skip its
siblings nor propagate into the traced operation, and surface the fault through an opt-in diagnostic hook.

### Scope of isolation (what is guarded)

For **live**, **span-less live**, and **report** fan-out, the guard wraps two kinds of region:

- **Per-adapter:** that adapter's `TracePolicy` evaluation (`accepts`), its `onLive` / `onReport` callback,
  and, for report, whatever it consumes from its lazy record view *before `onReport` returns*.
- **Shared, per-event:** the one-time record construction that live/span-less fan-out does for all accepting
  adapters ([`Emit.kt:22`](../src/main/kotlin/dev/kotrace/event/Emit.kt:22),
  [`SpanlessEmit.kt:28`](../src/main/kotlin/dev/kotrace/event/SpanlessEmit.kt:28)).

**The report view is NOT pre-forced.** Core cannot iterate the adapter's `Sequence` for it without charging
filtering/message construction even when the adapter rejects the `TraceStatus`; the laziness that lets an
adapter self-gate is intentional ([`TraceAdapter.kt:35`](../src/main/kotlin/dev/kotrace/TraceAdapter.kt:35);
each view is per-adapter lazy, [`Report.kt:93`](../src/main/kotlin/dev/kotrace/Report.kt:93)). The contract
is instead:

> `reportTrace` guards the entire synchronous `onReport` invocation, so any sequence consumption the adapter
> performs before `onReport` returns is contained. Core does no pre-forcing. Retaining the `Sequence` and
> consuming it after `onReport` returns is **unsupported**: `ReportAdapter` KDoc must state that records are
> consumed synchronously within `onReport`.

**Fault attribution and the "siblings still run" guarantee are therefore split:**

- A **per-adapter** fault (its policy, callback, or in-call view consumption) is contained; **sibling
  adapters still run**, and the hook fires once for the failing adapter.
- A **shared record-construction** fault has no single owner: the event cannot be built, so **delivery of
  that one event is aborted for all accepting adapters** (there is nothing to deliver), the traced operation
  continues, and the hook fires once with `adapter = null`.

The pipeline for live / span-less is: evaluate each adapter's policy under its own guard → collect the
accepting adapters → build the shared record once under the shared guard (on failure: hook `null`, deliver
nothing) → invoke each accepting adapter under its own guard.

### The diagnostic hook

A single optional hook on `TraceConfig`, invoked when a guarded region throws:

```kotlin
fun interface FaultHook {
    fun onFault(phase: FaultPhase, adapter: TraceAdapter?, cause: Throwable)
}
enum class FaultPhase { LIVE, SPANLESS_LIVE, REPORT }
```

- **Input:** the phase, the adapter whose region threw (`null` for a shared record-construction fault), and
  the caught `Throwable`.
- **Default:** with no hook, the fault is still contained and nothing reports it (release-safe: telemetry
  failing must not surface).
  A consumer opts a hook in to observe faults, for example, log or count them (a debug build that wants a hard stop
  signals it out of band, since the hook's own throw is swallowed; see below).
- **Which `Throwable`s are contained.** Ordinary exceptions, an adapter-injected `CancellationException`, and
  `AssertionError` are caught and routed to the hook. **`VirtualMachineError` (including `OutOfMemoryError`,
  `StackOverflowError`), `ThreadDeath`, and `LinkageError` are rethrown immediately**: no hook, no sibling
  continuation. kotrace never swallows a JVM-fatal error.
- **The hook is itself guarded:** a **non-fatal** throw from `onFault` is caught and dropped; the
  JVM-fatal exclusions above (`VirtualMachineError` / `ThreadDeath` / `LinkageError`) are still rethrown.
- **Reentrancy guard (per-thread).** A **thread-local** flag marks "a hook is running on this thread" (never
  a process-global boolean, which would suppress unrelated diagnostics on other threads). While set, a
  further adapter fault on that thread is dropped without re-invoking the hook. Fan-out *is* allowed from
  within a hook, but any nested fault it triggers is simply not re-notified (the simpler of the two possible
  rules); the hook should still avoid emitting through kotrace.

### What is deliberately NOT covered

- **Configuration resolution is not an adapter fault.** `resolvedThreadConfig()` intentionally throws under
  strict-not-installed mode ([ADR-011](adr-011-strict-uninstalled-optin.md),
  [`TraceConfig.kt:36`](../src/main/kotlin/dev/kotrace/TraceConfig.kt:36)). That is a deliberate debug-only
  fail-fast about consumer setup, not a sink failing, and it stays outside this isolation. A strict-resolution
  failure therefore **passes through fault isolation to the boundary**, whose outcome-precedence rule
  (preserve an existing application throwable, attach the strict failure as suppressed) is **defined by
  [ADR-013](adr-013-auto-root-span.md); [ADR-011](adr-011-strict-uninstalled-optin.md) defines the strict
  trigger**; neither is owned here.
- **Adapters that defer their work** (hand `onLive`/the report sequence to another thread or a later time)
  are outside the guard by construction: isolation covers the synchronous fan-out call only.

## Options considered

- **Guard every phase + optional swallow-by-default hook (chosen).** Matches ADR-011's "release must not
  crash" and ADR-013's outcome guarantee, while giving consumers a way to see suppressed faults. Default
  silence is the release-correct behavior; the hook makes debug visibility opt-in, mirroring ADR-011's
  opt-in strictness.
- **Guard the report phase only: rejected.** Live fan-out runs before the `span` rethrow, so a live adapter
  can still corrupt the application throwable; report-only isolation cannot deliver ADR-013's guarantee.
- **Let faults propagate (status quo): rejected.** Violates the core contract that telemetry does not alter
  control flow, and makes any adapter bug a production incident in the traced operation.
- **Log faults unconditionally instead of a hook: rejected.** kotrace ships no logger and names no sink
  (ADR-002); an unconditional log is both a dependency it does not have and noise a release cannot silence.
  The hook lets the consumer decide, defaulting to silent.
- **Fold this into ADR-013: rejected.** It is cross-cutting (three phases, a new hook type, a
  consume-synchronously rule) and independently useful (it hardens direct `reportTrace` and span-less emit
  too, with or without auto-root). A separate ADR keeps ADR-013 about lifecycle and this about fault
  containment.

## Consequences

- **A faulty adapter (throwing a non-fatal `Throwable`) can no longer corrupt the traced operation or starve
  sibling adapters**, on any synchronous fan-out phase. ADR-013's "rethrow unchanged" becomes a real
  guarantee in normal operation. JVM-fatal errors are the deliberate exception: they are rethrown and do
  stop sibling continuation.
- **Suppressed faults are silent by default, observable on opt-in.** A consumer that wants to know its sink
  is throwing installs a `FaultHook`; release builds that install none pay nothing and never crash.
- **Report views stay lazy; isolation covers only in-call consumption.** Core does not pre-force the
  sequence; an adapter that captures the `Sequence` to iterate after `onReport` returns loses isolation,
  documented as unsupported; every shipped adapter consumes in-call.
- **A shared-construction fault aborts one event for all its accepting adapters** (there is nothing to
  deliver), while a per-adapter fault leaves siblings running: the two-tier guarantee above.
- **No interface change for adapters.** `TraceAdapter`/`ReportAdapter`/`LiveAdapter` are unchanged; the
  change is at the fan-out call sites plus the new hook on `TraceConfig`.
- **The hook is added as an optional constructor parameter.** It is a second parameter on `TraceConfig`
  defaulting to null ([`TraceConfig.kt:65`](../src/main/kotlin/dev/kotrace/TraceConfig.kt:65)), so existing
  call sites are unchanged. kotrace is pre-release with no already-compiled consumers, so no `@JvmOverloads`
  is needed to preserve the old one-argument JVM constructor signature; add it only once a published version
  must stay binary-compatible (or a Java consumer needs the one-arg overload at source level).

## Test matrix

A throwing adapter on each phase (live, span-less live, report): the traced block's result/throwable is
preserved, sibling adapters still run, and the hook is invoked once with the right phase/adapter/cause. A
throwing `TracePolicy.accepts` is contained the same way (siblings run). A throwing **shared** message /
record construction aborts that one event for **all** accepting adapters (no delivery), the operation
continues, and the hook fires once with `adapter = null`. A `VirtualMachineError` / `ThreadDeath` /
`LinkageError` from an adapter is **rethrown**, not swallowed and not routed to the hook. A hook that itself
throws is swallowed. A fault raised from within a running hook does not re-enter it (per-thread reentrancy),
while a fault on another thread is unaffected. No hook installed ⇒ a non-fatal fault is swallowed, siblings
run, operation unaffected. Strict-not-installed configuration resolution still propagates (not treated as an adapter
fault; its outcome precedence is ADR-013's).

## Amendment (2026-09-27): one public JVM-fatal predicate

The JVM-fatal set above was defined three times: an internal `isFatalFault()` in the core, and a copy in each
integration module (`kotrace-okhttp`, `kotrace-room`), which cannot see the core's `internal`. Three copies of the
rule this ADR fixes can drift apart. The core now exposes it once as public `Throwable.isJvmFatal()`, which the
core and both integrations call. It is public rather than shared through compiler friend paths, which the ABI
gate would not check: an `internal` change in the core could then break an already-published integration at
runtime. It also gives code that contains its own telemetry faults (an adapter, a bridge) the same rule. The
core's ABI grows by that one function; the set itself is unchanged.

---

[← All decisions](../DECISIONS.md)
