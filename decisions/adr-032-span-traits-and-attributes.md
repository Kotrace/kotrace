# ADR-032: Span `traits` filter, span `attributes` are the payload

- **Date:** 2026-10-10
- **Status:** Accepted
- **Affects:** the names of the span's two string maps across the API and the wire:
  - the filter dimensions `Span.attributes` become `Span.traits`, and the `attributes` parameter of `span`,
    `startSpan` and `TracingCallFactory` becomes `traits`;
  - the payload `Span.info` / `putInfo` becomes `Span.attributes` / `putAttribute`, and
    `Span.EVENTS_DROPPED_INFO` becomes `EVENTS_DROPPED_ATTRIBUTE`;
  - `TraceRecord.info` becomes `spanAttributes`, and the `info` parameter of `addException` / `emitException`
    becomes `attributes`;
  - `toJson` renders the span's map under `"span_attributes"` instead of `"info"`;
  - `span` and `startSpan` gain an `attributes` parameter, after `links`, that seeds `Span.attributes`.

  No existing behavior changes: which map filters and which map rides the record are exactly as ADR-001 set
  them; the new parameter only lets a span open with its attributes already set.
- **Builds on:** [ADR-001](adr-001-span-filter-attrs-vs-emitted-info.md), which split the two roles;
  [ADR-004](adr-004-tojson-nested-attributes-info.md), the nested wire shape.

## Context

ADR-001 gave a span two string maps with opposite roles: `attributes`, immutable filter dimensions that only
`TracePolicy.acceptsSpan` reads and that never reach a record; and `info`, the payload that rides every record
and that no policy reads. The names did not say which was which:

- `attributes` is what most tracing APIs call the data a span **exports**, yet here it was the one map that never
  leaves the span. A consumer setting `layer=http` and expecting it on its log lines saw nothing, only in
  `renderTree`.
- `info` was the map that actually reached the sink, under a name that suggests a side note.

The reference consumer, Camailux, never set or read the filter map: everything it wants on a record goes
through `info`. The mismatch is a naming problem, not a design one.

## Decision

Name each map by what it is:

- **`traits`**: fixed at birth and immutable, the span's inherent characteristics. They are the only per-span
  input `acceptsSpan` reads, and they never reach a record.
- **`attributes`**: the values a sink sees, set when the span opens (`span(…, attributes = …)`,
  `startSpan(…, attributes = …)`) or stamped later through `putAttribute`. Set at opening, they ride every record
  from the first event; before, a value had to be stamped by hand at the top of the block, and a live record
  emitted earlier missed it. The parameter follows `links`, so an existing positional `span(name, traits, links)`
  call keeps its meaning.

On a `TraceRecord` the span's map sits beside the event's own map (`LogRecord.attributes`,
`NamedRecord.attributes`), since a record flattens one event together with its span. The span's map therefore
takes the prefix: `spanAttributes` on the record and `"span_attributes"` on the wire, like `spanId` beside it.
The event's `attributes` keep their name and meaning.

## Options considered

- **Keep the names, document the roles: rejected.** The confusion is in the names themselves; every reader
  would still have to learn that `attributes` do not reach the sink.
- **`dimensions` or `fixedAttributes` for the filter map: rejected.** `dimensions` stresses filtering rather
  than immutability; `fixedAttributes` is long and keeps the word that now names the other map.
- **Prefix the event's map instead (`record.attributes` for the span, `eventAttributes` for the event):
  rejected.** `record.attributes` would change meaning while still compiling, so a sink reading event
  attributes (Camailux's analytics adapter) would silently read the span's map.
- **Rename the API but keep the `"info"` wire key: rejected.** The API and the JSON would name the same map
  differently. 0.5.0 already breaks the wire (ADR-021), so the key moves with the name.

## Consequences

- Source-breaking for every listed name. A named `attributes =` argument to `span` / `startSpan` /
  `TracingCallFactory` and every use of `info` / `putInfo` fail to compile and point at the fix.
- **One break compiles silently:** a policy reading `span.attributes` in `acceptsSpan` now reads the payload.
  The README upgrade notes call it out; such a policy must switch to `span.traits`.
- Wire-breaking: a log query or dashboard on `info.*` moves to `span_attributes.*`.
- `renderTree` labels the filter map `traits:`.
