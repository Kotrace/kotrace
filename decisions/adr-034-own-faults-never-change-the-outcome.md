# ADR-034: A fault of kotrace's own never changes the traced outcome

- **Date:** 2026-10-14
- **Status:** Accepted
- **Amended:** 2026-10-16 by [ADR-036](adr-036-fault-hook-names-the-step.md): the hook gets a `faultMessage`
  (`span.open`, `report.trace`) where this record says `adapter = null`.
- **Affects:**
  - `span`: a fault opening the span (creating it, stamping its opening attributes, registering it) is contained
    and the block runs untraced; it no longer propagates;
  - the auto-root report: a non-fatal fault of kotrace's own while reporting is contained, where it used to
    propagate after a normal return;
  - `FaultPhase` gains `SPAN_OPEN`, and `FaultPhase.REPORT` now also covers kotrace's own report faults, with
    `adapter = null`;
  - the strict-not-installed failure gets its own internal type, a subclass of `IllegalStateException`, so a
    caller catching `IllegalStateException` still catches it.

  ABI: one enum constant added.
- **Amends:** [ADR-013](adr-013-auto-root-span.md), where a non-fatal report failure propagated after a normal
  return and a failure opening the root propagated; [ADR-014](adr-014-adapter-fault-isolation.md), whose isolation
  covered the adapters, policies and mappers but not kotrace's own code. Keeps
  [ADR-011](adr-011-strict-uninstalled-optin.md): the strict failure still reaches the traced code.

## Context

ADR-013 and ADR-014 promise that telemetry never corrupts the traced outcome, and every consumer callback runs under
fault isolation (`runIsolated`). Two places still let a fault through:

- **The auto-root report.** `reportAutoRoot` caught every non-fatal failure of `reportTrace` and treated them
  alike: thrown after a normal return, suppressed onto an escaping throwable. The only failure meant to arrive
  there is the strict-not-installed one, but the few unguarded lines of `reportTrace` (copying the collector's
  spans, freezing the timelines) would take the same path. A bug there would turn a successful call into an
  exception in a release build.
- **Opening a span.** `createSpan` and the collector registration ran before the block, unguarded. A failure there,
  such as a `null` value in the opening `attributes` passed from Java, failed the call before the block ran.

## Decision

- **Strict is the one exception.** The strict-not-installed failure is thrown as an internal
  `StrictNotInstalledException`. `reportAutoRoot` keeps ADR-013's handling for it alone: thrown after a normal
  return, suppressed onto an escaping throwable. Every other non-fatal fault goes to the `FaultHook` as
  `FaultPhase.REPORT` with `adapter = null` and is dropped.
- **A span that fails to open runs untraced.** `executeSpan` contains a non-fatal fault while opening, reports it
  as `FaultPhase.SPAN_OPEN`, then runs the block with no span of its own: it returns or throws exactly as it would
  untraced, and no classifier runs on its value. Spans the block opens parent under the unopened span's parent; at
  the auto-root they become roots of its collector, which the report walks (ADR-026), so their trace is not lost.
- A JVM-fatal error still always propagates.

## Options considered

- **Contain everything, strict included: rejected.** Strict mode exists to fail loudly in a debug build
  (ADR-011); containing it would leave a missing install silent exactly where it is most common.
- **Tell strict apart by `Kotrace.isStrictWhenNotInstalled()` instead of a type: rejected.** Armed strict mode does
  not make every report failure a strict one; only the type says which failure it is.
- **Let a failure opening the span propagate, as before: rejected.** The block has not run yet, so failing the
  call there drops the feature's work for a telemetry fault.
- **Extend it to `startSpan`: deferred.** The bridge must return a `Span`, so it has no untraced fallback without
  a no-op span type.

## Consequences

- In a release build, the traced code's result is now independent of kotrace, short of a JVM-fatal error.
- A span that failed to open is missing from the tree; the `FaultHook` is the only signal, so a consumer that
  wants to see it must install one.
- An auto-root whose root failed to open reports `OK` unless its `returnedOutcome` says otherwise, since no
  classifier ran: there was no span to mark.
- The containment of a report fault has no test that triggers it: no public input reaches the unguarded lines,
  which is why such a fault is unlikely. Only the strict branch of that handling is tested.
