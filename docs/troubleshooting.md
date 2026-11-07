# Troubleshooting

Symptom first, then the causes in the order to check them. Most rows point at a row of the
[outcome matrix](outcome-matrix.md), which is generated from the code and so is the authority when this page and
the code disagree.

Two tools answer most questions:

- **A debug live adapter** whose policy accepts everything (`acceptsSensitive = true`, and `acceptsAttribute`
  returning `true`) shows every record as it happens, before any gate of your real sinks.
- **`renderTree()`** (`@UnredactedTraceRead`) prints a collected tree with statuses and events: the README's
  `render-tree` snippet. Never route its output to a sink (ADR-008).

## An exception never reaches my crash sink

1. **The trace ended `OK`.** A failure caught and recovered from inside the root is in the report, but under `OK`,
   and a crash sink gated on `ERROR` drops it. Either let it escape, or report it explicitly and ship it live
   ([writing-adapters.md § 5](writing-adapters.md#5-recipe-a-crash-sink-that-ships-explicit-reports-live)).
2. **The failure was returned, not thrown, and nothing classified it.** A returned failure is a failure only through
   a `returnVerdictClassifier` (ADR-018/020). Check that one is installed, and that the span's result is bound: in a
   `Unit`-expected position `T` is `Unit` and the classifier sees nothing (`val r: Result<X> = span(…) { … }`).
3. **The classifier returned `Failure(null)`.** That marks the span `ERROR` with no exception record, by design.
4. **The caller cancelled the root.** A `withTimeout` *around* a top-level span gives `CANCELLED` and no record;
   put the timeout inside the span to get an `ERROR` with a birthplace (ADR-022).
5. **`returnedOutcome` was set on a child span.** It is invoked only at an auto-root; on a child, its status and
   attached throwables are dropped. A wrapper that may nest registers trace-level failures with `attachException`
   inside its span block; its crash sink selects `ATTACHED` on every status.
6. **It was emitted outside any span.** `emitException` goes live only: a `ReportAdapter` never sees it.
7. **The adapter's own policy dropped it.** `acceptsEvent` can refuse an `ExceptionEvent`, and
   `acceptsExplicitException = false` removes every `addException` record from the report, including ones the
   adapter never shipped live: an `addException` call without the adapter's marker is then lost to it.
8. **kotrace was not installed when it happened.** Emits before `install` are dropped silently. Arm
   `Kotrace.strictWhenNotInstalled()` in debug builds to fail fast instead (ADR-011).
9. **The adapter threw.** The fault is contained and the record lost for that adapter only. Install a `FaultHook`
   to see it (`FaultPhase.REPORT` or `LIVE`, ADR-014/037).
10. **An attachment could not enter the report.** `attachException` needs a current span belonging to the current
    collector. The collector keeps 100 distinct identities; further registrations increment
    `Span.ATTACHED_DROPPED_ATTRIBUTE`, visible only when admitted by `acceptsAttribute`. A registration after the
    report's registry copy reaches only a later manual report (backlog D05). Install a `FaultHook` for registration
    or copy faults (`REPORT`, `report.attach` or `report.attach_registry`).
11. **The process died before the trace ended.** The tree lives in memory; nothing reports it (backlog D02).

## The same exception is recorded twice

An identity registered through `attachException` is unique within its collector, but the `attached` argument keeps
its multiplicity. Passing the same throwable through both routes, or attaching a throwable already recorded on the
timeline, reports it twice.

1. **It was reported and rethrown.** `addException(e)` then `throw e` is two lineages: `EXPLICIT` plus
   `BIRTHPLACE`. Report or rethrow, never both (ADR-041, rejected, explains why kotrace does not merge them).
2. **A sink ships `addException` live and also takes it from the report.** Set `acceptsExplicitException = false`
   on that sink (ADR-040).
3. **The policy takes the climb.** With `acceptsPropagatedException = true`, each level a failure crossed is a
   record; filter on `origin` if one per failure is wanted.
4. **The code catches and throws a new exception.** A new throwable is a new lineage, so both the original and the
   new one are birthplaces (ADR-015), even when the new one carries the original as its `cause`. Rethrow the
   original instead if one record is wanted.
5. **Two adapters feed one backend.**

## An attribute is missing from a record

1. **The adapter did not admit it.** `acceptsAttribute` defaults to `false`, for kotrace's own keys too (ADR-039).
   The span keeps the attribute; only that adapter's view loses it.
2. **It was stamped after the event, and the record is live.** A live record copies the span's attributes when the
   event is recorded. `putAttribute` later reaches the report, not records already delivered live.
3. **It was passed to `addException` and read from the report.** Those attributes merge into the live record only.

## A link is missing

1. **The record comes from another span.** Links belong to the span that declared them, not to its children or
   parent (ADR-009).
2. **It was read from a span-less record.** Those carry no links.
3. **Only its attributes are missing.** `acceptsAttribute` filters link attributes too; the link itself stays.

## No report arrives at all

1. **No `ReportAdapter` is installed, or the `install` provider threw.** A throwing provider reaches the
   `FaultHook` as `FaultPhase.INSTALL` (ADR-037).
2. **A `SpanCollector` was already in the context.** Then the top-level `span` is a manual-boundary root, and
   whoever installed the collector must call `reportTrace` (ADR-013).
3. **The span failed to open.** The block ran untraced and the fault went to the `FaultHook` as
   `FaultPhase.SPAN_OPEN` (ADR-034).

## Other surprises

- **A `CANCELLED` report holds an exception record.** A child's failure was on its way up when the caller cancelled
  the root; the record stays (ADR-022).
- **A cancelled child or sibling records no `JobCancellationException`.** By design: a span whose own coroutine is
  cancelled is `CANCELLED` and records no exception (ADR-022/044). A `CancellationException` thrown through a coroutine that is still
  active is a failure and *is* recorded: awaiting a `Deferred` that something else cancelled, or an inner
  `withTimeout`. A blocking API that surfaces cancellation as another type (`IOException("Canceled")`) is recorded as a
  failure; rethrow it as a `CancellationException`.
- **Old events are missing from a long span.** A span keeps `maxEventsPerSpan` events (default 1,000), dropping
  the oldest named event first, then the oldest log, an exception only when nothing else is left;
  `Span.EVENTS_DROPPED_ATTRIBUTE` counts them (ADR-029/033), if the adapter admits it.
- **"Flow invariant is violated".** `emit` was called inside `span { }` in a `flow { }`. Compute inside the span and
  emit outside it (`spanInFlowUsage` sample, ADR-030).
- **A log made inside `onLive` never reaches the live adapters.** It is stored but not fanned out again, so a sink
  cannot recurse into itself (ADR-025).
- **A sensitive log is missing.** `acceptsSensitive` defaults to `false`.
- **A span exporter receives no span.** `acceptsSpanRecords` defaults to `false`, and span records come from a
  report only, never live (ADR-044). With it set, `acceptsSpan` still filters them.
- **A span arrives with no end.** It was still open when the report began, such as a bridge span whose call
  outlived the root; that report never learns of its end (ADR-044, backlog D05).
- **A crash arrives without its span.** The policy's `acceptsSpan` rejected the span, but an exception record passes
  `acceptsEvent` alone (ADR-044).
- **A Room read logs no SQL, while writes do.** The query executor was set after `.tracing()`, replacing the one
  that carries the span to Room's thread; Room 2.6 runs a suspend read outside the caller's coroutine. Pass it as
  `.tracing(queryExecutor = …)` instead (ADR-043). If no SQL is logged at all, a `setQueryCallback` after
  `.tracing()` replaced kotrace's callback, or the policy refuses `sensitive` events.
- **A record has a `scope_id` but no `trace_id`.** It was emitted outside any span, inside a `withScope` or under the
  installed fallback scope (ADR-010/038).
