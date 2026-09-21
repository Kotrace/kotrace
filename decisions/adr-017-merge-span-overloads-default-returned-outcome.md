# ADR-017 — Merge the two `span` overloads into one (`returnedOutcome` defaulted); one `autoRootSpan` boundary

- **Date:** 2026-09-18
- **Status:** Accepted
- **Amended:** 2026-09-21 — ADR-018's `SpanCompletion<T>` refactor replaced the internal root-opening
  recursion with a shared `executeSpan` helper; the merged public API and mapper behavior decided here are
  unchanged.
- **Affects:** `dev.kotrace.span` (`Trace.kt`) collapses from **two overloads** to **one** function whose
  `returnedOutcome: (T) -> TraceOutcome` parameter is **defaulted** to a new private singleton `alwaysOkOutcome`
  ([`Trace.kt:46`](../src/main/kotlin/dev/kotrace/Trace.kt:46), [`Trace.kt:50`](../src/main/kotlin/dev/kotrace/Trace.kt:50),
  [`Trace.kt:105`](../src/main/kotlin/dev/kotrace/Trace.kt:105)). The two private auto-root helpers introduced by
  ADR-013 (`autoRootSpan`, OK-only) and ADR-016 (`autoRootSpanReturning`, value-aware) collapse into a **single**
  `autoRootSpan(name, attributes, links, returnedOutcome, block)`
  ([`Trace.kt:145`](../src/main/kotlin/dev/kotrace/Trace.kt:145)); `autoRootSpanReturning` is deleted. No change to
  `TraceOutcome`, `reportAutoRoot`, `runReturnedOutcome`, `FaultPhase.RETURNED_OUTCOME`, or any fan-out. **This is a
  breaking API change on the released 0.4.0** (see § Source compatibility) → **0.5.0**.
- **Supersedes:** [ADR-016](adr-016-auto-root-returned-outcome.md) **§ Source compatibility** and its
  "**second, separate overload**" shape. ADR-016's *behavior* (failure-as-value auto-root, mapper contract,
  fault isolation, precedence) is retained **unchanged**; only its two-overload *packaging* is replaced.
- **Amends:** [ADR-013](adr-013-auto-root-span.md) — the sole `autoRootSpan` boundary now carries the
  `returnedOutcome` mapper; the OK-only boundary is gone (the zero-config path supplies `alwaysOkOutcome`).
- **Builds on:** [ADR-016](adr-016-auto-root-returned-outcome.md), [ADR-013](adr-013-auto-root-span.md),
  [ADR-003](adr-003-span-verb-rename-and-startspan-optin.md) (`span` the sole verb),
  [ADR-014](adr-014-adapter-fault-isolation.md) (mapper-fault routing, untouched).

## Context

ADR-016 delivered failure-as-value auto-root as a **second overload** carrying a *required* `returnedOutcome`,
deliberately keeping the original zero-config overload verbatim. Its § Source compatibility stated the reason
plainly: adding `returnedOutcome` *before* `block` on the single existing `span` would break the fully
**positional in-parentheses** call `span(name, attributes, links, block)` — kotrace's own recursion did exactly
that — so a *separate* overload was chosen to keep every existing call site untouched.

That left the surface carrying **two `span` overloads** and **two private auto-root helpers**
(`autoRootSpan` OK-only + `autoRootSpanReturning` value-aware) that differ only in where the normal-return
status comes from. The two are **behaviorally identical modulo a constant mapper**: the OK-only boundary is
exactly the value-aware boundary invoked with a mapper that ignores its input and returns
`TraceOutcome(OK)`. Escaping outcomes (`CANCELLED` / `ERROR`), ordering (report after the root's `markEnd`,
before the collector context exits), and strict/report precedence are the same code either way. The
duplication is pure packaging, and it costs a second overload, a second helper, and a second docstring that
must be kept in lockstep.

The reason to remove it *now*, cheaply: a **non-capturing** `alwaysOkOutcome` compiles to a JVM singleton, so
routing the zero-config path through the defaulted parameter allocates **no** mapper and adds nothing to the
per-span instrumentation path (the parameter is inert on every non-auto-root span; the auto-root already runs
a mapper regardless). The performance objection ADR-016's split implicitly guarded is empty here.

The reason it was *not* free before, and the reason this ADR must supersede rather than extend: merging is
**only** possible by placing `returnedOutcome` before the trailing `block`, which reopens the exact
positional-in-parentheses break ADR-016 engineered around. That break is now assessed as acceptable (§ Source
compatibility).

## Decision

**One `span`. `returnedOutcome` becomes a defaulted parameter; the default is the always-OK mapper. One
`autoRootSpan` boundary consults it.**

```kotlin
private val alwaysOkOutcome: (Any?) -> TraceOutcome = { TraceOutcome(TraceStatus.OK) }   // non-capturing → singleton

suspend fun <T> span(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    returnedOutcome: (T) -> TraceOutcome = alwaysOkOutcome,   // ADR-016 mapper, now defaulted
    block: suspend () -> T,
): T
```

- The auto-root branch forwards the **parameter**, not a hard-coded `alwaysOkOutcome`
  ([`Trace.kt:60`](../src/main/kotlin/dev/kotrace/Trace.kt:60)) — a `span(returnedOutcome = m) { }` must reach the
  mapper `m`, not the default.
- `autoRootSpanReturning` is deleted; the surviving `autoRootSpan`
  ([`Trace.kt:145`](../src/main/kotlin/dev/kotrace/Trace.kt:145)) is the ADR-016 value-aware body verbatim.
- At adoption, the internal recursion that opened the root via the instrumentation path passed the block by **name** —
  `span(name, attributes, links, block = block)` ([`Trace.kt:158`](../src/main/kotlin/dev/kotrace/Trace.kt:158)) —
  because the fourth positional slot is now `returnedOutcome`; the recursion never auto-roots (a collector is in
  context), so leaving `returnedOutcome` at its default is correct. The later ADR-018 amendment replaced this
  recursion with `executeSpan`, which has no `returnedOutcome` parameter.
- `(Any?) -> TraceOutcome` stands in for the `(T) -> TraceOutcome` parameter and default at every `T` by
  function-parameter **contravariance** — no cast, verified by compile.

**Behavioral equivalence (why this is safe).** For any call:

- *normal return* → `returnedOutcome(value)` (default `alwaysOkOutcome` ⇒ `OK`) — identical to the old OK-only
  boundary's fixed `OK` and to the old value-aware boundary's mapped status;
- *escaping `CancellationException`* → `CANCELLED`; *any other escaping throwable* → `ERROR`; the throwable
  rethrown unchanged — core-owned, mapper never consulted;
- report fires **after** the root's `markEnd`, **before** the collector context exits, with the unchanged
  `reportAutoRoot` precedence.

No observable behavior changes for either former overload. The full existing test suite passes unmodified
except the one compat-guard test rewritten in § Test matrix.

## Source compatibility

**Breaking, on a released version. `span(name, attributes, links, block)` with `block` as the fourth
positional argument (in parentheses, not a trailing lambda) no longer compiles** — the fourth slot now binds
`returnedOutcome`. This is precisely the shape ADR-016 § Source compatibility preserved by keeping two
overloads; ADR-017 knowingly reverses that call. Unlike ADR-016's window (0.4.0 unreleased at the time), 0.4.0
is now **released and tagged** (`v0.4.0`), so this is a genuine external break: both **source** (the
positional-block shape) and **binary** (the JVM signature `span(String, Map, List, Function1)` disappears; a
pre-compiled caller of it gets `NoSuchMethodError`). Pre-1.0 semantics: **bump 0.4.0 → 0.5.0.**

Why the break is acceptable:

- **The idiomatic shape is unaffected.** A trailing lambda always binds the final `block`, and Kotlin skips a
  defaulted parameter that precedes it, so `span(name) { }` and `span(name, attrs, links) { }` resolve to the
  merged function untouched. The failure-as-value shape `span(name, …, returnedOutcome = m) { }` (a named
  argument in every real call, including the reference consumer's `observe`) resolves unambiguously.
- **No caller uses the broken shape.** A sweep of kotrace (`src`, `demo`, `benchmarks`) and the reference
  consumer (Camailux `platform-sdk`) found the positional-block form **only** in kotrace's own recursion (fixed
  here with a named `block =`) and in the ADR-016 compat-guard test (rewritten). Zero product call sites.
- **The duplication it bought is the thing being removed.** Keeping the second overload solely to preserve a
  call shape nobody uses is the trade ADR-017 declines.

Consumers on 0.4.0 upgrade by: bumping to 0.5.0, and — only if they wrote `span(a, b, c, someBlock)`
positionally — switching to a trailing lambda `span(a, b, c) { … }`. No `returnedOutcome` call site changes.

## Options considered

- **Merge into one defaulted-parameter `span` + one `autoRootSpan` (chosen).** Smallest surface: one verb, one
  boundary, one docstring; zero-config path allocation-free via the `alwaysOkOutcome` singleton; ADR-016
  behavior intact. Cost: the positional-block source/binary break (§ Source compatibility).
- **Keep ADR-016's two overloads + two helpers (status quo) — rejected.** Zero compat cost, but permanent
  packaging duplication (two overloads, two auto-root helpers, two docstrings kept in lockstep) for a call
  shape no product uses. This ADR exists to retire that.
- **Merge the two `span` overloads but keep two auto-root helpers — rejected.** Removes the public duplication
  yet leaves `autoRootSpan`/`autoRootSpanReturning` as private twins differing only by a constant mapper. The
  singleton default makes one helper strictly sufficient; two is dead weight.
- **Merge but inline `{ TraceOutcome(TraceStatus.OK) }` as the default instead of a named singleton —
  rejected.** Works (a non-capturing lambda is still a singleton), but a named `alwaysOkOutcome` documents the
  intent ("this is the always-OK contract"), guarantees one shared instance, and gives the KDoc a symbol to
  reference.
- **Overload-neutral rename (`autoRootSpanReturning` kept as the survivor's name) — rejected.** Once the
  boundary is singular, the "Returning" suffix distinguishes nothing; `autoRootSpan` is the plain concept name.

## Consequences

- **One `span`, one auto-root boundary.** The failure-as-value feature is now a *defaulted parameter*, not a
  distinct overload; discoverability shifts from "a second signature" to "a parameter with a default". `span`
  remains the sole verb (ADR-003/013 hold).
- **The always-OK contract becomes a convention, not a structural guarantee.** In ADR-013's OK-only boundary,
  "normal return ⇒ `OK`" was unbreakable by construction; it is now "the zero-config call omits `returnedOutcome`,
  so it defaults to `alwaysOkOutcome`". A caller passing a non-OK mapper opts out — which is the whole point of
  the parameter. Guard remains the test asserting the default reports `OK`.
- **A released, breaking change.** 0.5.0 required; the positional-block call shape is gone (§ Source
  compatibility). The reference consumer needs only a version bump — its call site already uses a named
  `returnedOutcome`.
- **Docs move with the code.** `Trace.kt` KDoc is a single merged contract for `span` (both `@sample`s
  retained) and for `autoRootSpan`; ADR-016 § Source compatibility is marked superseded by this ADR; ADR-013's
  auto-root boundary description now reads through the single mapper-carrying `autoRootSpan`.

## Test matrix

All ADR-013 and ADR-016 auto-root cases carry over **unchanged** against the merged function (both former
overloads' behavior is preserved): the full suite passes with a single edit. The one changed test is the
compat guard —

- **`existing call shapes still bind the zero-config overload`** (ADR-016) asserted the now-broken
  positional-block shape `span(name, attrs, links, { })` binds. It is rewritten as **`surviving call shapes
  bind the single span function`**: the trailing-lambda forms `span("trailing") { }` and
  `span("with-attrs", emptyMap(), emptyList()) { }` each report `OK`; the dropped positional-block shape is
  documented in the test comment as intentionally removed by this ADR.

Everything else stands: mapper maps returned failure → `ERROR` + `attached` (one report, keyed to root, past
birthplace dedup); success → `OK`; a value mapped to `CANCELLED` reports `CANCELLED`; escaping non-cancellation
→ `ERROR`, mapper not invoked; escaping `CancellationException` → `CANCELLED`, mapper not invoked; mapper
invoked exactly once on a normal auto-root return and zero times on each non-auto-root state; mapper-fault
isolation (`OK` fallback, `phase = RETURNED_OUTCOME`, `adapter == null`; `CancellationException` contained;
JVM-fatal rethrown); strict-mode and ordering precedence.

---

[← All decisions](../DECISIONS.md)
