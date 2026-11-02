# ADR-044: A report carries one `SpanRecord` per span, behind `acceptsSpanRecords`; a cancelled span is `CANCELLED`

- **Date:** 2026-11-01
- **Status:** Accepted (implemented in 0.7.0)
- **Affects:**
  - `TraceRecord` gains a fourth kind, `SpanRecord`;
  - `TracePolicy` gains `val acceptsSpanRecords: Boolean get() = false`;
  - `SpanStatus` gains `CANCELLED`;
  - `reportTrace` emits a span entry per span and freezes each span's status and end at report entry;
  - `toJson` writes a span line (`"kind":"span"`);
  - `kotrace-okhttp` ends a caller-cancelled call `CANCELLED`.

  **Compatibility:**
  - **Source:** an exhaustive `when` over `TraceRecord` or `SpanStatus` stops compiling.
  - **Runtime:** an adapter compiled against 0.6.0 never receives a `SpanRecord`, since the gate defaults to `false`.
    Code compiled against 0.6.0 with an exhaustive `when` over `SpanStatus` (on `Span.status`, say) throws
    `NoWhenBranchMatchedException` on its first cancelled span.
  - **Behaviour:** a span whose own coroutine is cancelled reads `CANCELLED`, not `ERROR`. A check for
    `status == ERROR` stops matching it, and `renderTree` no longer counts it as failed. `TraceStatus` is unchanged.

  Code with an exhaustive `when` over either type must handle the new kind or constant; an adapter that does not
  opt in and matches no `SpanStatus` keeps working unchanged. The `api/kotrace.api` and `kotrace-okhttp` dumps, the
  outcome matrix and the CHANGELOG upgrade notes change with it, and so do the living docs that state the record
  kinds and statuses: ARCHITECTURE § Vocabulary ("record", "report", "status") and §11, the README,
  `docs/writing-adapters.md` (a span exporter recipe) and `docs/troubleshooting.md`.
- **Amends:** [ADR-022](adr-022-cancellation-vs-failure.md) (it deferred `SpanStatus.CANCELLED`).
- **Extends:** [ADR-002](adr-002-remove-capture-gate.md) (each adapter's policy is the one filtering authority),
  [ADR-019](adr-019-exception-origin-marking.md) (the report's record kinds), [ADR-029](adr-029-bounded-hot-paths.md)
  (the lazy, status-first walk).

## Context

An adapter receives `TraceRecord`s, one per reportable event, and never a span (backlog D01). A span backend (OTLP,
Tempo, Jaeger) therefore cannot be fed from the adapter callbacks:

- **A span with no reportable event is invisible.** The report walk builds entries from events only, and a
  `NamedEvent` is live-only, so a span that logged nothing, or only named events, yields no record.
- **The tree has holes.** A child's records carry a `parentId` that no record resolves, when the parent had no
  event, so a backend shows the child as an orphan.
- **Timing and status reach no record.** `startNanos`, `endNanos` and `status` live on `Span` alone.
- **A value-only failure has no identity.** With no other event, it reaches `onReport(ERROR, emptySequence())`
  (D03): no `trace_id`, no operation.

`SpanCollector.spans` is public, so a consumer seeding its own collector can read spans. That read bypasses every
policy: `Span.events` is unredacted (ADR-008/028) and `Span.attributes` unfiltered. An adapter cannot reach the
auto-root's collector at all. What is missing is a span view
that goes through each adapter's policy, like every other record.

A cancelled span is marked `ERROR` with no exception (ADR-022). Once spans are exported, that state is misleading: a
backend shows every cancelled child as a failure. It cannot be told apart afterwards either, because a value-only
failure and a bridge's `end(ERROR)` produce the same `ERROR` with no exception. ADR-022 deferred `CANCELLED` as a
display concern. A span export makes it a correctness one.

## Decision

- **`SpanRecord`, the fourth record kind.** A `SpanRecord` carries:
  - the identity every record carries: `traceId`, `spanId`, `parentId`, `operation`, `scopeId`, `spanAttributes`
    and `links`;
  - `atNanos`, the span's start;
  - `endNanos`, the span's end, null while it is open;
  - `status`, a `SpanStatus`.

  It holds no `traits` (ADR-032), no events and no throwable. The span's events stay their own records.
- **The report emits it.** The walk adds one span entry per span, immediately before that span's events, in the
  same tree order. A span with no events gets its entry too.
- **The adapter opts in with `acceptsSpanRecords`.** It defaults to `false`, so an existing adapter's report is
  unchanged. With it on, a span entry passes `acceptsSpan(span)`. `acceptsEvent` is not consulted, since there is no
  event. The record then goes through the attribute view, link attributes included, like every record (ADR-039).
  The gate is read on consumption, beside `acceptsPropagatedException` (ADR-019), so a throwing getter never aborts
  the report before the adapter's status gate ran.
- **Report-only.** A `LiveAdapter` never receives a `SpanRecord`. Delivering one when a span ends is a separate
  decision, with its own gate, duplicate and reentrancy rules (ADR-025). It never rides this gate.
- **Status and end are frozen at report entry,** with the timelines (`FrozenSpans`). An ended span's status and end
  never change, so only a span still open is copied, in one read of its atomic state (ADR-006), never through the two
  getters, which could observe different transitions.
  The auto-root's own span has ended by then (`span` ends it before it reports), so only a span still running
  elsewhere is open: a bridge span, or any span under a manual collector reported early. It is delivered with
  `endNanos == null` and the status it held, and that report never learns of its end (D05). An exporter skips such a span or represents it as open, and never invents an end time.
  Attributes keep their current rule: copied when each record is built.
- **The span entry takes no timeline slot.** It is not a `SpanEvent`, so `maxEventsPerSpan` and eviction are
  unchanged (ADR-029/033).
- **`SpanStatus.CANCELLED`.** A span is `CANCELLED` when its block ends in a genuine cancellation of its own
  coroutine, by the predicate and the once-decided verdict of ADR-022: a throw, or a classified returned failure
  whose throwable is that cancellation. It still records no exception. The bridge's `Span.end` accepts it, and
  `kotrace-okhttp` ends a call the caller cancelled (`canceled && !timedOut`) as `CANCELLED`; a timeout stays `ERROR`.
  `TraceStatus` is untouched: it already distinguishes `CANCELLED` (ADR-022).
- **`toJson` writes a span line.** `"kind":"span"` with the identity fields, `"span_status"` (`"ok"`, `"error"`,
  `"cancelled"`), `"duration_nanos"` (null while open), `"span_attributes"`, `"links"` and `"scope_id"`. It writes no
  start or end time: a `toJson` line carries no timestamp, and a pipeline stamps its own at ingest. A duration is a
  measured length and means the same in any process; a monotonic start or end means nothing outside this one.
- **Time is monotonic.** `atNanos` and `endNanos` come from `System.nanoTime()`, like every record's `atNanos`. An
  exporter converts them at export with a wall-clock anchor (`unix ≈ wallNow - (nanoNow - atNanos)`), which is an
  approximation (D08).
- **A policy can still produce a partial tree.** `acceptsSpan` rejects a span's record and its logs, but an
  exception record passes `acceptsEvent` alone, so an adapter may receive an exception from a span whose span record
  it rejected. A manual collector may also hold subtrees whose parents are elsewhere (ADR-026). The KDoc states it.

## Options considered

- **Live delivery when each span ends (the OpenTelemetry `SpanProcessor.onEnd` shape): deferred.** It streams and
  catches late spans, but delivers before the trace's outcome is known, so tail selection moves into the sink or a
  collector downstream. It adds a fan-out to every span end on the hot path and a new reentrancy surface. It can come
  later on the same record type, behind its own gate.
- **A third adapter type receiving a nested tree: rejected.** Closest to OTLP's shape, but it adds a delivery
  surface and changes the vocabulary ("adapter" is report or live) for no capability that `SpanRecord` lacks.
- **Hand adapters the `Span` objects: rejected.** `Span.events` is unredacted and `attributes` are unfiltered; the
  fail-closed invariant would not hold.
- **Synthetic start and end logs on every span: rejected.** Every existing adapter would receive them, since
  `acceptsEvent` defaults to `true`; the timeline cap evicts the oldest log first, so a busy span loses its start;
  the status would ride an attribute every adapter must admit, or a parsed message; an exporter would pair lines;
  every span would pay for it; and a "log" would stop meaning the consumer's breadcrumb. A late end log still could
  not enter a report already delivered.
- **Infer cancellation from an `ERROR` span with no exception: rejected.** A value-only failure and a bridge's
  `end(ERROR)` look the same. Cancellation is recorded when it is decided.
- **Start and end times in the `toJson` line: rejected.** They are process-local, and `toJson` carries no timestamp.

## Consequences

- A span exporter can be built on the public API: every span, its timing and status, the whole tree, behind one
  policy line, and only for the traces whose status it wants.
- A value-only failure now reaches such an adapter with its identity, operation, timing and `ERROR` status. Its
  symbolic reason is still missing (D03).
- A cancelled child no longer reads as a failure, in a span export and in `renderTree`.
- Nothing is delivered until the root ends, and a span that ends after the report is lost to the report (D05).
  Process death loses the trace (D02).
- The fields OpenTelemetry has and kotrace does not stay separate decisions: span kind (D06), a link's span id
  (D07), a capture-time wall clock (D08), trace flags and `tracestate`.
- An adapter that accepts span records receives one more record per accepted span, built only when its view is
  consumed. An adapter that gates on status first still pays no walk.
- Named events stay live-only (ADR-019). An exporter that wants them as OpenTelemetry span events needs its own
  decision.
