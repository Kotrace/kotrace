# ADR-009 — `TraceLink`: cross-trace correlation on `trace_id` only (no `span_id`)

- **Date:** 2026-08-26
- **Status:** Proposed
- **Affects:** new `dev.kotrace.TraceLink`, `dev.kotrace.Span.links`, `createSpan` / `span` / `startSpan`,
  `dev.kotrace.event.TraceRecord` + `toJson` (wire contract), `ARCHITECTURE.md` §Vocabulary + §data model

## Context

Two flows that are deliberately **separate traces** (separate `trace_id`) sometimes need a causal edge
between them. The motivating case: a UI opens one trace per user Intent — `add_microsoft_account` is one
trace, `user_report_error` (the user tapping "report this failure") is another — and the report trace must
point at the trace that produced the error the user is reporting.

`parentId` cannot express this: it is the **in-tree** edge (one `trace_id`, caller → callee). The two flows
are different trees on purpose, so there is no shared parent. This is exactly OTel's **span link** — a
causal reference that is *not* parent-child.

kotrace has no link primitive today. We own the source, so we add one. The open question this ADR settles
is **what a link references**: OTel's link targets a full `SpanContext` (`trace_id` **+** `span_id`); do we
need `span_id`?

## Decision

A link references **`trace_id` only**. New value type:

```kotlin
data class TraceLink(
    val traceId: String,
    val attributes: Map<String, String> = emptyMap(), // static symbols only — Span's PII invariant
)
```

- **`Span` carries `links: List<TraceLink>`** — birth-set and immutable, exactly like `attributes`. Settled
  at construction in `createSpan`, never mutated. The natural carrier is a trace's root span.
- **`createSpan` / `span` / `startSpan` gain a `links` parameter** (default empty), forwarded into
  `Span(...)`. `createSpan` is the single construction point, so the shape lives once.
- **Egress:** `TraceRecord` carries `links` (span-scoped, stamped onto each record lifted off the span, the
  same treatment as `info`); `toJson` emits them nested as an array:
  ```json
  "links":[{"trace_id":"abc123…","attributes":{"reason":"user_report"}}]
  ```
  A backend correlates the two trees by `trace_id` and, if it wants the failure node, walks the linked
  tree to its birthplace `ExceptionRecord` itself.

## Why no `span_id`

The link is a **birth-time** property of the *linking* span (span B), referencing the *context* of the
linked trace (trace A) — a value settled at A's construction (`hex()` mints `traceId`/`spanId` immediately),
**not** A's outcome. Given that capture model, `span_id` is unreachable or useless:

1. **A birth-time capture only sees A's root.** The context is captured when A **opens** (see the Camailux
   binding, ADR-030's `onOpen`). At that instant only A's root span exists; the failing node
   (`span(uc.login)` deep in the tree) has not been created. So any `span_id` we could capture is the
   **root's**.
2. **Root `span_id` is redundant with `trace_id`.** A backend holding `trace_id` fetches the tree and finds
   the root (`parentId == null`). The root `span_id` adds nothing.
3. **Targeting the real birthplace would re-couple to A's completion.** The birthplace is only known at
   report tail (`isBirthplaceAmong`, ADR-005). Capturing it would force the link to wait for A to finish —
   breaking the "link is fixed when B is created, and A may still be running" property that made a link the
   right tool in the first place.

So `span_id` is dropped. If a future case genuinely needs to target a specific node, extend `TraceLink`
with an optional `spanId` **then** (YAGNI now) — the wire shape (`links[]` of objects) already has room.

## Consequences

- **New wire key `links`** on `TraceRecord.toJson`, nested array (consistent with ADR-004's nested
  `attributes`/`info` — namespaced, no collision with reserved identity keys). Empty list omitted.
- **PII posture unchanged.** `TraceLink.attributes` are static symbols only, same invariant as
  `Span.attributes` — they ride the general log/backend path. `traceId` is a random hex id, not user data.
- **Span-scoped egress caveat.** kotrace flattens per-event: a record is lifted off a span only when that
  span has a reportable event. A span that carries `links` but emits **no** event produces no record, so its
  links never reach the wire. Consumers linking a trace must ensure the linking span emits ≥1 event (a
  `log`/`addNamed`); the alternative (emit a span-summary line for eventless spans) is a larger model change
  and is **not** taken here.
- **`createSpan` stays the one construction point** — `span` and `startSpan` just forward the new param.

## Rejected alternatives

- **Full OTel `SpanContext` link (`trace_id` + `span_id`).** The canonical OTel shape. Rejected for this
  library's capture model: the only `span_id` reachable at birth-time capture is the linked trace's **root**,
  which is redundant with `trace_id`; the meaningful target (the birthplace node) is unknowable without
  re-coupling the link to the linked trace's completion. `span_id` would be a field that is always the root
  or always empty — cost with no signal. Re-add if a span-level target is ever actually needed.
- **A `parentId` across the two flows (one trace).** Would make the two Intents children of one root and
  need no link at all — but it forces one `trace_id` to span the whole ViewModel lifetime, conflating
  independent flows into one tree. Rejected: per-Intent traces are the chosen model; a link connects them
  without merging them.
- **Emit a dedicated span-summary record so eventless linking spans still surface links.** Fixes the
  span-scoped caveat but adds a fourth record shape and a walk that emits for eventless spans. Rejected as
  premature: requiring the linking span to carry an event is cheap and fits the existing per-event model.
