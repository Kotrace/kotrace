# ADR-001 — Span filter attributes (fixed) vs emitted info (dynamic)

- **Date:** 2026-08-18
- **Status:** Accepted (implemented — filter/info split + capture-gate `acceptsSpan` union)
- **Affects:** `dev.kotrace.Span`, `dev.kotrace.TracePolicy.acceptsSpan`, `TraceRecord` egress,
  `kotrace-okhttp` (`TracingInterceptor` `http.status`)

## Context

A `Span` carries `attributes: MutableMap<String, String>`. Two kinds of value land in that one map:

- **Fixed, birth-set** — passed at span creation (`trace(name, attributes)` /
  `startChildSpanHere(name, attributes)`). Example: `layer=http`, `layer=repo`. Set once, never changes.
- **Dynamic, late-written** — stamped after the span opens, once a result is known.
  Example: `kotrace-okhttp`'s `TracingInterceptor` writes `span.attributes["http.status"] = code` when
  the response returns, *between* the request-side events and the response-side events.

`acceptsSpan(span)` — the per-span filter in `TracePolicy` — reads this map. It runs on **both**
timings (`Emit.kt` at live emit, `Report.kt` at report fan-out), reading `span.attributes` **as they
stand at call time**.

This creates a correctness trap. A dynamic value is only final at span end:

- At **report** the span is complete → the value is present → filtering on it is correct.
- At **live** the filter runs per event, mid-span → the value is present for events emitted *after* the
  write and absent for events *before* it. A policy that drops `http.status == "500"` on a `LiveAdapter`
  leaks the failing call's request breadcrumbs and drops only its response ones. The value the filter
  reads depends on emit order — a race, not a decision.

kotrace is an open-source library: it cannot assume how many dynamic attributes a consumer defines or
that consumers will avoid filtering on them. The contract has to make the trap unrepresentable, not
merely documented.

## Decision

Separate the two **roles** a span value can play. They are different concerns that happened to share
one map:

1. **Filter dimensions are fixed and immutable.** `Span.attributes` becomes an immutable
   `Map<String, String>`, set once at construction. It is the **only** input `acceptsSpan` ever sees.
   Because it never changes after birth, `acceptsSpan` is reliable at live *and* report by construction.

2. **Late-known values are emitted info, never a filter key.** A dynamic value (`http.status`) is
   written through a separate span channel that flows into the emitted `TraceRecord` as payload but
   **does not reach `acceptsSpan`**. Consumers read it off the record; nobody filters on it. Its value on
   a live record is best-effort at emit time and final on the report record — acceptable, because it is
   information, not a decision input.

The rule, stated once: **filter = fixed birth attributes; anything known late is record payload, never
a filter input.** `kotrace-okhttp` moves `http.status` off the filter map onto the emitted-info channel
(carried on span completion, so it surfaces on the report record).

## Consequences

- The live-filter race is **unrepresentable**: a dynamic value is not in the map `acceptsSpan` reads, so
  no policy can gate on it at the wrong time.
- **Capture-gate optimization landed** *(superseded by [ADR-002](adr-002-remove-capture-gate.md),
  2026-08-21 — the capture gate was removed; fan-out is now the single filtering authority)*.
  `TraceConfig.eventAccepted` was an event-only union because span attributes were mutable (a late attribute
  could retroactively change a span filter, so span gating had to wait for report). With filter attributes
  now immutable at birth, the capture gate unioned `acceptsSpan(span) ∧ acceptsEvent(event)` **per adapter**
  and skipped *storing* log events for a span no adapter's span-filter wanted. The immutability finding
  stands; the gate built on it did not — ADR-002 shows the storage skip guarded an out-of-scope regime and
  the message-build win is recoverable at fan-out.
- `http.status` stays a structured, per-span, queryable datum — just on the emitted-info channel, not the
  filter map. A report still reads it as a field; it is not re-parsed out of a log line. Its shape
  (structured, one-per-span) is unchanged; only its *slot* (info, not filter) and *timing* (report,
  honest) change.
- **Migration touch points:** `Span.attributes` immutable; a span info/completion channel added;
  `TraceRecord` exposes emitted info; `TracingInterceptor` writes `http.status` through the new channel
  instead of `span.attributes[...] =`. Contained to core span/record + the okhttp adapter.

## Rejected alternatives

- **Leave one `MutableMap`, document "don't filter live on late attrs."** A convention a library cannot
  enforce; an unknown consumer hits the race and blames kotrace. Rejected — the contract must make it
  unrepresentable.
- **Two maps, both reaching `acceptsSpan` (fixed + dynamic, filterable at report only).** Still lets a
  consumer filter on a dynamic attribute — safe at report, racy at live — so it reintroduces "which phase
  may filter what." Rejected: the cleaner invariant is that late values are *never* a filter input, at
  any phase.
- **Split the policy hook (`acceptsSpanLive` / `acceptsSpanReport`).** Two hooks the consumer must keep
  consistent, re-deriving a timing distinction the framework already knows from span lifecycle. Rejected —
  keep one `acceptsSpan`; the live/report difference falls out of which values exist when, not a second
  predicate.
