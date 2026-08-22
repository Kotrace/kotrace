# ADR-006 — `Span` terminal state via safe publication of an immutable pair

- **Date:** 2026-08-22
- **Status:** Accepted (implemented — `status` / `endNanos` read off one `AtomicReference<State>`)
- **Affects:** `dev.kotrace.Span` (`status` / `endNanos` shape), `dev.kotrace.Trace` (`span` catch/finally,
  `Span.end`), backlog **D1**
- **Plan:** [plan-005](../plans/plan-005-span-terminal-state-thread-safety.md)

## Context

`Span.status` and `Span.endNanos` were plain `var`s, written by the completing thread (coroutine catch /
finally, the throwable climb, or an off-coroutine bridge `Span.end`) and read later by `reportTrace` /
`renderTree` on whatever thread runs the report. The class already treats itself as cross-thread-shared —
`events` is a `CopyOnWriteArrayList`, `info` a `ConcurrentHashMap` — so these two plain `var`s were the
inconsistent holdouts.

It was safe *today* only because structured-concurrency **join** at the report boundary is a happens-before
edge: the report runs after every span has joined, so the writes are visible. But that guarantee was
unenforced by the type and undocumented as the reason, and load-bearing on "report always runs after join."
A future reader off an unsynchronized thread — notably a live dashboard reading spans **mid-flight** — would
get a stale read (`status == OK`, `endNanos == null`) or a torn `(status, endNanos)` pair.

The full option analysis is in plan-005 (`@Volatile`; safe-publication snapshot; formalize the join; freeze
to an immutable copy).

## Decision

Adopt **safe publication of an immutable pair** (plan-005 Option B). The two fields become one immutable
`State(status, endNanos)` published through a single `AtomicReference`, with derived read-only getters:

```kotlin
private class State(val status: SpanStatus, val endNanos: Long?)
private val state = AtomicReference(State(SpanStatus.OK, null))
val status: SpanStatus get() = state.get().status
val endNanos: Long?     get() = state.get().endNanos
```

Mutation is through intent-named internal methods: `markStatus` (coroutine catch + climb, preserves
`endNanos`), `markEnd` (coroutine finally, preserves `status`), and `markCompleted` (the bridge `Span.end`,
both fields in **one** atomic publish). The birthplace throwable stays in `events` (copy-on-write), its
single source of truth — it is **not** folded into `State`, which would duplicate an event.

## Consequences

- **Correct independent of the join.** Each read of `state` is a happens-before-published, consistent
  `(status, endNanos)` pair — never torn, never stale — so a pre-join / mid-flight reader is safe without
  relying on "report runs after join." This is the property a future live dashboard needs (plan-005 §Future
  scenario); the join remains the *ordering* guarantee for the report, but is no longer the *only* thing
  keeping a single read correct.
- **Group atomicity where a writer sets both.** `Span.end` publishes `status` + `endNanos` in one write, so
  the bridge path is never observed half-updated. The coroutine two-phase path still publishes twice (status
  in catch, end in finally); each publish is individually consistent, and a reader between them sees a true
  intermediate ("errored, not yet ended"), not a torn read.
- **Breaking API change:** `status` / `endNanos` are now read-only `val` getters; their public setters are
  gone. No consumer set them directly (completion goes through `Span.end`); direct assignment was never the
  supported path. Reads are unchanged.
- **Cost:** an `AtomicReference` read per access and a small immutable `State` allocation per transition —
  a handful per span. Negligible against the trace's own allocations, and in line with the class already
  paying for `events` / `info` thread-safety.
- **Not tested by a race.** A data race is not deterministically unit-testable; `SpanStateTest` covers the
  behaviour (fresh span reads OK/open; `end` publishes both and routes the throwable through `events`).
  Correctness is by construction, not by test — stated plainly rather than implied.

## Rejected alternatives

- **`@Volatile` on both fields (plan-005 Option A).** Fixes visibility but not the torn pair, and makes the
  fields *look* fully thread-safe while the real invariant stays undocumented — inviting a future reader to
  assume group consistency it lacks. A two-line stopgap, not a real fix.
- **Formalize the join as a structural guarantee (Option C).** Cheapest, but correctness still *is* the
  join — it forbids the mid-flight reader by construction, foreclosing the live-dashboard direction. Rejected
  because that direction is plausible and B keeps it open at the same order of cost.
- **Freeze each span to an immutable `CompletedSpan` at trace end.** Cleanest phase separation, but only
  earns its per-span allocation when spans are read mid-flight from multiple threads — no such reader exists
  yet. YAGNI now; it pairs with B (freeze-on-demand) if a coherent-snapshot dashboard lands.
