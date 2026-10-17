# ADR-026 — `reportTrace` and `renderTree` walk every root of a collector

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** `SpanCollector.reportTrace` and `List<Span>.renderTree`, which now use the internal
  `rootsOf`/`TraceTreeIndex(all, roots)`. No API or wire change. A report may now carry records with more than one
  `trace_id`.
- **Builds on:** [ADR-013](adr-013-auto-root-span.md) (auto-root: one collector per top-level span),
  [ADR-015](adr-015-exception-origin-token.md) / [ADR-019](adr-019-exception-origin-marking.md) (birthplace per
  tree), [ADR-012](adr-012-reporttrace-attached-orphan-failures.md) (`attached` keyed to the root).

## Context

The report walked `spans.firstOrNull { it.parentId == null }` and nothing else. That matches an auto-rooted
trace, which holds exactly one tree. A **hand-seeded** collector, which the README still documents for bespoke
control, can hold more than one tree:

1. **Two top-level spans under one collector** (`withContext(c) { span("a") {} }; withContext(c) { span("b") {
   throw … } }`). Each span is a separate root with its own `traceId`. Only `a` was walked, so `b`'s failure was
   never reported, and nothing signalled that it had been dropped.
2. **A collector seeded inside a traced flow.** A helper that seeds its own collector, called from within
   `span("outer")`, opens spans whose parent is `outer`, which that collector does not hold. The collector has
   **no** parentless span, so its `reportTrace` returned without reporting anything, and the helper's failure
   disappeared.

`renderTree` had the same blind spot. It rendered the first root, or the first span as a fallback.

## Decision

- A **root** is any span in the collector with no parent, **or** whose parent is not in the collector (the top
  of a subtree recorded under someone else's span). The roots are walked in start order into the **one** report.
  Each record keeps its own `trace_id` and `parent_id`, so a subtree still correlates with the trace it hangs off.
- Each root is an **independent tree** for birthplace dedup. Lineage keys never merge across roots.
- `attached` (ADR-012) is keyed to the earliest root.
- `renderTree` renders each root as its own tree, with its own header, failure mark and time base.
- The one-root case, which covers every auto-rooted trace, is unchanged.

## Options considered

- **Fail fast, or signal the fault hook, when there is more than one root — rejected.** Both shapes above are
  legitimate uses of a manual collector, and the data is there to report. A hook signal would still lose the
  failure.
- **One `onReport` per root — rejected.** The caller supplies a single `status` for the whole collector, so
  splitting would imply per-tree verdicts that do not exist. One report whose records carry their own trace
  identity is the faithful shape.
- **Also register a span in every enclosing collector — rejected for now.** That would put a nested helper's
  subtree into the outer trace as well. It changes span ownership and double-reports, so it deserves its own
  decision if it is ever needed.

## Consequences

- No failure in a manual collector is dropped because it is not under the first root.
- A consumer that assumed one `trace_id` per `onReport` must group by `trace_id` when it hand-seeds collectors
  that can hold several trees. Auto-rooted traces are unaffected.
- Finding the roots costs one hash set of span ids per report, and only when the collector holds more than one
  span.
