# ADR-015 — Birthplace dedup by a stable, fail-open exception lineage key, not "deepest throwable-bearing span"

- **Date:** 2026-09-13
- **Status:** Accepted
- **Affects:** `isBirthplaceAmong` and the report walk (`Report.kt`), the exception verb (`event/TraceException.kt`
  `addException`, `ExceptionEvent`), and `renderTree` (`TraceFormat.kt`, shares birthplace selection). A
  **behavioral change** to which exception records a report contains: (1) the recover-and-rethrow-different case
  (B01); and (2) explicitly recorded ancestor/descendant exception events that the structural heuristic used to
  shadow — because consumer `addException` calls now get fresh keys, all such events report. Source-compatible.
  Fixes **[B01](../BACKLOG.md)**.
- **Builds on:** [ADR-005](adr-005-birthplace-requires-throwable-drop-helper.md) (birthplace = deepest
  throwable-bearing span), [ADR-012](adr-012-reporttrace-attached-orphan-failures.md) (orphan failures ride
  past the dedup deliberately), [ADR-013](adr-013-auto-root-span.md) / [ADR-014](adr-014-adapter-fault-isolation.md)
  (a span rethrows unchanged; each enclosing `span` re-records the climbing throwable; the auto-root knows the
  throwable that actually escaped).

## Context

ADR-005 defines a span as a **birthplace** iff it carries a throwable and no span in its subtree does — the
deepest throwable-bearing span on a branch. The implementation
([`Report.kt`](../src/main/kotlin/dev/kotrace/Report.kt) `isBirthplaceAmong`) tests exactly that: `exception != null`
and `all.none { it has an exception && descendsFromThis(it) }`.

This is correct for the **single climbing throwable**: a leaf throws, and each enclosing `span` catches the
**same logical failure** (the same object, or — with stacktrace recovery on — a copy of it), re-records it
(`addException`), and rethrows ([`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt) catch). The deepest span
is the origin; the ancestors' copies are dedup'd out, so the report shows one crash record at the leaf.

It is **wrong** when a span **recovers** a child failure and throws a **different** one:

```kotlin
span("root") {                          // root records B  (escapes the trace)
    runCatching { span("child") { throw A } }   // child records A (recovered, handled)
    throw B
}
```

Both `child` and `root` carry an exception. `isBirthplaceAmong(root)` sees an exception-bearing descendant
(`child`) and returns **false**, so `B` — the throwable that actually escaped the trace — is **dropped** from
the report, while `A` — recovered, never seen by the caller — is reported as the birthplace. The report is
actively misleading: it shows the handled failure and omits the real one. This is confirmed against the code:
the child catch records `A` and the root records `B` through the same catch path ([`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt)),
`isBirthplaceAmong(root)` rejects the root merely because a descendant has *any* exception, and the walk then
drops every root exception event.

The root cause: "any descendant has any exception" is a **proxy** for "this throwable already appears deeper."
The proxy holds only while there is one throwable per branch. It cannot distinguish a re-recorded climb from
two unrelated failures.

**Why not just dedup by throwable identity (`===`).** That is what the structural heuristic exists to avoid:
The kotlinx.coroutines *stacktrace recovery* copies a throwable across each `withContext` boundary
([`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt) catch comment), so the "same" climbing throwable is a
**different object** at each enclosing span when recovery is on (`-ea` / debug). Identity dedup would then fail
to collapse the climb and report the throwable N times. The dedup key must survive that copy.

**Why not walk the whole cause chain either.** Recovery is *not* the only reason a throwable has a cause. The
motivating pattern itself — `throw B(cause = caughtA)` — deliberately keeps the recovered `A` as `B`'s cause.
Any dedup that keys on the *deepest cause* would compute `A` for both `child` and `root`, conclude they are the
same lineage, and drop `B` again — reproducing B01. The lineage key must therefore only ever collapse edges
that are **provably** coroutine-recovery copies, never arbitrary semantic causes.

## Decision

**Give each recorded failure a stable lineage key, dedup birthplace per `ExceptionEvent` by that key, and fail
open: when lineage cannot be proven, keep the record.** Concretely:

### Where the key lives

- The key is **internal metadata on `ExceptionEvent`**, not a mutation of the application `Throwable`. The raw
  throwable is carried untouched for crash tooling ([`TraceException.kt`](../src/main/kotlin/dev/kotrace/event/TraceException.kt)),
  and kotrace's principle is that instrumentation *observes*, never alters, the user's object. This rules out
  the "suppressed marker throwable" carrier from the earlier draft: a suppressed exception is visible to callers
  and crash reporters, collides with the strict-mode suppressed-failure semantics already in
  [`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt), and does not exist for throwables constructed with
  suppression disabled.
- **Two recording paths, not one overloaded verb.** The lineage key is assigned by *who records*, because the
  public verb cannot tell whether a caller is kotrace propagating a climb or a consumer recording their own
  failure:
  - **Internal propagation recorder** — the path the `span` catch and `Span.end(error)` use to re-record a
    climbing throwable ([`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt)) — computes the **canonical
    lineage key** (below), so a climb collapses.
  - **Public `Span.addException` / `ExceptionEvent(...)`** — a consumer recording a failure directly — gets a
    **fresh distinct key** each call. Consumer-recorded events are their own lineage and are never dedup'd away.
- Public-API impact: the existing public two-argument `ExceptionEvent` constructor keeps its exact JVM
  descriptor. The key must **not** be added as a defaulted parameter to that primary constructor — a default
  still changes the constructor's descriptor. Carry it as a **body `var`/property** set only by the internal
  recorder (via an internal factory or secondary path), or make the expanded constructor private and keep the
  original public signature as a secondary constructor. Either way, no ABI change to the public constructor.

### How the key is computed (canonicalize, don't chase causes)

The internal recorder maps the throwable it observes at a boundary to a **canonical object**, and that object's
**identity** is the lineage key. The mapping is a single local traversal — no comparison against "anything
deeper" is needed:

1. Start at the observed throwable.
2. While the current object is a **recognized coroutine stacktrace-recovery wrapper**, step to its `cause`. A
   wrapper is recognized by **three** conjuncts, biased to false negatives (a missed copy costs a duplicate
   record; a false positive would drop the escaping failure — the B01 direction):
   1. **its own** stack trace carries a coroutine **boundary** artificial frame, which kotlinx.coroutines 1.11.0
      splices in as the exact class name `_COROUTINE._BOUNDARY` when a copy crosses a `withContext` (older versions a
      `(Coroutine boundary)` class name). Match the boundary frame *specifically*, **not** `_COROUTINE._CREATION`
      (the debug creation-stack frame, which appears on ordinary exceptions built inside a coroutine) nor any
      other `_COROUTINE*` frame; the wrapper carries the boundary frame, the original underneath does not;
   2. its direct `cause` is of the **same runtime class** — recovery copies the class;
   3. it carries the **same message** as that cause — recovery copies the message.
   Conjunct 3 stops a *same-class semantic* wrap — `throw Foo(newMessage, cause = A)` — from being mistaken for a
   recovery copy and collapsing the escaping failure into `A`. (A different-class wrap is already excluded by
   conjunct 2.) It is strict in the *safe* direction: a copy made through a cause-only constructor may carry the
   cause's `toString()` rather than its `message` and so fail conjunct 3 — that costs only a duplicate record
   (fail open), never a dropped failure. These conjuncts are strong heuristics, **not proof**: `stackTrace` is
   publicly mutable, so a boundary frame can be forged; that residual is unavoidable and accepted.
3. Stop at the first object that is not a recognized wrapper. **Its identity is the lineage key.**
4. If the traversal cannot proceed safely — a cycle (identity-based detection), the depth bound is hit, or a
   **hostile `cause`/`message`/`stackTrace` accessor throws** (all overridable; the key derivation swallows a
   non-fatal throw, rethrows only a JVM-fatal one) — stop where you are and use the current object's identity.
   Key derivation is therefore **total**: it never propagates out of the recorder, which matters because
   `end(error)` records on the failure path with no strict-mode guard around it.

Why this collapses the right things and nothing else:

- **Single climb, recovery off (or non-copyable throwable):** the *same instance* is observed at every boundary;
  step 2 never fires, and every boundary yields that one identity → one lineage. Exotic throwables that climb as
  the same instance collapse here too — the deciding factor is *same instance*, not the class.
- **Single climb, recovery on:** each boundary observes a fresh wrapper whose recovery chain leads back to the
  *same* original; every boundary canonicalizes to that original's identity → one lineage.
- **Fail open:** anything not *provably* a recovery wrapper is left as its own identity, so unrelated failures —
  and `throw B(cause = A)`, where the `A` cause is a *semantic* cause, not a recovery wrapper of `B` — keep
  distinct keys. Duplicate records are the worst case; the escaping failure is never dropped.

The earlier draft's `(deepest cause class, deepest cause message)` **fallback is rejected**: it collides for two
distinct failures with the same class/message, for null messages, for recursion throwing the same type, for
same-class domain translation, and for localized messages — silently dropping the escaping throwable in each.
Leaving the object as its own identity (fail open) replaces it.

### How the walk uses the key

- Birthplace is decided **per `ExceptionEvent`**, not per span. `addException` is public and a span can hold
  **multiple** events (record `A`, then escape with `B`); `Span.exception` exposes only the first, so a single
  per-span boolean is undefined. An event is a birthplace of its lineage iff **no descendant event carries the
  same lineage key**.
- Selection stays **ancestry-scoped**, exactly as today (`descendsFromThis`). It is *not* a global "seen key"
  set: two sibling branches, and ADR-012 `attached` orphan failures, must each keep their own birthplace even
  if their keys happened to coincide. Attached failures continue to bypass the walk entirely (ADR-012), after
  it runs.
- **No terminal-failure pin is needed — the walk already preserves the escaping failure.** For any lineage key
  that is *present* in the tree, the deepest event carrying it has, by definition, no same-key descendant, so
  the per-event walk always selects it. The escaping throwable's lineage is present whenever it was recorded at
  all (each enclosing `span` re-records the climb — ADR-013/014), so its deepest event is always selected; there
  is nothing for a pin to add. An earlier draft proposed pinning the auto-root's escaping throwable
  unconditionally, but that would either be a no-op (the lineage is already selected) or, if it re-selected the
  root's re-recorded copy, duplicate every ordinary climb — so it is dropped. Fail-open (above) is what bounds
  the worst case: an *unrecognized* copy simply keeps its own key and is reported, never dropped. The only way an
  escaping failure is absent from the report is if recording it failed outright, which a pin cannot fix either.
- **`renderTree` policy.** `renderTree` moves to the same per-event selection as the report and renders **every
  selected event** on a span, each as its own `error:` line, in **record (timestamp) order**; today it renders
  only `span.exception` — the first event — ([`TraceFormat.kt`](../src/main/kotlin/dev/kotrace/TraceFormat.kt)),
  which is why a span with two distinct failures currently loses one. This keeps the human read in agreement
  with the report.

## Options considered

- **Fail-open per-event lineage key, canonicalized from proven recovery edges (this ADR).** Correct for both
  the single climb and recover-and-rethrow-different; never drops the escaping failure. Cost is deriving/reading
  a key. Risk is confined to "which cause edges are recovery copies," quarantined behind the test matrix, and
  bounded by fail-open.
- **Deepest-cause identity or `(class, message)` fallback — rejected.** Reproduces B01 for `throw B(cause = A)`
  and collides on identical class/message; see Context and Decision.
- **Suppressed marker throwable — rejected.** Mutates the user's object, leaks into crash reporters, collides
  with strict-mode suppression, and breaks under suppression-disabled throwables.
- **Global `IdentityHashMap`/`WeakHashMap<Throwable, Key>` — rejected.** Internally contradictory: the climbing
  object is a *copy*, so a map keyed by the pre-copy object misses on the next boundary. `IdentityHashMap` also
  holds strong references (leaks throwables) and `WeakHashMap` is keyed by equality, not identity; they are not
  interchangeable, and neither is thread-safe for the parallel-children case. The canonical object serves as the
  key without an external map.
- **Keep the structural heuristic (status quo) — rejected.** Silently drops the escaping throwable in a real,
  common pattern (catch-and-rethrow-domain-exception).
- **Report every exception-bearing span, no dedup — rejected.** Reintroduces the N-copies-of-one-climb noise
  ADR-005 removed; the report becomes unreadable on deep trees. (Note: fail-open degrades *toward* this only for
  the unresolvable cases, not globally.)
- **Minimal guard: always report the span's own throwable even if a descendant has one — considered.** Never
  drops `B`, but re-introduces duplicate climb records unconditionally; acceptable as an interim stopgap if the
  key work is deferred, but noisier than the target. Not chosen.

## Consequences

- **The report stops hiding the escaping failure** in recover-and-rethrow-different flows. B01 closed.
- **`ExceptionEvent` gains an internal lineage key**; `addException` and the report walk share it. The existing
  public constructor and API are unchanged; externally constructed events get a distinct key.
- **Birthplace is now per-event, not per-span.** The indexed walk and `renderTree` both test each
  `ExceptionEvent`; `renderTree` renders every selected event in record order (see policy above).
- **Correctness of the *dedup* is contingent on recognizing coroutine-recovery edges**, which is version- and
  `-ea`-dependent; the test matrix pins it. But because the design **fails open** — an unrecognized copy keeps
  its own key and is reported — the worst case is duplicate records, never a dropped escaping failure. No
  terminal pin is required: the walk already selects the deepest event of every lineage present in the tree.
- **No new observable failure modes.** Key derivation runs on data already held; note `Span.end(error)` calls
  `addException` without the non-fatal protection that suspend `span` has ([`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt)),
  so key lookup must be total (no throw) on any throwable shape.
- **ADR-012 preserved:** attached orphan failures still ride past the dedup; selection remains ancestry-scoped,
  not a global key set.
- **Object reuse is one lineage — a documented limitation.** Because the key is the canonical object's identity,
  the *same* throwable instance rethrown as a genuinely independent later failure in the same ancestry
  canonicalizes to one key and collapses to a single record. This is judged acceptable: reusing a throwable
  instance for two semantically distinct failures is a discouraged pattern, and introducing a per-record
  occurrence token to split them would add cost for a case kotrace should not encourage. The behavior is fixed
  by an explicit test.

## Test matrix

- **Single climb, recovery on and off.** One throwable across N (≥3) genuinely suspending `withContext`
  boundaries → **one** crash record at the deepest span, with recovery **on** (`-ea`) and **off**, in separate
  JVM configurations. Assert the caught objects actually **differ** across boundaries before asserting the dedup
  (otherwise the test proves nothing about recovery).
- **Recover-and-rethrow-different (B01).** `runCatching { span("child") { throw A } }; throw B` → **both**
  reported, each at its own span.
- **Wrap-and-rethrow (`throw B(cause = A)`).** After recovering child `A`, throw `B` with `A` as its cause →
  **both** reported. Guards against the deepest-cause regression.
- **Key collisions.** Distinct ancestor/child failures with identical class **and** message; with **null**
  messages; recursion throwing the same type/message at multiple depths → each distinct failure keeps its own
  record (fail-open), none dropped.
- **Exotic throwables, same instance climbing.** Custom non-copyable throwable, a `CopyableThrowable`, and a
  suppression-disabled throwable climbing as the **same** instance → collapse to **one** record (same identity
  at every boundary; the traversal never steps), not a distinct key; key lookup never throws.
- **Unrecognized copy.** A different instance with no recognized recovery wrapper relationship → keeps its own
  identity as key, escaping throwable reported, never dropped (fail open).
- **Cause cycle and very deep cause chain** → walk terminates (cycle detection + depth bound).
- **Multiple events on one span.** Span records `A` then escapes with `B`; also `startSpan(...).end(ERROR, error)`
  followed by a different escaping ancestor failure → per-event birthplace is correct.
- **Parallel children recording concurrently** → no data race / no lost or corrupted key.
- **Object reuse.** The same throwable object reused for a later independent throw in the same ancestry →
  asserted to collapse to **one** record (the documented one-lineage limitation).
- **ADR-012 attached.** Attached list containing the same object as a tree failure, and the same object twice →
  every attached entry survives.
- **ADR-005 regression.** Throwable-less bridge/child span still not a birthplace.
- **Escaping failure always reported (no pin needed).** In every recovery-on/off and exotic-throwable case
  above, assert the escaping throwable's deepest event is selected by the walk itself — the design must never
  rely on a terminal pin to reinstate it.
- **`renderTree`** renders every selected event per span in record order, matching the report — including a span
  with two distinct failures (both `error:` lines present) and a single climb (one line).

---

[← All decisions](../DECISIONS.md)
