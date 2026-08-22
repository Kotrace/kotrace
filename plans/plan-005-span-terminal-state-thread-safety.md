# plan-005 — `Span` terminal-state thread-safety (D1)

Working doc for backlog **D1** — `Span.status` / `Span.endNanos` are non-atomic `var`s read cross-thread.
Scratch: delete when landed.

> **Resolved 2026-08-22 — Option B locked and landed.** Safe publication of an immutable `(status,
> endNanos)` pair via one `AtomicReference`; see [ADR-006](../decisions/adr-006-span-terminal-state-safe-publication.md).
> The options below are kept as the decision record.

## Problem

`Span.status` and `Span.endNanos` (`Span.kt:46-47`) are plain `var`. Their terminal value, together with the
birthplace `exception`, forms one logical "completion state" written once when the span finishes and read
later at report.

- **Writers:** the coroutine path (`Trace.kt` catch → `status = ERROR`, finally → `endNanos`), the throwable
  climb (each enclosing `span{}` frame sets its own `status`, possibly on a different dispatch thread), and
  the off-coroutine bridge (`Span.end(status, error)` — OkHttp/Room, on a non-coroutine thread).
- **Reader:** `reportTrace` / `renderTree`, on whatever thread runs the report.

The class already treats itself as cross-thread-shared for its *other* mutable state — `events` is a
`CopyOnWriteArrayList`, `info` a `ConcurrentHashMap` — precisely because bridges write off-coroutine.
`status` / `endNanos` are the two inconsistent holdouts left as plain `var`.

## Why it is safe *today* (and why it is still debt)

Structured-concurrency **join** at the report boundary is a happens-before edge: `reportTrace` runs after
the root block returns, i.e. after every `span{}` has joined, so every terminal write is visible to the
report read without any `@Volatile`. Bridge writes fall under the same umbrella (a blocking `execute()` runs
on the coroutine thread; an async OkHttp/Room callback is joined back through the continuation resume).

So the current design is genuinely correct — **provided** the invariant "report always runs after join"
holds. The debt is that this guarantee is:

- unenforced by the type (both fields are plain `var`),
- undocumented as the *reason* (the `events` KDoc covers concurrent *writes*, not the write→read visibility
  edge for `status` / `endNanos`),
- load-bearing on two implicit assumptions: report runs after join, and every bridge write is joined before
  report.

## The hazard

A future change that reports a span from an **unsynchronized thread** — a mid-flight read, a live-ish
report, a bridge that ends a span on a truly detached thread with no join before the report — gets a
**stale read**: `status` still `OK`, `endNanos` still `null`. Result: wrong birthplace / wrong duration,
silent and hard to reproduce.

Note two distinct sub-hazards, because not every option covers both:
1. **Stale-but-completed** — the write happened, just isn't visible. (`@Volatile` fixes this.)
2. **Torn group** — a reader sees `status = ERROR` with `endNanos = null` because it read between the two
   writes; or reads before `end()` ran at all. (`@Volatile` does **not** fix this.)

No current reader needs `(status, endNanos, exception)` atomic as a group — birthplace reads `exception`
alone, `durMs` reads `endNanos` alone, status is read alone — so torn-group is latent, not live. But an
annotation that makes the fields *look* fully thread-safe can invite a future reader to assume group
consistency it does not have.

## Options (pick one)

### Option A — `@Volatile` both fields (+ mandatory KDoc)

```kotlin
@Volatile var endNanos: Long? = null
@Volatile var status: SpanStatus = SpanStatus.OK
```

- **Fixes:** visibility (sub-hazard 1), independent of the join argument. Consistent with `events` / `info`
  already being thread-safe structures.
- **Does not fix:** atomicity across the group (sub-hazard 2).
- **Downsides:** a real (tiny) barrier cost, heavier on ARM/Android than the x86 "free-read" intuition;
  makes the fields *look* fully handled while the real guarantee (join) stays undocumented — so **A without
  the KDoc is the worst outcome** (cost + false confidence + unstated invariant). Public `@Volatile var`
  also advertises "meant for concurrent reads," subtly inviting the misuse the invariant forbids.
- **Must ship with:** a KDoc stating the real invariant (read after the owning coroutine joins; `@Volatile`
  is defence for a future unsynchronized reader) and a note that the fields are **not** atomic as a group.
- **ADR:** no — a localized hardening, not a contract change.

### Option B — Immutable completion snapshot via one atomic reference

```kotlin
private class Completion(val status: SpanStatus, val endNanos: Long, val exception: Throwable?)
private val completion = AtomicReference<Completion?>(null)
val status: SpanStatus get() = completion.get()?.status ?: SpanStatus.OK
val endNanos: Long?      get() = completion.get()?.endNanos
```

- **Fixes:** visibility **and** group atomicity **and** the lifecycle read (a reader sees `null` = "not
  complete", never a torn trio) — correct for a single read without leaning on join.
- **Downsides:** all terminal writes must funnel through one publish. The two-phase coroutine path
  (`status = ERROR` in catch, `endNanos` in finally) means either two atomic publishes (a reader between
  them sees the older-but-consistent snapshot) or a restructure so completion publishes once at end. Larger
  surface: `exception` currently derives from `events`; folding it into `Completion` changes where it lives.
- **ADR:** likely yes — changes the shape of public fields (vars → derived getters) and introduces a
  concurrency mechanism.

### Option C — Formalize the join as a structural guarantee

Keep the fields plain, but make it **impossible** to report without crossing the join: route the report
behind a single publish (e.g. the collector marks the trace done through a `volatile`/`AtomicReference`
flag whose report-side read pairs with the completing write). Turns the invariant from convention into an
enforced happens-before, without adding per-field concurrency.

- **Fixes:** both sub-hazards *via* the join (same model as today, now enforced).
- **Downsides:** does not make a `Span` self-safe in isolation — correctness still *is* the join, just
  guaranteed. Least code, but the weakest answer to "read a span mid-flight from another thread" (which it
  still forbids by construction — acceptable today, since no such feature exists).
- **ADR:** maybe — documents that report is gated behind trace completion as the sole synchronization point.

### Rejected here (note for the record)

- **Freeze each `Span` to an immutable `CompletedSpan` at trace end (per-field-free reads after a phase
  barrier).** Cleanest phase separation, but only earns its allocation-per-span when spans are read
  **mid-flight from multiple threads** (a live in-flight viewer, a stuck-span watchdog, a sampler) — no such
  reader exists. YAGNI now; revisit if a live-introspection feature lands.

## Future scenario — a live "all users" event dashboard (bears on the choice)

If a live dashboard streaming every user's events lands later, it is the concrete case that turns the
mid-flight cross-thread read from hypothetical into real. What it actually needs, layered — because most of
it is **not** what D1/Option B covers:

1. **Live event stream** (the breadcrumbs as they happen). Already safe, and **unrelated to B**: `events` is
   `CopyOnWriteArrayList` and the live path (`LiveAdapter.onLive`) hands each `TraceRecord` to the adapter on
   the emit thread. A dashboard consumes that stream today, independent of D1.
2. **Span-level terminal read mid-flight** (is it done? final status/duration?). **This is the slice Option B
   covers** — its atomic `completion` gives a consistent `null` = running / full trio = done, read safely
   before any join. This is the one place the dashboard makes B (not A, not C) the right call: C leans on a
   join the dashboard reads *before*, and A leaves the group torn.
3. **Enumerating in-flight traces across users.** kotrace binds one `SpanCollector` per flow in coroutine
   context; there is **no global registry** of live collectors. A dashboard needs one — a new architectural
   piece, orthogonal to D1.
4. **PII.** Streaming every user's events to a dashboard is a **sink**. It must go through the live policy
   gate + `toJson` (class-name-only exceptions), and must **not** use `renderTree` (D4). Live-to-dashboard
   does not get to skip redaction.
5. **Coherent point-in-time span snapshot.** If the dashboard needs a whole span captured at one instant
   (name + attributes + events + completion together), the independently-safe pieces still skew across
   instants. That needs **freeze-on-demand** (the rejected freeze option), paired **with** B.

Bearing on the decision: this scenario is the strongest argument for **B over C** — B keeps a `Span`'s
terminal read correct *independent of the join*, which a dashboard reading pre-join requires; C's guarantee
evaporates the moment a reader does not cross the join. But B is a **foundation, not the whole feature**: the
dashboard also needs a live-collector registry, PII gating, and (for coherent snapshots) freeze-on-demand.
Do **not** scope those into D1 — note them here so the D1 choice does not foreclose them. If a live dashboard
is a likely roadmap item, prefer **B**; if it is not, **C** stays a legitimate cheaper fix.

## Recommendation (not locked)

- If we want each `Span` correct **independent of the join argument** → **Option B**: it targets exactly the
  three fields, is standard safe-publication, costs ~the same as volatile, and closes torn-group too.
- If we accept "report always runs after join" as an architectural commitment → **Option C** is the cheapest
  *real* fix: formalize the guarantee we already rely on, rather than adding a concurrency mechanism the
  happy path never exercises.
- **Option A** only if we want a two-line stopgap now and defer B/C — and only *with* the KDoc, never bare.

Leaning B or C. Decide before implementing.

## Work once an option is chosen

- **Code:** per the option above (`Span.kt`; C also touches `SpanCollector` / `reportTrace`).
- **Docs:** update the `Span` KDoc to state the visibility invariant explicitly (all options need this — it
  is the missing piece the debt names). For B/C, add/point to the relevant ADR.
- **Tests:** a data race is not deterministically unit-testable. Cover instead by:
  - a behavioural test that a bridge-ended span (`end(ERROR)`) reports the right `status` / `endNanos` after
    a real join (regression against the funnel refactor for B);
  - for C, a test that reporting is reachable only post-completion.
  Do **not** claim the race itself is tested — state in the PR that the fix is by-construction, not by test.
- **Backlog:** on landing, delete D1 (it is a `Dnn` — safe to cite until then).
- **ADR:** add one for B (field-shape + concurrency change) or C (report-gating guarantee); none for A.
```
