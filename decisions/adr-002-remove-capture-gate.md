# ADR-002 — Remove the capture gate; fan-out is the single filtering authority

- **Date:** 2026-08-21
- **Status:** Accepted (implemented)
- **Affects:** `dev.kotrace.TraceConfig` (removes `eventAccepted`), `dev.kotrace.event.Span.log`,
  `dev.kotrace.event.emit`, `renderTree`; supersedes the capture-gate consequence of
  [ADR-001](adr-001-span-filter-attrs-vs-emitted-info.md)

## Context

Two filtering mechanisms sat in the event path:

- **Fan-out (delivery)** — per-adapter `policy.accepts` at `emit` (live) and `viewOf` (report). This is
  the authority: it decides what each consumer's adapter actually sees.
- **Capture gate (storage)** — `TraceConfig.eventAccepted`, the union
  `adapters.any { acceptsSpan(span) ∧ acceptsEvent(event) }`, applied in **`Span.log` only**. Not a
  reportability check — a storage-and-message-build optimization derived from the same per-adapter
  policies, pre-applied early. `addNamed` / `addException` never consulted it.

The gate was landed under ADR-001 once filter attributes became immutable at birth (so a span gate
decides identically at capture and report). But it is a second place filtering logic lives, it makes
`renderTree` silently non-honest (it printed only what the gate had already admitted, while claiming
"all levels"), and its stated payoff — not building a rejected log's lazy message, and not storing a
rejected span's breadcrumbs — turns out to be either recoverable elsewhere or a guard for an
out-of-scope regime.

## Decision

Remove the capture gate. Fan-out (`policy.accepts`) is the **single** filtering authority. Every event
verb — `log`, `addNamed`, `addException` — appends to `Span.events` unconditionally; what a consumer
sees is decided once, per adapter, at delivery.

To keep the message-build win, `emit` filters **before** building the record:

```kotlin
internal fun Span.emit(event: SpanEvent, config: TraceConfig?) {
    events += event
    val accepting = config?.liveAdapters?.filter { it.policy.accepts(this, event) }.orEmpty()
    if (accepting.isEmpty()) return
    val record = recordOf(event)   // resolves the lazy message only when someone wants it
    accepting.forEach { it.onLive(record) }
}
```

The report path already builds `recordOf` only for accepted events (`viewOf` filters then maps). So no
rejected message is ever built, gate or no gate.

## Options considered

- **A. Remove it (chosen).** Uniform verbs, one authority, honest `renderTree`. Deletes `eventAccepted`,
  its union-soundness KDoc, and the storage-side tests.
- **B. Config flag to toggle the gate — rejected.** Doesn't resolve the question, hands it to a consumer
  with less context, and doubles the behavior surface (two paths to test and document). A toggle to
  avoid choosing.
- **C. Replace with a policy-free per-span event cap** (max N events/span, drop-with-counter) — rejected
  *for now*, kept as a follow-up. It is the right shape only if long-lived / loop-heavy traces become
  in-scope; that is the streaming regime kotrace declares it is not for (ARCHITECTURE §1: one call tree
  **per flow**, **tail**). YAGNI today; revisit if the tail buffer ever needs backpressure — at which
  point it bounds **spans** too, not just events.

## Consequences

- **Single authority.** Filtering lives in exactly one place (`policy.accepts` at fan-out). Registering
  an adapter is still the only step to admit a level or layer.
- **`renderTree` is honest.** It now shows every stored log, including ones no adapter accepts — it no
  longer inherits a pre-filter it never named. Demo tree output grows.
- **Message-build cost unchanged.** No rejected log's lazy message is built (the `emit` fix + `viewOf`).
- **The one real cost:** a rejected span now allocates the `LogEvent`, appends it, and retains its
  message-lambda captures for the trace lifetime (closure retention — the real memory cost, not the tiny
  event). Bounded by the flow: a trace is finite (tail, one flow), so event count is bounded by it. The
  pathological long-lived/loop-heavy case is the streaming regime kotrace is not for, and that already
  blows the tail buffer on **spans**.
- **Not worsened:** PII/sensitive posture (sensitive was always stored unconditionally — the gate never
  applied the sensitive filter) and report output (fan-out `accepts` untouched). The `ExceptionEvent`
  already bypassed the gate, so crash reporting is unchanged.
- **Supersedes** the "Capture-gate optimization landed" consequence of ADR-001; that bullet is annotated
  accordingly.
