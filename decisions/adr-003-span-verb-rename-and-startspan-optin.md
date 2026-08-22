# ADR-003 — Rename the suspend `trace` verb to `span`, and gate `startSpan` behind `@RequiresOptIn`

- **Date:** 2026-08-22
- **Status:** Accepted (implemented)
- **Affects:** `dev.kotrace.trace` (suspend span-opener, renamed to `span`), `dev.kotrace.startSpan`
  (non-suspend bridge, gains an opt-in marker), `README.md`, `ARCHITECTURE.md` § Vocabulary. Public API —
  a breaking change; see Migration.

## Context

kotrace exposes two ways to open a span:

- `suspend fun trace(name) { block }` — scoped. Opens a child of the current span, installs it into the
  coroutine context via `withContext(SpanContext(span))`, runs `block`, closes it in `finally`, marks
  ERROR and records the throwable in `catch`, rethrows.
- `fun startSpan(name): Span` + `Span.end(...)` — manual. Opens a span parented off the `currentThreadSpan`
  thread-local mirror, registers it into the collector, and hands the caller a `Span` to close by hand. It
  exists for non-suspend code running on a coroutine's thread with no `coroutineContext` handle — the
  OkHttp `Call.Factory` is the case it was built for.

Two problems, both felt first by a newcomer.

**1. The names are inconsistent, and one of them is wrong by kotrace's own vocabulary.** ARCHITECTURE
§ Vocabulary makes `trace` (one tree / `trace_id`) and `span` (one node) canonical and non-interchangeable;
CLAUDE.md restates it ("the four verbs never interchange"). Yet `trace(name) { }` opens **one span** — a
node, not a tree — and usually a *child*, not a root. It is named after the wrong altitude (tree vs node)
and implies the wrong thing (start-the-trace vs open-a-child). `startSpan` names the same underlying
operation correctly. So the two public entry points do the same thing — open a span, child-or-root — under
two different nouns, one of which the project's own glossary forbids. A reader cannot infer the rule
because there isn't one; it is drift.

**2. `startSpan` is silently misusable from suspend code, with no compiler signal.** It is a plain
function, so it compiles anywhere. Called inside a suspend function it *appears* to work — the thread
mirror holds the active span at the call instant, so the new span parents and collects. But `startSpan`
never installs itself into the coroutine context (no `withContext(SpanContext)`). So any nested suspend
`trace`/`currentSpan()` reads the **old** ambient span as its parent, not the `startSpan` node. The nested
work becomes a sibling of the manual span instead of its child. The tree is silently misparented; nothing
errors, nothing warns. The only guard today is a KDoc sentence.

## Decision

Two coupled changes, shipped together.

**A. Rename the suspend verb `trace` → `span`.** The scoped opener becomes `suspend fun span(name) { }`.
This aligns the public name with the concept it operates on (a node) and with the manual bridge
(`startSpan`), leaving *shape* — scoped block vs `start…`/`end` pair — as the only axis that distinguishes
the two, mirroring the established Kotlin idiom (`withLock { }` vs `lock()`/`unlock()`, `use { }` vs
manual close).

```kotlin
span("loadAccount") { … }          // scoped, suspend — the default
val s = startSpan("okhttp.call")   // manual, non-suspend — the bridge
try { … s.end() } catch (t: Throwable) { s.end(SpanStatus.ERROR, t); throw t }
```

**B. Mark `startSpan` (and `Span.end`) with a `@RequiresOptIn` bridge marker.**

```kotlin
@RequiresOptIn(
    message = "startSpan is the non-suspend tracing bridge. In suspend code use span { } instead — " +
        "startSpan does not install its span as the ambient parent, so nested spans misparent silently.",
    level = RequiresOptIn.Level.ERROR,
)
annotation class NonSuspendTracingBridge

@NonSuspendTracingBridge
fun startSpan(name: String, attributes: Map<String, String> = emptyMap()): Span
```

Every `startSpan` call now raises a compiler warning carrying the message above, cleared only by an
explicit `@OptIn(NonSuspendTracingBridge::class)` at the call site. The genuine bridge (the OkHttp factory)
opts in once, deliberately, with a comment. Every accidental suspend-context use lights up and points the
author back to `span { }`.

## Options considered

- **A+B together (chosen).** Rename removes the false vocabulary and makes `span { }` the obvious default;
  the marker makes the escape hatch a deliberate, self-documenting opt-in. Naming fixes discoverability,
  opt-in fixes enforcement — neither alone closes the gap.
- **Rename only — rejected.** Better discoverability, but `startSpan` stays silently misusable from suspend
  with no compiler signal. Leaves the sharper of the two problems open.
- **Opt-in only, keep `trace` — rejected.** Guards misuse but preserves the vocabulary violation the ADR
  set out to fix; a newcomer still cannot infer why one is `trace` and one is `Span`.
- **Konsist/lint rule instead of `@RequiresOptIn` — rejected as primary, kept as optional backstop.** A
  rule "`startSpan` inside a `suspend` fun fails the build" is enforceable on the *consumer* side
  (Camailux already runs `:lint-architecture`), but it lives in the wrong repo (the guard belongs on the
  API, not each consumer), and static call-target resolution across the module boundary is brittle.
  `@RequiresOptIn` travels with the artifact and works for every consumer. A Konsist rule may be added
  downstream as a hard gate on top; it does not replace the marker.
- **Runtime detection — rejected.** `startSpan` is non-suspend and cannot read `coroutineContext` to check
  whether a live `SpanContext` encloses it. The thread mirror is set during any suspend frame — including
  the legitimate OkHttp case, which runs on the coroutine thread — so a mirror-present check false-positives
  on exactly the use the function exists for.
- **Keep `trace` as a deprecated alias — rejected.** A lingering alias re-introduces the two-name
  inconsistency the rename removes. kotrace's sole consumer (Camailux) builds it from source via a local
  `includeBuild`, so there is no published-artifact window to bridge: the rename is applied atomically —
  every call site moves to `span` in the same change and the `trace` overload is deleted outright, no
  `@Deprecated` shim.

## Consequences

- **Vocabulary is honest.** Every public opener now says `span`; `trace` is reserved for the tree, as
  ARCHITECTURE § Vocabulary already claims. The four-verbs rule stops being contradicted by the entry point.
- **Misparenting is now a compiler warning, not a silent bug.** The `@RequiresOptIn` message names the
  exact failure mode and the fix. The OkHttp bridge carries one `@OptIn` with a rationale comment; nothing
  else needs it.
- **Atomic breaking change — no shim.** Every `trace(...) { }` call site becomes `span(...) { }` and the
  `trace` overload is removed in the same change; every `startSpan`/`end` site gains
  `@OptIn(NonSuspendTracingBridge::class)`. Safe to do in one step because the only consumer builds kotrace
  from source (`includeBuild`), so there is no published version to keep compiling. See Migration.
- **Misuse fails to compile — hard gate.** The marker is `ERROR` level: an un-opted `startSpan`/`end` call
  does not compile, so the misparenting bug cannot reach a build. This is deliberately stricter than a nudge
  — the bridge has exactly one legitimate site (the OkHttp factory), so a hard stop costs one `@OptIn` there
  and forecloses every accidental suspend-context use.
- **Docs move with the code.** README examples and ARCHITECTURE § Vocabulary update in the same change
  (STD-DOC-001 `DOC-XCT-2`): the rename is not real until the docs stop saying `trace` for a span.

## Migration

Applied in one change (kotrace is consumed from source, so nothing straddles a published boundary):

1. Rename `suspend fun trace(...)` → `span(...)` in `Trace.kt`; no `@Deprecated` shim is kept.
2. Add `@NonSuspendTracingBridge` to `startSpan`/`Span.end`; add `@OptIn` at the OkHttp `Call.Factory`
   bridge and interceptor with a comment pointing here.
3. Migrate every call site — kotrace's own tests/demo and the Camailux `:core:common` tracing wrapper —
   to `span`; opt-in the genuine `startSpan`/`end` sites.
4. Update docs (README, ARCHITECTURE § Vocabulary + § Capture) in the same change.
