# ADR-045: `attachException` attaches a trace-level failure from any span; the report stamps it `ATTACHED`

- **Date:** 2026-11-07
- **Status:** Accepted (implemented in 0.8.0)
- **Affects:**
  - a public `suspend fun attachException(cause: Throwable)` in `dev.kotrace.event`, beside `addException` in
    `event/TraceException.kt`;
  - `SpanCollector` holds the attached failures: bounded, unique by identity, each with its owner span and the time
    it was registered;
  - `ExceptionOrigin` gains `ATTACHED`, stamped on these and on `TraceOutcome.attached`, which reads `BIRTHPLACE`
    today;
  - `reportTrace` appends the collector's attached failures after the walk, each on its owner span, before the
    `attached` argument;
  - `Span.ATTACHED_DROPPED_ATTRIBUTE` (`kotrace.attached_dropped`) counts the registrations refused past the bound;
  - `FaultPhase.REPORT` also covers registering an attached failure;
  - `toJson` writes `"exception_origin":"attached"`.

  **Compatibility:**
  - **Source:** an exhaustive `when` over `ExceptionOrigin` stops compiling.
  - **Runtime:** every existing signature is unchanged, so code compiled against 0.7.0 links. Such code with an
    exhaustive `when` over `ExceptionOrigin` throws `NoWhenBranchMatchedException` on its first attached record.
  - **Behaviour:** a `TraceOutcome.attached` record no longer reads `BIRTHPLACE`. An adapter testing
    `origin == BIRTHPLACE` stops matching it, and `toJson` changes shape for it.

  Targets 0.8.0: `gradle.properties`, the `api/kotrace.api` dump, the CHANGELOG and the README upgrade notes change
  with it. So do the living docs that state what it changes:
  - the `FaultPhase.REPORT` KDoc;
  - ARCHITECTURE § Vocabulary ("exception", "record", "report", "live"), §2 (`ExceptionRecord.origin`), §3 (what a collector
    holds), §4 (what is read below the auto-root), §10 (the `returnedOutcome` precedence), §11 (the memory invariant
    and the `returnedOutcome` limitation);
  - the memory invariant in CLAUDE.md;
  - BACKLOG D05, which gains the attached failure it cannot deliver;
  - `docs/outcome-matrix.md`, `docs/troubleshooting.md`, and `docs/writing-adapters.md`, whose compiled
    `guide-trace-viewer` snippet holds an exhaustive `when` over `ExceptionOrigin`.
- **Amends:** [ADR-012](adr-012-reporttrace-attached-orphan-failures.md) (attached failures enter only at the root),
  [ADR-019](adr-019-exception-origin-marking.md) and [ADR-040](adr-040-explicit-exception-origin.md) (an attached
  failure is stamped `BIRTHPLACE`).
- **Extends:** [ADR-016](adr-016-auto-root-returned-outcome.md) (`returnedOutcome` stays root-only),
  [ADR-026](adr-026-report-every-root.md) (several roots), [ADR-029](adr-029-bounded-hot-paths.md) (bounded memory).

## Context

A trace-level failure is a throwable that belongs to the trace and is no span's birthplace: a saga's rollback that
threw while unwinding, collected on the failed result value. ADR-012 gave it one entry, `TraceOutcome.attached`,
read from `returnedOutcome`, and ADR-016 invokes `returnedOutcome` only at the auto-root.

A wrapper that may nest therefore loses these failures. The Camailux binding's `runOperation` maps a returned
failure to `TraceOutcome(ERROR, suppressedFailures)`. Nested in another operation, it is a child: its failures reach
the report only if the same result is returned up to the root unchanged. If the outer operation recovers, wraps the
error in a new failure, or fails for an unrelated reason, they are lost. ARCHITECTURE §11 lists this as a known
limitation; Camailux tracks it as its D47.

Recording the throwable on the span does not replace it:

- `addException` is stamped `EXPLICIT` (ADR-040). A crash sink that ships explicit exceptions live drops them from
  the report, and its live record precedes the report's breadcrumb flush, so the failure reaches the sink without
  the trail of its trace.
- An event on the timeline can be evicted past `maxEventsPerSpan` (ADR-029/033).

A sink also cannot pick an attached failure out of a report. It is stamped `BIRTHPLACE`, like a child's failure that
its parent recovered from. A crash sink that delivers only on `ERROR`, the common shape, cannot deliver an attached
failure from an `OK` trace without delivering every recovered failure too. Yet the attached failure stands apart from
the verdict: a rollback that left state behind is not repaired because the caller recovered.

## Decision

- **`attachException(cause)`** registers `cause` as a trace-level failure, owned by the current span. It reads the
  span and the collector from the coroutine context, like `currentSpan()`, and resolves no configuration. It is
  `suspend` only to read that context: it never suspends and never checks for cancellation.
- **It cannot change the traced code's result.** Registration runs in `runIsolated(FaultPhase.REPORT,
  "report.attach")`: a non-fatal fault is contained, reaches the `FaultHook`, and leaves the call a no-op. A
  JVM-fatal error still propagates.
- **Where a wrapper calls it.** Inside its `span` block, after its own code returns and before the block does, so the
  current span is the wrapper's. After `span` returns, the caller's context is restored, and the failure would be
  owned by the caller.
- **The collector holds them, not the span.** The failure belongs to the trace. No field is added to every span, and
  uniqueness is decided once, when a failure is registered.
- **Registration is atomic.** One lock owned by the collector serializes the uniqueness check, the bound check and
  the insertion. A successful insertion fixes the failure's owner and its place in the report. Concurrent calls from
  parallel children have no prescribed order.
- **Unique by identity; the first registered owner wins.** `equals` is never consulted. A wrapper that registers at
  every nesting level, as each level returns, reports a forwarded failure once, owned by the deepest of them, which
  returns first. Uniqueness is collector-wide: one throwable registered under two roots of a manual collector belongs
  to the first, unlike lineage deduplication, which never crosses roots (ADR-026).
- **The owner must belong to the collector.** A collector can be installed under a span it does not hold: a manual
  collector seeded inside a traced flow. When the current span is not registered in the current collector, the call
  does nothing and consumes neither the bound nor uniqueness. Otherwise a foreign registration could take the place
  of a later valid one.
- **No collector, no effect.** Under a span without a collector (identified but uncollected, ADR-013), or outside any
  span, no report will be built, so the call does nothing. It never falls back to live delivery, a second route with
  other timing.
- **Bounded at 100 per collector,** fixed, apart from `maxEventsPerSpan`. Past it, a registration of a failure not
  already held is refused, keeping the first observed failures. Each refusal adds one to
  `kotrace.attached_dropped` on that registration's owner span, read, incremented and written under the same lock;
  a registration of a failure already held is not a refusal. Refused identities are not remembered, so the bound holds for the uniqueness state too. The count is a
  decimal string, read when each record is built, like `kotrace.events_dropped`, and an adapter receives it only
  when it admits the key (ADR-039). An owner span with no record in a report carries the count to no adapter, unless
  the adapter accepts span records (ADR-044).
- **The report copies the registry once.** `reportTrace` keeps its order and adds one step: it captures the span
  membership, freezes the spans, copies the registered failures, copies the `attached` argument as today, then
  starts the lazy shared walk. Only the registry copy holds the collector lock; reading the consumer's list and
  calling the `FaultHook` happen outside it. Each copy has its own `runIsolated(FaultPhase.REPORT, …)` guard
  (`report.attach_registry`, and `report.attached` as today) with an empty fallback, so a non-fatal fault in one
  costs only those attached failures, never the other copy or the tree's report. The copy is immutable and shared
  by every adapter. A failure whose owner span is not in the captured membership is
  skipped. A registration made after the copy reaches no report already begun.
- **Reporting does not drain the registry,** as it does not drain spans. A manual collector reported twice reports
  its earlier attached failures again, and neither the bound nor uniqueness resets.
- **Order, correlation and time.** After the walk come the registered failures, in registration order, each built on
  its owner span (`trace_id`, `parent_id`, `operation`, `scope_id`) and carrying the time it was registered. The
  `attached` argument follows, keyed to the earliest root and stamped with the report's time, as before.
- **`ExceptionOrigin.ATTACHED`** stamps both. Neither origin gate excludes it: it is neither `PROPAGATED` nor
  `EXPLICIT`. It still passes `acceptsEvent` as an `ExceptionEvent`; `acceptsSpan` and `acceptsSensitive` are not
  consulted, as for every exception; its record and link attributes pass `acceptsAttribute`. No new policy gate.
- **The `attached` argument keeps its multiplicity.** The same object listed twice still yields two records, and it
  is not deduplicated against the registered failures. Both routes stay for true trace-level failures only: a
  throwable a span already records reaches the report through the tree, and attaching it too reports it twice.
- **Report-only.** A live adapter never receives an attached failure. Live delivery would precede the breadcrumbs,
  the problem this decision removes.
- **`returnedOutcome` is unchanged,** still invoked only at the auto-root. A wrapper that may nest attaches through
  the verb, and stops passing the same failures as `TraceOutcome.attached`, which would report them twice at the
  root. The Camailux binding, whose `opened` runs the caller's block after its `onOpen`, becomes:

  ```kotlin
  span(name, returnedOutcome = { result ->
      TraceOutcome(if (result is Result.Failure) TraceStatus.ERROR else TraceStatus.OK)
  }) {
      opened().also { result ->
          (result as? Result.Failure)?.suppressedFailures?.forEach { attachException(it) }
      }
  }
  ```

## Options considered

- **Invoke `returnedOutcome` at a child, for its `attached` only: rejected.** It changes when a callback is invoked
  below the root, adding work and fault handling to every nested span that passes one, and discards the status the
  callback returns. The new origin would be needed either way.
- **Hold the failures on each span: rejected.** It adds a field to every span, needs a report-wide pass to
  deduplicate and choose an owner, and, inside the timeline, can be evicted.
- **The name `ORPHAN` (`addOrphan`): rejected.** "Orphan" already names a span-less emit in the vocabulary.
  `ATTACHED` continues ADR-012's term.
- **Uniqueness per root: rejected for now.** It needs ancestry bookkeeping at registration or a reconciliation
  against the report's membership, which the driving consumer does not need.
- **The consumer works around it on 0.7.0: rejected.** A marker exception type, `addException` without the live
  marker, and a sink that selects by type and deduplicates itself. It works without a release, but every consumer
  rebuilds it, the event can be evicted, and every nesting level emits a live line.
- **The producer reports live (the saga calls the crash sink when a compensation throws): rejected.** The failure
  then precedes the report's breadcrumbs and reaches the sink with no trail of its trace.
- **A cheap `hasAttached` signal for adapters that skip non-`ERROR` reports: deferred** (see Consequences).

## Consequences

- A wrapper that may nest registers its trace-level failures where it runs, and each reaches the report once, with
  its owner's correlation and the trace's breadcrumbs.
- A sink routes `ATTACHED` independently of the verdict: a crash sink that reports `ERROR` traces can also deliver
  the attached failures of an `OK` or `CANCELLED` trace, without delivering recovered birthplaces. It must then read
  the records of every report, giving up for itself the status-first skip of ADR-029; other adapters keep it.
  Measured in [`20261107-adr045`](../benchmarks/results/20261107-adr045/analysis.md) (HotSpot on an M1 Pro, a
  128-span tree): a crash-sink-shaped adapter on an `OK` report with no attached failure costs 17.3 µs and 60 KB,
  16.4 µs and 55 KB above a skipping adapter. One or ten attached failures add no resolved time and 4 to 7 KB, the
  owner membership set. A skipping adapter's report stays within noise of 0.7.0, 8 B more per report.
  `hasAttached` stays deferred: the saving is real per report, but no consumer has yet weighed it against its report
  rate and an Android device budget.
- Still not guaranteed:
  - delivery exactly once across routes: a throwable that both climbs and is attached reports twice, a consumer
    rule as in ADR-041;
  - a failure registered after the report's copy, such as one from a span that outlives its root (D05);
  - process death before the report (D02), or a cancellation before the failure is registered.
- Memory is bounded per span (`maxEventsPerSpan`) and per collector (attached failures).
- **Held in tests at acceptance:** identity uniqueness and the first owner under concurrent registration; the
  bound and the refusal count, including concurrent refusals on one owner; a span outside the current collector; a registration after the copy; a manual
  collector reported twice; one copy shared by every adapter; each policy gate above; span status, events and the
  traced result unchanged; `attached` multiplicity and its overlap with the registry; the `toJson` line; new
  outcome-matrix rows; and, in Camailux, its D47 conformance case inverted.
