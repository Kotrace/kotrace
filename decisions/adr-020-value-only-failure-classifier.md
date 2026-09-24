# ADR-020 — Generalize returned-failure detection into a classifier; value-only failures produce status without synthetic records

- **Date:** 2026-09-23
- **Status:** Accepted
- **Affects:** replaces ADR-018's `FailureDetector = (Any?) -> Throwable?` with
  `FailureClassifier = (Any?) -> ReturnedFailure?`; renames the process-wide and per-call parameters from
  `failureDetector` to `failureClassifier`; adds the closed public result type
  `ReturnedFailure { ValueOnly, CausedBy(Throwable) }`; renames `FaultPhase.FAILURE_DETECTOR` to
  `FAILURE_CLASSIFIER`. `ValueOnly` marks a normally returning span `ERROR` and supplies the auto-root's default
  `TraceStatus.ERROR`, but emits no `SpanEvent` or `TraceRecord`. `CausedBy` preserves ADR-018's throwable
  recording, lineage dedup, cancellation, and verdict behavior.
- **Amends:** [ADR-018](adr-018-ambient-failure-detector.md) — its ambient/per-call placement, fault isolation,
  root-verdict precedence, recovery, and stable-throwable contract stand; only the callback's result shape and
  terminology widen. Also amends [ADR-019](adr-019-exception-origin-marking.md): origin marking still applies
  to `CausedBy`, while `ValueOnly` has no exception record to classify.
- **Related:** backlog D01 (span-granular export) and D03 (no machine-readable domain-failure reason).

## Context

ADR-018 models a returned failure as `Throwable?`: `null` means success; a non-null throwable means failure,
marks the span `ERROR`, and becomes its exception lineage. This is exact for Kotlin `Result.failure(e)`, but it
cannot represent a typed domain failure with no throwable, such as `Either.Left(DomainError)`, Arrow `Raise`,
or Camailux's `Result.Failure(LoginError.AlreadyExists(id))`.

The concrete consumer workaround exposes the missing state: after a suspend-owned `span {}` returns a typed
failure, it calls `currentSpan()?.end(ERROR)` inside that span's block. That reaches the desired status but uses
the non-suspend bridge's completion verb, publishing `endNanos` before the suspend block has actually finished.
The later `finally` overwrites the timestamp, but a concurrent reader can observe a closed span while it is
still running. The failure classifier — the owner that already inspects every normal return — is the narrow
layer that should mark status.

The missing throwable does **not** justify synthesizing one: a fake exception pollutes crash grouping and has
no stable lineage. Nor does it justify an automatic `LogEvent`: severity and routing belong to the consumer,
an ambient classifier would emit one breadcrumb per returning span, and a report adapter may route logs into a
crash reporter. Status classification and domain-reason emission are separate concerns.

## Decision

**Replace the throwable-only detector with one ambient returned-failure classifier whose non-null result always
marks the span `ERROR`; only the throwable-backed variant emits an exception event.**

```kotlin
sealed interface ReturnedFailure {
    data object ValueOnly : ReturnedFailure
    data class CausedBy(val throwable: Throwable) : ReturnedFailure
}

typealias FailureClassifier = (Any?) -> ReturnedFailure?
```

The classifier remains process-wide on `Kotrace`, with a typed per-call override on `span`:

```kotlin
Kotrace.install(
    adapters = adapters,
    failureClassifier = { value ->
        (value as? Result.Failure<*>)?.let { failure ->
            (failure.error as? Throwable)
                ?.let(ReturnedFailure::CausedBy)
                ?: ReturnedFailure.ValueOnly
        }
    },
)
```

For every span's normal return:

| Classifier result | Span status | Event/record |
|---|---|---|
| `null` | unchanged (`OK`) | none |
| `ValueOnly` | `ERROR` | none |
| `CausedBy(t)` | `ERROR` | canonical-lineage `ExceptionEvent(t)` |

The auto-root verdict keeps ADR-018's precedence, widened to both failure variants:

1. escaping throwable → core-owned `CANCELLED` / `ERROR`;
2. explicit `returnedOutcome` → its result wins;
3. root `CausedBy(CancellationException)` → `CANCELLED`;
4. root `ValueOnly` or any other `CausedBy` → `ERROR`;
5. otherwise → `OK`.

An explicit `returnedOutcome` changes only the trace verdict; classification still marks the span. Recovery is
unchanged: a child returning `ValueOnly` is `ERROR`, but an ancestor that converts it to success returns `null`
from the classifier, stays `OK`, and lets an auto-root report `OK`.

### Machine-readable boundary

`ValueOnly` is intentionally status-only. It creates no `LogEvent`, `ExceptionEvent`, or new record kind, so a
report containing no other events reaches an adapter as `onReport(ERROR, emptySequence())`. This preserves the
consumer's distinction between “operation failed” and “crash-worthy object exists,” and keeps expected business
rejections such as `LoginError.AlreadyExists` out of crash telemetry.

Two limitations remain explicit rather than being smuggled into this change:

- Until D01 is repaid, adapters do not receive eventless spans, their status, timing, or identity. An empty
  report therefore carries no `trace_id` or operation in its record sequence.
- A safe symbolic reason such as `login.already_exists` is not emitted automatically. Backlog D03 retains this
  machine-readable domain-reason gap; its trigger is a consumer that needs to search, count, or route such
  failures automatically.

### Fault isolation and non-suspend bridge

A non-fatal classifier throw — including a thrown `CancellationException` — remains contained as “no
classification” and is routed to `AdapterFaultHook` with `FaultPhase.FAILURE_CLASSIFIER`, `adapter = null`.
A fatal JVM error is rethrown. `startSpan`/`Span.end` remain unchanged: they wrap no returned value and therefore
have nothing to classify.

## Compatibility and migration

This is a source and binary break relative to the in-development ADR-018 API: callback return type, parameter
names, typealias, enum constant, and JVM signatures change. ADR-018 has not shipped in a tagged release; it is
already queued with ADR-017 for 0.5.0, so replace it before that release rather than carrying two overlapping
callbacks and a precedence rule between them.

Migration:

```kotlin
// Before
failureDetector = { value -> (value as? Result<*>)?.exceptionOrNull() }

// After
failureClassifier = { value ->
    (value as? Result<*>)?.exceptionOrNull()?.let(ReturnedFailure::CausedBy)
}
```

A typed result adds the `ValueOnly` branch instead of calling `Span.end(ERROR)` or synthesizing a throwable.

## Consequences

- Value-only failures become first-class span outcomes without becoming fake crashes.
- The ambient climb and recovery semantics are uniform across throwable-backed and value-only failures.
- Throwable behavior remains unchanged modulo the explicit `CausedBy` wrapper.
- No new event kind, wire field, adapter callback, policy gate, or dependency is added.
- The residual machine-readable reason is documented in D03; span-oriented export remains D01.

## Test matrix

- A root `ValueOnly` return marks the trace `ERROR`, returns the application value unchanged, and reports zero
  records.
- Nested spans returning the same value-only failure are all `ERROR`; they finish through the normal `finally`
  and contain no events.
- Explicit `returnedOutcome = OK` wins the trace verdict while the classified root remains `ERROR`.
- A value-only child recovered into success leaves the root/trace `OK`; the child remains `ERROR`.
- Every ADR-018 throwable-backed case passes after wrapping the stable throwable in `CausedBy`: birthplace
  dedup, semantic re-wrap, sibling branches, returned cancellation, local opt-out, strict mode, and classifier
  fault isolation.

---

[← All decisions](../DECISIONS.md)
