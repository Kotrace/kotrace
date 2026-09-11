# ADR-012 — `reportTrace(attached)`: trace-level orphan failures on the report path

- **Date:** 2026-09-07
- **Status:** Accepted
- **Affects:** `reportTrace` gains an optional `attached: List<Throwable>` parameter (`Report.kt`); no
  change to `Span`, the walk, or any adapter. Additive public API → **0.3.0 → 0.4.0**.
- **Builds on:** [ADR-005](adr-005-birthplace-requires-throwable-drop-helper.md) (birthplace = deepest
  throwable-bearing span), [ADR-002](adr-002-remove-capture-gate.md) (fan-out is the single filtering
  authority)

## Context

The report walk emits one `ExceptionRecord` per **branch**, at the birthplace only (`isBirthplaceAmong`):
the deepest throwable-bearing span, deduped as the throwable climbs. That models a single failure
propagating up a call path.

It cannot express a **trace-level orphan failure** — a throwable that belongs to the trace as a whole but
is the birthplace of no span. The driving case is a saga: when an operation fails, its rollback may throw
while unwinding, and those secondary throwables are collected on the failed **result value**, not raised on
any span. A consumer with such throwables in hand, after its traced block returned, had no way to route
them through kotrace's report fan-out — the tree is already assembled, no span is live, and attaching them
to the root is silently dropped (the root is not the branch birthplace). The only exit was a **direct call
to the crash sink**, bypassing the one fan-out authority (ADR-002).

## Decision

**Add `reportTrace(status, attached: List<Throwable> = emptyList())`.** Each `attached` throwable is emitted
as an `ExceptionRecord` keyed to the **root** span (its `trace_id` / `operation`), appended to the walk's
entry list **after** the tree walk, then fanned to the report adapters like any other record.

Two properties make it correct:

- **Post-walk append bypasses the birthplace dedup by construction.** The birthplace gate lives *inside* the
  walk; `viewOf` (the per-adapter filter) applies only each adapter's `TracePolicy`. An entry appended after
  the walk is therefore never subject to `isBirthplaceAmong` — an orphan failure is not silenced for failing
  to be the leaf-most throwable on a branch.
- **The throwables are never written onto `Span.events`.** Doing so would give the root an `ExceptionEvent`
  and could flip it into a birthplace, **shadowing the tree's real crash origin** (the exact ADR-005 bug, in
  reverse). They exist only as synthesized report entries.

With an empty `attached` (the default) the function is byte-for-byte its old self.

## Consequences

- A consumer routes trace-level orphan failures through kotrace's report fan-out instead of calling its sink
  directly — the fan-out stays the single authority (ADR-002) for this class of failure too.
- They ride the **report** path only: they are known as values after the block returns, so they cannot be a
  live-as-it-happens event without the producer (e.g. the saga) emitting them itself. Report-path parity
  matches how the birthplace failure of a raise-only trace is handled.
- Correlation is the root's own — `operation` = the root span name, one `trace_id` shared with the tree —
  because the record is built from the root span via `recordOf`. No new correlation shape.
- Additive, source-compatible for Kotlin callers (default argument); a minor version bump for the new public
  surface.

## Rejected

- **Attach to `Span.events` on the root (or the birthplace) span.** Root: dropped by the birthplace dedup.
  Birthplace: misattributes an unwinding-time orphan as the branch's own cause, and requires re-deriving the
  internal birthplace inside the caller. Either way an `ExceptionEvent` on a span it does not belong to risks
  shadowing the real origin.
- **A span-less `emitException` per orphan.** Fans **live-only** — it never reaches the ERROR-gated report
  path, so the orphan is lost from the crash report (the very sink it must reach), and it carries no root
  correlation.
- **A dedicated `attachFailure` verb / a separate fan-out call.** More surface for what is one line inside
  the existing report fan-out; `reportTrace` already resolves the config and holds the root. A parameter on
  the one report entrypoint keeps the fan-out single.

---

[← All decisions](../DECISIONS.md)
