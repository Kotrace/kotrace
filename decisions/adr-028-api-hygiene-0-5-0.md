# ADR-028: 0.5.0 API hygiene: gate test-only and unredacted reads, validate the wire id, snapshot inputs

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:**
  - new `@KotraceTestApi` opt-in on `Kotrace.resetForTest`;
  - `@UnredactedTraceRead` now also on `Span.events` and `Span.exception` (its `@Target` gains `PROPERTY`);
  - `toTraceparent` validation;
  - `LogEvent`, `NamedEvent` and `Span.links` snapshot the maps they are given;
  - `kotrace-room` `TracingQueryCallback(delegate)` and `tracing(delegate, delegateExecutor)`.

  In the core, the only ABI change is the new annotation class; the rest is source-level (new opt-ins) or
  behavioral. `kotrace-room` does break binary compatibility (its `api` dump records it): `TracingQueryCallback` and
  `tracing()` gain parameters, so their 0.4.0 JVM signatures are gone, and code compiled against 0.4.0 must be
  recompiled. The 0.5.0 bump covers it.
- **Builds on:**
  - [ADR-008](adr-008-unredacted-trace-read-optin.md) (the unredacted-read marker);
  - [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md) (install-once);
  - [ADR-001](adr-001-span-filter-attrs-vs-emitted-info.md) (defensive copies keep live and report consistent).

## Context

0.5.0 already breaks the ABI of 0.4.0, so it is the cheap moment to close the review's API-surface gaps rather
than break again in 0.6.0:

1. **`Kotrace.resetForTest()` was plain public API.** `@TestOnly` is only an IDE hint. Production code could
   call it and defeat install-once: a later `install` would silently swap a live sink.
2. **`toTraceparent()` rendered whatever ids a `Span` held.** `Span` has a public constructor, so a
   hand-built span with made-up ids produced a malformed header. A backend discards such a header or, worse,
   starts a new trace.
3. **An unredacted read had no gate.** `collector.spans → span.events → LogEvent.message` (including
   `sensitive` messages) and `span.exception` (the raw throwable) return exactly what `renderTree` renders.
   `renderTree` required `@UnredactedTraceRead`; these reads did not. ADR-008's threat model (someone pipes
   it into `Log.d`) applies unchanged.
4. **Caller maps were held by reference.** Event attributes and `TraceLink.attributes` kept the caller's map.
   Mutating it between the live fan-out and the report made policies decide differently. Mutating it while a
   report walked could throw `ConcurrentModificationException` inside `onReport`, which fault isolation turns
   into a lost report. `Span.attributes` has been copied since ADR-001.
5. **Room's `.tracing()` replaced the app's query callback.** Room holds one callback, and nothing documented
   that `.tracing()` cannot be combined with an `SQLiteDriver`.

## Decision

- **`@KotraceTestApi`** (`RequiresOptIn`, `ERROR`) gates `resetForTest`. Consumers opt in for the test source
  set only. The repository does the same in Gradle, which is exactly the documented usage.
- **`toTraceparent` validates** that `traceId` is 32 and `spanId` is 16 lowercase hex chars, neither all zero,
  and throws `IllegalArgumentException` otherwise. Validation lives there and not in the `Span` constructor:
  only the wire needs the W3C grammar, and tests and bridges legitimately build symbolic spans. kotrace mints W3C
  ids, but a span **inherits its trace id** from its parent, so a span opened under a hand-built parent can fail
  too. *Amendment (review, same day):* the first version called `toTraceparent` unguarded in the OkHttp
  interceptor, so such a parent failed the HTTP call, and with `enqueue` it crashed OkHttp's dispatcher thread.
  The interceptor now sends the request without the header and logs a symbolic breadcrumb (ADR-023: telemetry
  never changes the call).
- **`Span.events` and `Span.exception` carry `@UnredactedTraceRead`.** Core internals read the internal buffer
  instead. Test files that inspect timelines opt in explicitly per file, so samples still show the opt-in a user
  needs. The `Span.exception` KDoc now says what it returns: the first throwable on the span, which on an
  ancestor is the propagated copy.
- **Inputs are snapshotted at construction:**
  - `LogEvent` and `NamedEvent` copy their attribute maps (the constructors are public, so the copy covers
    every path, not only the verbs);
  - `Span` copies each link's attribute map along with the list.

  `TraceLink` itself stays a plain `data class`: its ABI is unchanged, and kotrace only ever reads links
  through the span.
- **Room:** `TracingQueryCallback(attributes, delegate)` forwards every query to the app's callback after the
  span log, even if logging fails. `tracing(attributes, delegate, delegateExecutor)` runs the delegate on its own
  executor when one is given. The `SQLiteDriver` limitation is documented; Room enforces it at `build()`.

## Options considered

- **Make `resetForTest` `internal` or move it to a `kotrace-testing` artifact: rejected for now.** `internal`
  cuts off the okhttp and room modules' tests and consumers' own tests. A separate artifact is heavier than one
  opt-in marker for one function.
- **Validate ids in the `Span` constructor: rejected.** It breaks legitimate symbolic spans in tests and bridges
  for a property only the wire needs.
- **Gate `SpanCollector.spans` instead of `Span.events`: rejected.** Span names, ids, attributes and status are
  symbols by contract. Only the timeline (messages, throwables) is unredacted.
- **Snapshot in the verbs only: rejected.** `LogEvent`/`NamedEvent` constructors are public, so a snapshot
  in the class holds whatever the entry point.

## Consequences

- Source-breaking for consumers who call `resetForTest`, read `Span.events` or read `Span.exception` without
  opting in. Each needs one opt-in. Binary compatibility within 0.5.0 is unaffected.
- A hand-built span with non-W3C ids now fails loudly at `toTraceparent` instead of emitting a bad header.
- One small map copy per event that carries attributes. `emptyMap()` copies to the shared empty map.
- A Room app keeps its existing query callback by passing it as `delegate`.
