# ADR-040: A report stamps an `addException` record `EXPLICIT`, behind a per-adapter gate

- **Date:** 2026-10-29
- **Status:** Accepted (implemented in 0.6.0)
- **Amended:** 2026-11-07 by [ADR-045](adr-045-attach-exception-from-any-span.md): an `attached` orphan no longer
  keeps its `BIRTHPLACE` stamp; it is `ATTACHED`, like a failure registered with `attachException`.
- **Affects:**
  - `ExceptionOrigin` gains `EXPLICIT`;
  - `ExceptionEvent` gains an internal marker set only by `Span.addException`;
  - `TracePolicy` gains `val acceptsExplicitException: Boolean get() = true`, applied in the report view beside
    `acceptsPropagatedException` (ADR-019).

  **Compatibility:**
  - **Source:** an exhaustive `when` over `ExceptionOrigin` stops compiling.
  - **Runtime:** a consumer compiled against 0.5.0 with such a `when` throws `NoWhenBranchMatchedException` on its
    first `EXPLICIT` record.
  - **Behaviour:** an adapter testing `origin == BIRTHPLACE` stops matching explicit records. The default gate keeps
    them in every report, but under the new label, and `toJson` changes shape for them.

  Every adapter that reads `origin` must migrate. Targets 0.6.0, unpublished, with the `api/kotrace.api` dump and the
  README upgrade notes updated.
- **Amends:** [ADR-019](adr-019-exception-origin-marking.md) (a third origin).

## Context

A throwable reaches a report in two ways:
- **A climb**, a thrown or returned failure. `recordPropagatedException` records it with its lineage key, so the
  report stamps it `BIRTHPLACE` or `PROPAGATED`.
- **A consumer's `addException`.** It gets a fresh key (ADR-015), so it is never deduped, and the walk stamps it
  `BIRTHPLACE` too.

A consumer often routes the two by moment: an explicit exception live, the moment it is recorded, with its
attributes; a birthplace through the report, only if the trace fails. An `addException` inside a trace that ends
`ERROR` then reaches such a crash sink twice:
- live, with its attributes;
- in the report, labelled `BIRTHPLACE` and without them, since an `ExceptionEvent` holds no attributes.

The adapter cannot tell this record from a real birthplace.

## Decision

- **The report names the source.** An event from `addException` is stamped `EXPLICIT`, never `BIRTHPLACE` or
  `PROPAGATED`. These keep their current stamps:
  - every climb: a thrown failure, a classified returned failure (ADR-018/020), and the `startSpan` bridge's
    `end(error)`;
  - an `attached` orphan (ADR-012).

  A live record stays unclassified (`origin == null`). `renderTree` reads the span, not the report, and is unchanged.
- **The adapter chooses, as for `PROPAGATED`.** `acceptsExplicitException` drops `EXPLICIT` records before
  `acceptsEvent` runs. It defaults to `true`, so an existing adapter's report keeps them. A crash sink that already
  records explicit exceptions live sets it to `false`.
- **Setting it to `false` is a claim the adapter makes about itself:** "I took this exception live." kotrace stores
  every event before fan-out (ADR-002). It cannot know whether an adapter received the live record: a policy may
  have rejected it, reentrancy may have skipped it (ADR-025), or a fault may have been contained (ADR-014). An
  adapter that drops some explicit exceptions live, for example by a marker, must leave the gate `true` or accept
  losing those records.
- **Dedup is unchanged.** An explicit event keeps its fresh lineage key: it neither shadows nor is shadowed by a
  climb.

## Options considered

- **`addException(…, reportable = false)` at the call site: rejected.** It removes the record from every adapter's
  report, so a trace backend loses it from the tree too. Whether a sink wants a record is the sink's call, as
  ADR-019 settled for `PROPAGATED`.
- **`addException` goes live only: rejected.** It breaks the idiom of recording a handled exception so that it
  reaches the report, which ADR-015 relies on ("all such events report").
- **Gate defaults to `false`: rejected.** It silently changes every existing adapter's report.
- **A boolean on `ExceptionRecord` instead of an origin: rejected.** It needs two fields to express one
  classification, and the record already classifies by `origin`.

## Consequences

- A crash adapter routing by moment stops double-recording an explicit exception with one policy line, and no
  call site changes.
- It does not deduplicate one throwable recorded explicitly **and** escaping as a climb. These are two records with
  two lineage keys, and both report. Engine-side matching was considered and rejected
  ([ADR-041](adr-041-birthplace-already-explicit.md)), so the consumer must not do both.
- A trace backend keeps the explicit exception in the tree, labelled for what it is.
- `toJson` writes `"exception_origin":"explicit"` for it, as it writes `propagated` today. A birthplace stays
  unwritten.
