# ADR-043: Room's query executor carries the submitting thread's trace context

- **Date:** 2026-11-01
- **Status:** Accepted (implemented in 0.7.0)
- **Affects:**
  - `kotrace-room`: `tracing()` gains `queryExecutor: Executor? = null` and sets Room's query executor, a wrapper
    over `queryExecutor`, else Room's own default (`ArchTaskExecutor`'s IO executor);
  - the core: `TraceHandoff.withCapturedContext(block)` mirrors the captured span, collector, configuration and
    ambient scope id onto the calling thread for the block.

  **Compatibility:**
  - **Binary:** additive. The 0.6.0 `tracing()` signature stays as a hidden overload.
  - **Behaviour:** `tracing()` now replaces a query executor set before it, and one set after it replaces kotrace's.
    An app that set only `setTransactionExecutor` ran its queries there (Room copies one executor to the other);
    they now run on Room's default unless it passes that executor as `queryExecutor`.
- **Amends:** [ADR-028](adr-028-api-hygiene-0-5-0.md) (the `tracing()` installer).

## Context

`TracingQueryCallback` logs onto `currentThreadSpan()`, inline on Room's query thread. That relied on Room running
a suspend query under the caller's coroutine, so `SpanContext`, a `ThreadContextElement`, is mirrored onto Room's
thread. A test on a real database (Robolectric, the Room 2.6.1 floor) showed this holds for writes only:

- a suspend write runs through `withContext(transactionDispatcher)`, which keeps the caller's context;
- a suspend `@Query` read outside a transaction runs through `GlobalScope.launch(queryDispatcher)`, which carries
  only the dispatcher. Room's thread mirrors no span, and every read logged nothing.

Room 2.8.4 keeps the caller's context for both. The floor is a published contract (ADR-027), so 2.6 must work.

Room's dispatcher calls the query executor's `execute` from the submitting thread, while the caller's span is still
mirrored there. The executor is the one place that sees both threads.

## Decision

- **`tracing()` wraps the query executor.** On `execute`, the wrapper captures the submitting thread's
  `TraceHandoff`. The task then runs inside `withCapturedContext`, which mirrors that context on Room's thread and
  restores the thread afterwards. With nothing to carry, the task is passed through as it is.
- **Room's own default when the app passes none.** The builder exposes no getter for an executor set earlier, so the
  app passes its own as `queryExecutor`. Without one, `tracing()` uses `ArchTaskExecutor.getIOThreadExecutor()`,
  which Room 2.6 uses itself, so the threads queries run on do not change.
- **The core owns the mirroring.** The thread mirrors are internal to the core, and any library that calls back on
  its own thread has the same need, so the verb is `TraceHandoff.withCapturedContext`, beside `withCapturedConfig`.
  It mirrors the **ambient** scope id, not the resolved `scopeId`, so a mirrored thread still reads the fallback
  scope (ADR-038) only where no scope is ambient.
- **Capture is isolated.** The capture runs inside Room's dispatch, so it goes through `runIsolated` as
  `FaultPhase.DATABASE`, `database.query.capture` (ADR-035). Only a JVM-fatal error and the strict-not-installed
  failure propagate.

## Options considered

- **Opt-in: wrap only when the app passes `queryExecutor`: rejected.** Suspend reads on the floor would stay silent
  unless every app found and set the parameter.
- **A module-local mirror in `kotrace-room`: rejected.** The callback would read a second, Room-only thread state,
  and every later integration would write its own (CLAUDE.md: an integration reuses what the core provides).
- **An `Executor` wrapper in the core: rejected.** `Executor` is a JVM type, and the core stays free of JVM-only
  concepts for KMP. The core offers the block; the module owns the `Executor`.
- **Raise the Room floor to 2.7 or later: rejected.** It is a compatibility decision for every consumer (ADR-027),
  and many apps have not taken Room's Kotlin Multiplatform rewrite.

## Consequences

- Suspend reads log their SQL on Room 2.6, onto the span they ran under; concurrent reads stay on their own spans.
  `TracingRoomDatabaseTest` pins this on a real in-memory database.
- A task Room submits from a traced query thread, such as its invalidation refresh after a write, carries that
  span, and its SQL is logged there, possibly after the span ended.
- On Room versions that keep the caller's context, the wrapper mirrors the same span the coroutine already mirrors.
- `kotrace-room` compiles against `androidx.arch.core:core-runtime` (`compileOnly`, a restricted API of Room's
  library group), which Room brings at run time.
