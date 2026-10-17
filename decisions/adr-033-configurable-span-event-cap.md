# ADR-033: The span event cap is configurable per flow, fixed when a span opens

- **Date:** 2026-10-11
- **Status:** Accepted
- **Affects:**
  - `TraceConfig` gains `maxEventsPerSpan`, default `Span.MAX_EVENTS` (1,000);
  - `Kotrace.install` (both overloads) gains `maxEventsPerSpan`, before the `provider` lambda;
  - `Span` gains `maxEvents`, a constructor parameter defaulting to `Span.MAX_EVENTS`, and caps its timeline at
    it instead of the constant.

  Source-compatible: every new parameter has a default. The constructors and `install` change their JVM
  signatures, inside the 0.5.0 binary break. With nothing set, behavior is unchanged.
- **Builds on:** [ADR-029](adr-029-bounded-hot-paths.md), which capped the timeline at a constant and deferred
  this option; [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md), the per-flow override over the installed
  configuration.

## Context

ADR-029 bounded each span at 1,000 events, dropping the oldest first, so a span that logs past the cap reaches
the report with its earliest breadcrumbs gone and only a count (`kotrace.events_dropped`) in their place. One
flow can need more, such as a long sync whose first lines hold its setup, while the rest of the app keeps the
default.

ADR-029 deferred making the cap configurable because an append does not always see the flow's configuration: a
bridge or a span-less path may append on a thread no coroutine mirrors it onto, and two writers on one span
could see two different caps.

## Decision

- `TraceConfig.maxEventsPerSpan` sets the cap, so it follows the two existing levels: `Kotrace.install(…,
  maxEventsPerSpan = …)` for the process, `withContext(TraceConfig(…, maxEventsPerSpan = …))` for one flow.
- **A span reads it once, when it opens,** and keeps it as `Span.maxEvents`. An append never reads the
  configuration, which answers ADR-029's concern: every writer of one span sees one cap, whatever thread it runs
  on, and a configuration overlaid after the span opened leaves it alone.
- The span takes the per-flow override's value, else the installed one, else `Span.MAX_EVENTS`. Reading the
  installed value never forces the `install` provider and never trips the strict latch: opening a span is not
  a fan-out, as reading the `ReturnVerdictClassifier` is not (ADR-018). The value is therefore stored beside
  the lazy configuration, not read from it.
- The cap is **per span**, not per trace: a trace of ten spans can hold ten times as many events. A span that
  logs heavily evicts only its own oldest events, never another span's.
- It must be at least 1, since the newest exception is always kept. `TraceConfig`, `install` and the `Span`
  constructor reject anything lower with `IllegalArgumentException`; `install` checks it at the call, not when
  the provider resolves.

## Options considered

- **A `maxEvents` parameter on `span` and `startSpan`: rejected.** The cap is an operational limit, not part of
  what a span means, and both signatures are already long.
- **Read the cap on every append: rejected.** It is ADR-029's objection: writers on different threads could
  apply different caps to one span.
- **Children inherit the cap from their parent span: rejected.** It would make the cap truly per trace, but an
  overlay inside a trace could no longer change it, unlike every other configuration value.
- **One cap for the whole trace: rejected.** A child logging in a loop would evict the root's breadcrumbs, the
  context that matters most, and a trace-wide eviction would have to choose between spans.

## Consequences

- A flow that needs a longer timeline raises its cap without changing the rest of the app.
- A larger cap costs more than linearly: the timeline is copy-on-write, so every append copies it, and ADR-029
  measured the cost only at 1,000. `TraceConfig`'s KDoc says so.
- Total memory per trace is still bounded only per span; the number of spans is not capped.
