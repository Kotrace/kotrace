# ADR-041: A birthplace already recorded explicitly in its trace is marked, so an adapter can keep one

- **Date:** 2026-10-29
- **Status:** Rejected 2026-10-29, after review. See [Why rejected](#why-rejected). The decision below is kept as
  proposed.
- **Affects:**
  - `Span.addException` also computes `lineageKeyOf(cause)` and keeps it on the event (internal);
  - the report index collects, per root, the lineage keys recorded explicitly;
  - `TracePolicy` gains `val acceptsExplicitlyRecordedBirthplace: Boolean get() = true`, applied in the report view
    beside the origin gates (ADR-019, ADR-040).

  Source- and binary-compatible: a defaulted member and internal state. Targets 0.6.0, unpublished.
- **Amends:** [ADR-015](adr-015-exception-origin-token.md), whose rule that each `addException` call is its own lineage
  stays true for dedup. The call's canonical key is now also read, for marking only.
- **Builds on:** [ADR-040](adr-040-explicit-exception-origin.md).

## Why rejected

The evidence the decision relies on does not prove what the gate assumes.
- **Capture is not delivery.** An explicit event is stored before fan-out (ADR-002). A sink may never have received
  it live: a policy rejected it, reentrancy skipped it (ADR-025), a fault was contained (ADR-014), or the adapter
  filters by a marker. Dropping the matching birthplace then loses the failure on that sink entirely.
- **The evidence can be evicted.** Under `maxEventsPerSpan` (ADR-033), a later exception can evict the explicit event
  before the report, so the match silently fails.
- **The scope is partial.** It does not cover an `attached` orphan (ADR-012), which gets a fresh key, nor an explicit
  emit under another root or outside the trace. Keys never cross roots (ADR-026).

A correct version needs a per-adapter acknowledgement of live delivery, which costs far more than the duplicate it
removes. "Do not both record explicitly and let the same throwable fail the trace" stays a consumer rule. ADR-040
covers the common case, an explicit record reaching the report.

## Context

ADR-040 stops an explicit record from reaching a crash sink twice. A second case remains: the consumer records a
throwable explicitly **and** lets the same throwable fail the trace.

```kotlin
span("load") {
    try { fetch() } catch (e: IOException) {
        currentSpan.addException(e)   // reported live, with its attributes
        throw e                       // climbs: the report's BIRTHPLACE
    }
}
```

This also happens when an explicit verb is followed by returning `Failure(e)`. A sink taking explicit exceptions
live and birthplaces from the report records `e` twice. A review rule ("never both") cannot be checked: no static
analysis follows a throwable value from one call to the other. kotrace already holds the evidence, because both
records resolve to one lineage key (ADR-015).

## Decision

- **`addException` records its throwable's canonical lineage** as well as its own fresh key. The fresh key still
  drives dedup, so explicit records still never collapse into each other or into a climb.
- **The report marks a `BIRTHPLACE` or `PROPAGATED` record** whose lineage matches an explicit record under the same
  root. As in ADR-015, lineage keys never cross roots.
- **The adapter chooses.** `acceptsExplicitlyRecordedBirthplace` drops a marked record before `acceptsEvent` runs.
  It defaults to `true`, keeping today's report. A sink that ships explicit exceptions live sets it to `false`.
- **Fail open, as ADR-015 does.** Only the same object or a provable coroutine-recovery copy matches. A wrap
  (`Failure(DomainError(cause = e))`) does not match, and both records report: a duplicate at worst, never a
  dropped failure.

## Options considered

- **Leave it to a consumer review rule: rejected.** It cannot be enforced, and the engine already computes the key
  that would enforce it.
- **Drop the explicit live record instead: rejected.** The live record has already gone out when the trace ends.
  Only the report can still choose.
- **Match on the cause chain: rejected for the reason ADR-015 rejected it.** `B(cause = A)` would match an
  explicit `A` and drop the real failure `B`.
- **A new `ExceptionOrigin`: rejected.** The record is still the birthplace or a propagated copy. The mark is
  orthogonal to its tree position.

## Consequences

- With ADR-040, a moment-routing crash sink records each failure once, whether it was recorded explicitly, by a climb,
  or both, using two policy lines.
- `addException` pays a bounded cause walk (`lineageKeyOf`, at most 100 steps) on the failure path.
- The report index holds one more identity set per root, allocated only when an explicit exception exists.
