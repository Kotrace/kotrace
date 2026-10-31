# ADR-042: A span-less emit carries `links`

- **Date:** 2026-10-29
- **Status:** Rejected 2026-10-29, after review. See [Why rejected](#why-rejected). The decision below is kept as
  proposed.
- **Affects:** `emitException`, `emitLog` and `emitNamed` gain `links: List<TraceLink> = emptyList()`, written to the
  record's `links`, today always empty on a span-less record. Source-compatible. Binary-breaking against 0.5.0 (a
  new JVM signature), so it targets 0.6.0, unpublished, with the `api/kotrace.api` dump updated.
- **Amends:** [ADR-009](adr-009-trace-link-cross-trace-correlation.md) (a link can now ride a record as well as a
  span) and [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md) (the span-less verbs).

## Why rejected

- **The motivating case needs no engine change.** A consumer that records the exception *inside* the linked root
  span already gets the link: a live record copies its span's `links` (`recordOf`). The root is a real trace, with
  identity and operation, and it can reach a report-side backend. A span-less record has null trace identity and is
  live only (ADR-010).
- **Not source-compatible as written.** `emitLog(attributes, sensitive, message)` cannot take a new parameter
  without breaking either positional calls (inserted before `message`) or trailing-lambda calls (appended after
  it). Keeping it compatible needs an overload for each verb. The released 0.5.0 already has these signatures, so
  "0.6.0 is unpublished" does not excuse the break.

Reopen if a standalone record about another trace is needed where no span can be opened.

## Context

A link joins two traces where one is about the other. ADR-009's own example is a user-report trace pointing at the
failure it reports. A link rides only on a span: it is set when the span opens and copied onto that span's records.

A consumer reporting a standalone event about an earlier trace, such as "the user pressed Report on that failure",
has no span to open. Today it must:
- open a root span only to carry the link;
- emit inside it, adding a log line if it needs the root to report at all;
- duplicate the trace id as a free attribute for sinks that flatten records.

`TraceRecord.links` already exists on every record kind, and ADR-039 already filters link attributes per adapter.

## Decision

- **Each span-less verb takes `links`**, copied and made unmodifiable like a span's (ADR-028), and written to the
  record. Its link attributes pass `acceptsAttribute` like any other (ADR-039).
- **All three verbs, not only `emitException`.** They mirror each other (ADR-010). A breadcrumb or a named event
  about another trace is the same need.
- **No span-level change.** A record inside a span still carries its span's links. A span-ful verb taking extra links
  is not needed and is not added.

## Options considered

- **Keep the throwaway root span: rejected.** It invents a trace with no work in it, exists only to carry an edge,
  and may need a dummy log line to report.
- **`emitException` only: rejected.** The three verbs are kept symmetric (ADR-010), and the cost is the same.
- **A links overload keeping the old signature: rejected.** 0.6.0 is unpublished, so no published binary depends on
  the old signature.

## Consequences

- A sink reads `record.links` for the cross-trace edge instead of a consumer-chosen attribute.
- A span-less record with a link is the one record kind whose links did not come from a span. `toJson` already
  writes `links` for any record.
