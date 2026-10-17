# ADR-036: The FaultHook names the failing step, not the failing adapter

- **Date:** 2026-10-16
- **Status:** Accepted
- **Affects:** `FaultHook.onFault(phase, adapter: TraceAdapter?, cause)` becomes
  `onFault(phase, faultMessage: String?, cause)`; `runIsolated` takes `faultMessage` in place of `adapter`, and every
  call site names its step. Source-breaking for a hook that read the adapter; a lambda that ignored it still
  compiles. Within the 0.5.0 break.
- **Amends:** [ADR-014](adr-014-adapter-fault-isolation.md), which gave the hook the faulting adapter;
  [ADR-034](adr-034-own-faults-never-change-the-outcome.md) and [ADR-035](adr-035-integration-faults-reach-the-hook.md),
  whose faults reported `adapter = null`.

## Context

ADR-014 isolated only adapters, so the hook named the adapter at fault. Since then the hook has heard of faults
that belong to no adapter: the record a fan-out shares, the mapper and the classifier (ADR-016/018), kotrace's own
steps (ADR-034), and the integrations' steps (ADR-035). For all of those it got `adapter = null`, and `phase` alone
could not tell them apart: `HTTP` covers eight OkHttp steps, `REPORT` both an adapter and kotrace's own walk, `LIVE`
a policy, the shared record and `onLive`. The cause's stack trace was the only clue, and code shrinking (R8)
obscures it in a release build.

The adapter was the one case with an answer, and the least needed one: an adapter that wants to know its own
health can catch its own faults, since it knows which adapter it is.

## Decision

- `onFault` takes `faultMessage: String?` in place of the adapter: a stable symbol naming the step that failed,
  `null` when the step has nothing to add to the phase. It is never user data, and it survives code shrinking.
- `FaultPhase` stays the typed, coarse "where"; `faultMessage` narrows it to one step, as `domain.step`:

  | Phase | `faultMessage` |
  |---|---|
  | `LIVE` | `live.policy`, `live.record`, `live.deliver` |
  | `SPANLESS_LIVE` | `spanless.policy`, `spanless.record`, `spanless.deliver` |
  | `REPORT` | `report.attached`, `report.deliver`, `report.trace` |
  | `SPAN_OPEN` | `span.open` |
  | `RETURN_VERDICT_CLASSIFIER` | `span.classify` |
  | `RETURNED_OUTCOME` | `span.returned_outcome` |
  | `HTTP` | `http.span_name`, `http.request.log`, `http.request.body`, `http.failure.log`, `http.response.log`, `http.response.body`, `http.response.body.omitted`, `http.response.body.log` |
  | `DATABASE` | `database.query.log` |

- It is a string, not an enum, so an integration (a Ktor one) names its own steps without changing the core.

## Options considered

- **Keep the adapter and add the step (`Fault(phase, adapter, site, cause)`): rejected.** The adapter answers a
  question its own code can answer better, and it widened the hook for that one case.
- **One `source: Any?` for both: rejected.** It loses compile-time checks, and it invites passing an object whose
  `toString()` carries user data (a request, a SQL statement) into a crash reporter.
- **Finer phases (`HTTP_BODY`, `HTTP_SPAN_NAME`): rejected.** Every new step would change the core enum.

## Consequences

- A hook routes by phase and tells steps apart by `faultMessage`, also in a shrunk release build.
- A hook can no longer tell which of several adapters failed; an adapter that needs that catches its own faults.
- The symbols are part of the observable contract: renaming one is a behavior change for a hook that matches it.
