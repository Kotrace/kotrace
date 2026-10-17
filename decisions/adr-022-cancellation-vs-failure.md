# ADR-022 — Cancellation vs failure: `CANCELLED` iff the running coroutine is itself cancelled; cancelled spans record no exception

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** `span` (`Trace.kt`) — the escaping-outcome rule of the auto-root, the returned-`CausedBy`
  verdict, and what a span's catch records. No API change; a **semantic** change to `TraceStatus` and to which
  `ExceptionRecord`s a trace emits.
- **Amends:** [ADR-013](adr-013-auto-root-span.md) ("an escaping `CancellationException` → `CANCELLED`") and
  [ADR-018](adr-018-ambient-failure-detector.md) option D ("a returned `CausedBy(CancellationException)` mirrors a
  thrown one → `CANCELLED`"). The mirror principle stands; the predicate both sides share changes.

## Context

Core keyed cancellation on the exception **type**: any escaping `CancellationException` made the trace
`CANCELLED`, and every span's catch recorded it like any other throwable. Two production patterns break that:

1. **A timeout was reported as a cancellation.** `span("load") { withTimeout(5_000) { api.fetch() } }` — when the
   timeout fires, `withTimeout` throws `TimeoutCancellationException` (a `CancellationException`) *to its caller*;
   the span's own coroutine is still active. The trace was `CANCELLED`, so a failure sink that gates on `ERROR`
   (or drops `CANCELLED`, as backlog D02 and the report-adapter sample recommend) never saw the timeout. The same
   held for `runCatching { withTimeout { … } }` returned as a `Result.failure` through the classifier.
2. **A cancelled sibling was reported as a crash.** In `coroutineScope { launch { span("a") { … } }; launch {
   span("b") { throw boom } } }`, `b` fails, `a` is cancelled; `a`'s catch recorded the `JobCancellationException`.
   Its lineage differs from `boom` (different class), so the report carried **two** `BIRTHPLACE`
   `ExceptionRecord`s under an `ERROR` trace — a crash sink received a spurious cancellation "crash" per cancelled
   sibling, and could not filter it by status because the trace really had failed.

## Decision

One predicate, used everywhere core interprets a cancellation:

> A throwable is a **genuine cancellation** iff it is a `CancellationException` **and** the current coroutine is
> no longer active (`!currentCoroutineContext().isActive`).

This is the kotlinx.coroutines guidance for telling "I was cancelled" from "a `CancellationException` was thrown
through me": a sibling failure, a cancelled caller, or an enclosing timeout cancel the *running* coroutine; an
inner `withTimeout` expiring, or awaiting a `Deferred` that was cancelled, does not.

- **Auto-root, escaping:** genuine cancellation → `CANCELLED`; anything else → `ERROR` (timeouts included).
- **Auto-root, normal return:** a classified `CausedBy(CancellationException)` → `CANCELLED` only under the same
  predicate, else `ERROR`. (A genuinely cancelled root almost never returns normally — `withContext`'s prompt
  cancellation turns it into a throw — so in practice a returned cancellation is a failure.)
- **Every span's catch:** still marks the span `ERROR` (`SpanStatus` stays binary), but a genuine cancellation
  records **no** `ExceptionEvent` — so it fans out neither live nor on the report path. A `CancellationException`
  seen while the coroutine is active is recorded like any failure.
- **Decided once (amendment, 2026-09-25).** The predicate is evaluated **once**, where the throwable is first
  seen: in the root span's catch for an escaping throwable, and when a returned `CausedBy` is classified. That
  single verdict both chooses whether to record the throwable and is carried to the auto-root, which never
  re-checks `isActive`. Re-checking let a Job cancelled *after* the failure was recorded (by a synchronous live
  adapter or another thread) report `CANCELLED` for a trace carrying that failure's `ExceptionRecord`. A
  returned `CausedBy(CancellationException)` judged a genuine cancellation is, like a thrown one, not recorded.
- **A nested failure overtaken by a cancellation (amendment, review).** The single verdict covers the throwable
  the root itself sees. A *nested* span can record a `CancellationException`-typed failure while still active (an
  inner `withTimeout` expiring in a child), and the caller can cancel before that throwable reaches the root.
  kotlinx.coroutines then hands the next scope the Job's own `CancellationException` in its place, so the root,
  genuinely cancelled, reports `CANCELLED`, and the report still carries the child's record. Both happened: the
  failure first, then the cancellation. A failure of any other type is kept over the cancellation by
  kotlinx.coroutines itself, so it reaches the root and stays `ERROR`. Carrying the child's verdict to the root
  instead was rejected: it would mean tracking in-flight failures across scope boundaries in every span's catch, to
  settle a race. A sink that must see every failure also reads the exception records of `CANCELLED` traces.

## Options considered

- **Special-case `TimeoutCancellationException` by type — rejected.** Fixes (1) for timeouts only; still calls a
  cancelled `Deferred` awaited by an active coroutine a cancellation, and leaves (2) untouched. The `isActive`
  test is the general rule the type test approximates.
- **Keep recording cancellations, let adapters filter — rejected.** Under an `ERROR` trace an adapter cannot tell
  the cancelled sibling's record from a real failure without re-deriving this predicate, and it has no coroutine
  context to do so.
- **A `SpanStatus.CANCELLED` — deferred.** Would let a render show cancelled nodes distinctly, but widens a public
  enum (a source break for exhaustive `when`) for a display concern; out of scope here.

## Consequences

- Timeouts inside a traced operation surface as `ERROR` with the `TimeoutCancellationException` as birthplace —
  visible to failure sinks. A timeout the *caller* wraps around a top-level span
  (`withTimeout(…) { span("load") { … } }`) cancels the span's coroutine, so that trace is `CANCELLED` with no
  exception record. That follows from the predicate, and the `TraceStatus` KDoc documents it: a failure sink
  that must see such timeouts keeps `CANCELLED` traces or moves the timeout inside the span. **Behavior change:** a consumer that relied on "any escaping `CancellationException`
  is `CANCELLED`" — e.g. throwing `CancellationException` itself to abort — now gets `ERROR` unless its coroutine
  is actually cancelled.
- A cancellation records no `ExceptionRecord`: neither the cancelled spans of a `CANCELLED` trace nor the cancelled
  siblings of a failure produce one, and a live adapter no longer receives a `JobCancellationException` line per
  cancelled span. A `CANCELLED` report can still carry a failure a nested span recorded before the cancellation
  arrived (see the amendments above).
- The rethrow contract is unchanged: every throwable, cancellation or not, is rethrown as-is.
- **Only a `CancellationException` qualifies.** A sibling interrupted by the cancellation that surfaces it as
  another type (`IOException("Canceled")`, `ClosedByInterruptException` from `runInterruptible`) is still
  recorded as a birthplace. Skipping *every* throwable once `!isActive` was considered and rejected: it would
  also hide a genuine crash raised while the coroutine was being cancelled (a failing cleanup). The `span` KDoc
  tells callers to rethrow such exceptions as a `CancellationException` when they should read as a
  cancellation.
