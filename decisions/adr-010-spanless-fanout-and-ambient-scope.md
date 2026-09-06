# ADR-010 — Span-less live fan-out + `scope_id`, a correlation level above `trace_id`

- **Date:** 2026-09-06
- **Status:** Accepted
- **Affects:** new span-less emit verbs (`emitLog`/`emitNamed`/`emitException`), a process-wide install-once
  fan-out config (`Kotrace.install`) with per-flow `TraceConfig` **override**, a `withScope` ambient
  primitive, `dev.kotrace.event.TraceRecord` + `toJson` (adds nullable `scope_id`), `ARCHITECTURE.md`
  §Vocabulary + §data model + §4
- **Builds on:** [ADR-002](adr-002-remove-capture-gate.md) (fan-out is the single filtering authority),
  [ADR-009](adr-009-trace-link-cross-trace-correlation.md) (correlation vocabulary)

## Context

Fan-out today is **per-trace**: `reportTrace` walks a `SpanCollector`, and live adapters fire only for
events on a span whose coroutine context carries a `TraceConfig`. Two consequences:

1. **An emit outside any span cannot flow through kotrace at all.** A log/event/exception raised where
   no operation span is open — app lifecycle, a push-notification callback, any non-coroutine entry —
   has no span to attach to and no `TraceConfig` in context. A consumer wanting *one* fan-out authority
   (ADR-002's goal) must fall back to calling its sink directly, so the sink is reached two ways.
2. **There is no correlation level above a trace.** `trace_id` identifies one bounded operation. There
   is no key that groups *many* operations plus their surrounding orphan emits into one session/journey
   — "everything that happened while handling this notification", "this app session".

## Decision

**Add a live-only fan-out for span-less emits, and a `scope_id` correlation level above `trace_id`.**

- **Config is process-wide, with a per-flow override.** Which sinks a consumer runs is static, so it lives
  in one **install-once process-wide `TraceConfig`** (`Kotrace.install(adapters)`, or `install { … }` for a
  provider resolved lazily once — DI-friendly; published through an `AtomicReference`, read-only after,
  double-install a hard error). Only per-*flow* state — the
  `SpanCollector`, the current span, the scope — stays in the `CoroutineContext`. Every fan-out site
  resolves `currentThreadConfig() ?: Kotrace.defaultConfig()`: a context `TraceConfig` is an **override**
  for its flow, the global covers everything else. One resolution feeds **every** path — span and
  span-less, live and report, suspend and non-suspend — so they differ only in the coroutine mechanism that
  locates the flow, never in which adapters see a record. (This subsumes ADR-002's "single filtering
  authority": config too now has one source.) Install is optional; with neither global nor override, a path
  is a safe no-op.
- **Span-less emit verbs.** `emitLog` / `emitNamed` / `emitException` build a record with no span and fan it
  through that resolved config's live adapters. **Live-only** — there is no tree to tail-buffer, so no
  report path; an orphan emit fires as it happens or not at all. `TracePolicy` still gates it (the per-trace
  live predicate minus the span-only `acceptsSpan`). A non-suspend root behaves the same: live fans through
  the config, while **report** still needs a per-flow `SpanCollector`, so a collector-less flow is live-only
  by construction.
- **Exception emits carry record-level `info`.** `emitException(cause, info)` — and `addException(cause,
  info)` — populate `ExceptionRecord.info` (which already exists), a `Map<String,String>` **distinct from
  the object-only `ExceptionEvent`**. kotrace does not interpret `info`; a consumer uses it to carry emit
  metadata (a caller-defined "explicit report" marker, a user-report flag, correlation ids) **without**
  putting an attribute bag on the throwable event — the raw object stays the crash reporter's primary,
  and the exception-event PII invariant (ADR-005) is untouched. Because exception emits already fan
  **live**, an explicit report reaches live adapters **unconditionally**, independent of any trace's
  `reportTrace`/status — so a consumer can record it immediately while a birthplace exception stays on
  the report path.
- **`scope_id` — a level above `trace_id`.** `scope ⊇ trace ⊇ span`. A `TraceRecord` gains a **nullable
  `scopeId`**; `toJson` emits it only when present (back-compat: absent = today's shape).
- **`withScope(scopeId) { … }`** establishes an **ambient scope** in context: a live-only root that
  propagates a `scope_id`, but **never** opens a reportable tree and has no bounded lifetime. It carries no
  config — the emit site resolves that (context override, else global) — so a scope holds only the
  correlation key. `span`/`reportTrace` opened *inside* a scope stamp its `scope_id` onto their records
  alongside `trace_id`; span-less emits inside it carry `scope_id` only. Nothing is truly orphan while a
  scope is active.
- **`scope_id` is a plain correlation key.** It is **never** subject to duration, outcome (`OK`/`ERROR`),
  waterfall rendering, or the report path — those are trace semantics. Keeping it a **distinct field**
  (not an overloaded `trace_id`) makes the distinction structural, not conventional.

## Consequences

- One fan-out authority for **every** occurrence, orphans included; a consumer's sink is reached only
  through kotrace, never directly. Config now has one source too (global + optional override), not a config
  per trace root.
- A session/journey correlation key falls out for free: `scope_id` groups many traces + their orphans.
- Span and span-less, live, suspend and non-suspend all fan through the same resolved config — uniform data,
  differing only by the coroutine mechanism. Report alone stays coroutine-bound (it needs the per-flow
  collector), so a collector-less flow is live-only.
- **A process-global fan-out config** — mutable process state in a library that is otherwise context-scoped
  and pure. Accepted: config is static and process-wide by nature; installed once at startup, read-only
  after (double-install a hard error); documented as the one global. Thread-safe publication required. A
  per-flow `TraceConfig` override remains for a flow that needs different sinks.
- `scope_id` is nullable on the wire; existing traces and tooling are unaffected until a scope is opened.
- One active scope per context (innermost). A scope **stack** is **not** modelled — YAGNI until a real
  nested-scope need appears.

## Rejected

- **Overload `trace_id` for scopes** (mark a long-lived `trace_id` with a `scope=` attribute): breaks
  every tool that reads `trace_id` as a bounded operation — duration (a "6-hour trace"), outcome,
  fingerprint/grouping, waterfall, per-trace retention caps. A distinct `scope_id` keeps `trace_id`'s
  meaning pristine.
- **A never-ending ambient span** (model the scope as a real long-lived span): the report path never
  fires (the span never completes) or fans out a giant tree; duration/outcome are meaningless on it. A
  scope is a correlation key, not an operation — so it is not a span.
- **Model identity in kotrace** (a hypothetical `identify`/`IdentityRecord`): identity is a subject-state
  upsert, not a timed occurrence — no `atNanos`, no span, no correlation value. It violates every record
  invariant and drags a CDP concern into a tracing library. Stays the consumer's analytics concern.
- **Put emit metadata on the `ExceptionEvent`** (an attribute bag on the throwable event): reintroduces
  exactly what ADR-005 kept off it — the raw object is the crash reporter's primary, and an attribute bag
  invites `exception.message` PII. Emit metadata rides `ExceptionRecord.info` (record-level, kotrace does
  not interpret it); the event stays object-only.

---

[← All decisions](../DECISIONS.md)
