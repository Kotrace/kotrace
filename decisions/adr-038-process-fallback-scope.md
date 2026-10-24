# ADR-038: A process-wide fallback scope for records emitted outside any `withScope`

- **Date:** 2026-10-24
- **Status:** Accepted
- **Affects:**
  - both `Kotrace.install` overloads gain `fallbackScopeId: (() -> String?)? = null`, install-once with the rest;
  - every site that stamps a `scopeId` (a span opening, root or child; a span-less emit; `startSpan`; `TraceHandoff`)
    resolves `ambient ?: fallback`, through one internal resolver;
  - `Kotrace.resetForTest` also clears it.

  Source-compatible (defaulted parameter); binary-breaking for a caller of `install`, so it lands in the next minor
  (0.6.0) with the `api/kotrace.api` dump updated.
- **Amends:** [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md), where a record outside any `withScope` carries
  no `scope_id`.

## Context

`scope_id` (ADR-010) groups many traces and their orphan emits into one session or journey. It exists only inside
`withScope`, a suspend boundary whose id reaches non-suspend code through a `ThreadContextElement` mirror, so it is
visible only while a coroutine carrying it runs on that thread. Two common callers never get one:

- **Entry points that are not suspend.** `Application.onCreate`, an Android `Service` callback, a click handler: the
  natural owner of a session scope cannot open `withScope` without `runBlocking`.
- **Synchronous emits from UI code.** A non-suspend `emitException` from an intent handler runs in no coroutine at
  all, so even a scope wrapped around the app's coroutines is invisible to it.

The result is records with neither `trace_id` nor `scope_id`: unjoinable to anything. A consumer cannot fill the gap
itself: the scope mirror is private, and stamping the id as an attribute would not make it the `scopeId` field that
sinks join on.

## Decision

- **A process-wide fallback scope.** `install(…, fallbackScopeId = { sessionId })` sets a provider, once, with the
  rest of the installed configuration. Every record resolves its scope as **the ambient `withScope` id, else the
  fallback's value, else none**. An ambient scope always wins; a nested `withScope` behaves exactly as today.
- **It applies to every record, not only span-less ones.** A trace opened outside any `withScope` takes the fallback
  too, so `scope ⊇ trace ⊇ span` holds for every record of the process.
- **A provider, not a fixed string.** The value is read at each resolution, so a consumer can rotate a session (for
  example after a long background period) without re-installing. It must be cheap and non-blocking; a throwing
  provider yields no scope for that record and reaches the fault hook (step `scope.fallback`), never the caller
  (ADR-034).
- **It is not a scope boundary.** No lifetime, no outcome, no report; it supplies a correlation key only, like
  `withScope` (ADR-010). A per-flow `TraceConfig` override does not carry one: correlation is not a sink choice.
- With nothing installed, behaviour is unchanged: no fallback, no scope.
- **Ambient readers stay ambient.** `currentScopeId()` and `currentThreadScopeId()` keep returning the `withScope`
  id only; the fallback is applied where a record is stamped, not where the ambient scope is read.
- **`startSpan`'s default resolves the fallback; an explicit argument is taken as given.** An explicit
  `scopeId = null` opens the span with no scope. `TraceHandoff` resolves the scope once, at capture on the
  coroutine's thread, and a bridge passes it on explicitly, so the provider is not consulted again on another
  thread, nor its fault reported twice.
- **A record the provider emits takes no fallback.** While the provider runs on a thread, a resolution on that
  thread yields no fallback rather than re-entering it, as with the install provider (ADR-025); otherwise a
  provider that logs would recurse into a `StackOverflowError`, which fault isolation rethrows.

## Options considered

- **A public setter for the thread's scope: rejected.** Unstructured, leaks across threads and coroutines, and has no
  restore point.
- **Consumers stamp the id as an attribute: rejected.** It is not the `scopeId` field, so sinks and `toJson` do not
  treat it as a join key.
- **Open `withScope` around the app's root coroutine only: rejected as sufficient.** It cannot cover non-suspend
  entry points or synchronous emits.
- **A fixed `String`: rejected.** Rotating a session would need a re-install, which is install-once.
- **On `TraceConfig`: rejected.** A per-flow override has no use for a different session key, the same reasoning as
  ADR-037 for the fault hook.

## Consequences

- A consumer that installs a fallback never emits an unjoinable record: an orphan carries the session `scope_id`.
- The fallback is coarse (the whole session); a consumer wanting journey-level grouping still opens `withScope`, which
  overrides it.
- The provider runs on the emitting thread at every resolution, including hot paths; its cost is the consumer's.
- `ARCHITECTURE.md` §Vocabulary (`scope`) and the `withScope` KDoc note the fallback when this lands.
