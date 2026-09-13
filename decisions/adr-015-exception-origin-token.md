# ADR-015 — Birthplace dedup by a stable exception origin token, not "deepest throwable-bearing span"

- **Date:** 2026-09-13
- **Status:** Proposed
- **Affects:** `isBirthplaceAmong` and the report walk (`Report.kt`), the exception verb (`event/TraceException.kt`
  `addException`), and possibly `renderTree` (shares `isBirthplaceAmong`). A **behavioral change** to which
  exception records a report contains in the recover-and-rethrow-different case; source-compatible. Fixes
  **[B01](../BACKLOG.md)**.
- **Builds on:** [ADR-005](adr-005-birthplace-requires-throwable-drop-helper.md) (birthplace = deepest
  throwable-bearing span), [ADR-012](adr-012-reporttrace-attached-orphan-failures.md) (orphan failures ride
  past the dedup deliberately), [ADR-013](adr-013-auto-root-span.md) / [ADR-014](adr-014-adapter-fault-isolation.md)
  (a span rethrows unchanged; each enclosing `span` re-records the climbing throwable).

## Context

ADR-005 defines a span as a **birthplace** iff it carries a throwable and no span in its subtree does — the
deepest throwable-bearing span on a branch. The implementation
([`Report.kt`](../src/main/kotlin/dev/kotrace/Report.kt) `isBirthplaceAmong`) tests exactly that: `exception != null`
and `all.none { it has an exception && descendsFromThis(it) }`.

This is correct for the **single climbing throwable**: a leaf throws, and each enclosing `span` catches the
**same** object, re-records it (`addException`), and rethrows ([`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt)
catch). The deepest span is the origin; the ancestors' copies are dedup'd out, so the report shows one crash
record at the leaf.

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
actively misleading: it shows the handled failure and omits the real one.

The root cause: "any descendant has any exception" is a **proxy** for "this throwable already appears deeper."
The proxy holds only while there is one throwable per branch. It cannot distinguish a re-recorded climb from
two unrelated failures.

**Why not just dedup by throwable identity (`===`).** That is what the structural heuristic exists to avoid:
kotlinx.coroutines *stacktrace recovery* copies a throwable across each `withContext` boundary
([`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt) catch comment), so the "same" climbing throwable is a
**different object** at each enclosing span when recovery is on (`-ea` / debug). Identity dedup would then fail
to collapse the climb and report the throwable N times. The dedup key must survive that copy.

## Decision (proposed)

**Stamp each recorded throwable with a stable origin token, and dedup birthplace by token, not by
"descendant has any exception."**

- **Token:** a value that identifies "this failure", stable across a stacktrace-recovery copy. Candidate
  carriers, to be validated against what coroutine recovery preserves:
  1. A suppressed marker throwable carrying an id (recovery copies `suppressed`? — **verify**);
  2. The `cause` chain (recovery sets the original as the copy's cause — walk to the deepest cause as the
     identity — **verify** it does so for the shapes kotrace sees);
  3. An external `IdentityHashMap`/`WeakHashMap<Throwable, Token>` keyed by object — robust to field copying
     but must key the **pre-copy** object, so it is written in `addException` before the rethrow and read on
     the climb; needs care because the climbing object is a *copy* (so a map keyed by the copy misses).

  The chosen carrier is whichever demonstrably survives recovery in a test (see Test matrix). If none does
  cheaply, fall back to a **best-effort structural + cause-chain** key: `(deepest cause class, deepest cause
  message)` — imperfect, but strictly better than the current proxy for the recover-and-rethrow case.
- **`addException`** stamps the token when a throwable is first recorded, and reuses the existing token when a
  throwable it has already seen climbs through an enclosing span.
- **Birthplace** becomes: a span is the birthplace **of its recorded throwable's token** iff no descendant
  recorded an exception **with the same token**. Two unrelated failures (`A`, `B`) have different tokens, so
  both `child` (token A) and `root` (token B) are birthplaces and both are reported; a single climbing
  throwable shares one token, so only the deepest span reports it — the ADR-005 behavior, preserved.

## Options considered

- **Origin token, deduped per lineage (this ADR).** Correct for both cases; the only cost is stamping/reading a
  token. Risk is entirely in "what survives coroutine recovery", quarantined behind a test.
- **Keep the structural heuristic (status quo) — rejected.** Silently drops the escaping throwable in a real,
  common pattern (catch-and-rethrow-domain-exception).
- **Report every exception-bearing span, no dedup — rejected.** Reintroduces the N-copies-of-one-climb noise
  ADR-005 removed; the report becomes unreadable on deep trees.
- **Identity (`===`) dedup — rejected.** Broken by stacktrace-recovery copies (see Context).
- **Minimal guard: always report the span's own throwable even if a descendant has one — considered.** Never
  drops `B`, but re-introduces duplicate climb records; acceptable as an interim if the token work is deferred,
  but it trades one wrong behavior for a noisier one. Not chosen as the target; noted as a possible stopgap.

## Consequences

- **The report stops hiding the escaping failure** in recover-and-rethrow-different flows. B01 closed.
- **The exception model gains a token** on recorded throwables — a new internal concept `addException` and the
  report walk share. Public API is unchanged.
- **Correctness is contingent on coroutine-recovery behavior**, which is version-dependent; the test matrix
  must pin it, and the fallback structural+cause key bounds the worst case.
- **`renderTree` inherits the fix** (shares `isBirthplaceAmong`), so the human read agrees with the report.

## Test matrix

Single climbing throwable across N `withContext` boundaries → **one** crash record at the deepest span, with
recovery **on** (`-ea`) and **off**. Recover-a-child-and-throw-different → **both** failures reported, each at
its own span. Two sibling branches each failing → two birthplaces. `attached` orphan failures still ride past
the dedup (ADR-012). A throwable whose token carrier does not survive recovery → falls back to the
structural+cause key and still does not drop the escaping throwable. `renderTree` marks the same birthplaces as
the report.

---

[← All decisions](../DECISIONS.md)
