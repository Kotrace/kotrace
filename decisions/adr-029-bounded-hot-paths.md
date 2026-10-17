# ADR-029 — Bounded hot paths: table-lookup ids, a lazy shared report walk, a capped span timeline

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:**
  - id generation (`hex`, internal);
  - `SpanCollector.reportTrace`, whose walk is now lazy and shared across adapters;
  - `Span`: a new cap `Span.MAX_EVENTS`, `Span.droppedEvents` and the info key
    `Span.EVENTS_DROPPED_INFO` (`kotrace.events_dropped`).

  The ABI changes are additions only. One behavior change: a span keeps `MAX_EVENTS` events (briefly a few more
  while appends race).
- **Builds on:**
  - [ADR-002](adr-002-remove-capture-gate.md) (events are stored unconditionally);
  - [ADR-019](adr-019-exception-origin-marking.md) (one event snapshot per span per report);
  - [ADR-026](adr-026-report-every-root.md) (every root is walked).

## Context

The review measured three costs that grow with the workload rather than with what a consumer asked for:

1. **Id generation dominated an empty span.** `hex` formatted every byte with `"%02x".format` (24 calls per
   span). It was about 88% of an empty auto-root span's allocation.
2. **The report walk ran eagerly.** Indexing, snapshotting and sorting the tree ran before any `onReport`, so an
   adapter that self-gates on status (the `ReportAdapter` KDoc promises it "pays no walk") still paid the full
   walk. On a 3,001-span trace that was 13 µs with no adapter against 2,687 µs with one self-gating adapter.
3. **A span's timeline was unbounded.** Every append to the copy-on-write list is O(n), and every lambda and
   throwable stays alive until the root ends. 10k/20k/40k logs on one span took 50/89/215 ms. A Room-heavy flow
   stores every SQL statement even when every policy drops it.

## Decision

- **Ids:** the random bytes are encoded through a 16-char lookup table into one `CharArray`. `SecureRandom` and
  the all-zero regeneration are unchanged, and so is the format (`TraceparentTest`).
- **Report:** `reportTrace` freezes the trace eagerly, so the report's content is fixed when the report begins:
  the span list, and each span's timeline as a copy-on-write iterator (an O(1) snapshot per span, no copy). The
  tree index and walk run **lazily**, on the first adapter that iterates its records, over those frozen
  timelines, and the result is **shared** by every later adapter. An adapter that returns without iterating costs
  only the freeze. Because every non-empty collector has a root (ADR-026), "no spans" is the only early exit.
  (A first version froze only the span list. Timelines were then read at first iteration, so an event appended
  after the report began — by an adapter's own `onReport` — entered the report. Found in review; fixed the same
  day.) The `attached` orphans are read at the same moment, under the fault guard, so a later mutation cannot
  enter the report and an unreadable list costs only the orphans. Each frozen timeline is drained once and kept:
  a `lazy` does not memoize a throw, so a walk that failed inside the first consuming adapter re-runs for the
  next one, and it must read the same timelines, not exhausted iterators. (Found in a later review: a failed walk
  left the next adapter with empty timelines.)
- **Timeline cap:** a span keeps `Span.MAX_EVENTS` (1,000) events. Past the cap, the **oldest** event is
  discarded before appending, preferring a `NamedEvent` (never reported, and already fanned out live), then a
  log, because the breadcrumbs leading up to a failure are the ones that matter. (A first version treated named
  events like logs, so 1,000 analytics events pushed every log out of a failure report. Found in review.) An `ExceptionEvent` survives any volume of other events, so a crash and its lineage
  (ADR-015/019) are kept. Only a timeline holding nothing but exceptions sheds its oldest exception, so a span
  recording handled failures in a loop is bounded too; the newest, escaping one is the last appended and always
  kept. (A first version never discarded an exception, which left `addException` in a loop unbounded and
  quadratic: 20,000 exceptions, 512 ms. Found in review.) The discard count is exposed as `Span.droppedEvents` and stamped as span info under
  `kotrace.events_dropped`, so every report and live record of that span shows that its timeline is incomplete.
  Live fan-out is unaffected: each event is still offered live when it is appended. Under concurrent appends
  the cap is approximate: the check-then-trim runs under a lock, but the append itself is lock-free, so each
  writer racing past the check can add one event over the cap, up to `MAX_EVENTS` + writers − 1. The next append
  trims it back in a loop. (A first version trimmed a single event per append, which left an overshoot in place
  for good. Found in review.)

## Options considered

- **Keep the first `MAX_EVENTS` events and drop new ones — rejected.** It keeps the least useful breadcrumbs
  for a long-lived span that eventually fails.
- **A ring buffer instead of the copy-on-write list — rejected for now.** It is the right structure for dense
  spans, but the earlier lock-backed buffer experiment regressed empty traces, reads and reports (see README §
  Performance). The cap bounds the copy-on-write cost to O(`MAX_EVENTS`) per append without changing the common
  path.
- **Make the cap configurable on `TraceConfig` — deferred.** An append does not always see the flow's config
  (bridges, span-less paths), and a constant is enough to remove the unbounded growth. It can become
  configurable if a consumer needs more than 1,000 events per span.

## Consequences

Measured with JMH on the same machine as the README baseline (Apple M1 Pro, Oracle JDK 21.0.3, G1, 1 GiB),
using a **reduced** configuration of 1 fork × 3 warm-up × 5 measurement iterations. Error bars are 99.9% CIs.
These numbers show direction and size, not publication-grade figures. The 10,000-log row was re-measured with 2
forks after the eviction order changed: named events are tracked by a counter so a log-only span evicts in one
short scan, since a first version scanned the whole buffer per append and cost 9.58 ms.

| Benchmark | Before | After | Allocation before → after |
|---|---:|---:|---:|
| `LifecycleBenchmark.autoRootNoConfig` (empty auto-root) | 7.64 ± 0.29 µs | 1.05 ± 0.08 µs | 15.5 KB → 2.0 KB |
| `ReportOutcomeBenchmark` 128 spans, `REPORT_SKIP` (adapter self-gates) | 7.69 ± 0.13 µs | 0.93 ± 0.17 µs | 33.0 KB → 6.4 KB |
| `ReportOutcomeBenchmark` 128 spans, `REPORT_CONSUME` | 10.09 ± 0.51 µs | 12.02 ± 1.01 µs | 33.2 KB → 46.7 KB |
| `CaptureBenchmark` 1,000 logs on one span | 85.4 ± 2.5 µs | 82.3 ± 8.5 µs | 2.09 MB → 2.09 MB |
| `CaptureBenchmark` 10,000 logs on one span | 12.44 ± 0.41 ms | 3.49 ± 0.25 ms | 200.9 MB → 75.5 MB |

- A span that logs in a loop no longer degrades quadratically, and its memory is bounded.
- A consumer reading `Span.events` on a span that went past 1,000 events sees 1,000 of them (a few more while
  appends are still racing). Named events were discarded first, then the oldest logs, and exceptions only when
  nothing else was left. `droppedEvents` counts the discards.
- An adapter that self-gates on status now truly pays no walk, as its KDoc always said. It still pays the freeze
  (one iterator per span).
- A report that is consumed costs about 20% more than the eager walk did (the report rows above were re-measured
  with 2 forks). That is the price of freezing timelines at report start while keeping the walk lazy, and it was
  chosen over reporting whatever happens to be appended before the first iteration. Keeping each drained
  timeline for a retried walk adds about 2 KB to a consumed 128-span report (45.6 → 47.7 KB in a same-session
  2-fork run; the time difference was within noise). The skip path is unchanged (0.98 → 0.94 µs, 6.2 KB), since
  that cache is only allocated on the first read.
