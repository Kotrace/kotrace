# ADR-025 — Same-thread reentrancy: nested emits are stored, never re-fanned; provider resolution is not re-entered

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** the live fan-out (`Span.emit`, the span-less `emitLog`/`emitNamed`/`emitException`) and
  `Kotrace.install { provider }` resolution. No API or wire change; KDoc on `LiveAdapter.onLive` and the provider
  `install` state the contract.
- **Builds on:** [ADR-014](adr-014-adapter-fault-isolation.md) (fault isolation — which already guards the fault
  hook against reentrancy, but not adapters), [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md) (lazy
  provider), [ADR-011](adr-011-strict-uninstalled-optin.md) (strict-uninstalled).

## Context

Two same-thread callbacks into kotrace recursed without bound. The result was a `StackOverflowError`, which
ADR-014 classes as JVM-fatal and rethrows into the traced code, so the app crashed on its first emit:

1. **A live adapter that logs through kotrace.** A typical Android setup has a Logcat live adapter that prints
   through Timber, and a Timber tree that forwards every log to `emitLog`. Then `onLive` → Timber → `emitLog` →
   fan-out → the same `onLive`, forever. A live adapter calling `span.log` loops the same way. ADR-014 guards only
   the fault hook against reentrancy.
2. **A provider that emits while resolving.** `Kotrace.install { … }` resolves through
   `lazy(SYNCHRONIZED)`, whose monitor is reentrant. An emit inside the provider (or inside an adapter constructor
   it calls, or in DI code that happens to be traced) reads the still-uninitialized `lazy` and runs the provider
   again. This contradicts the KDoc promise that a throwing provider is contained.

## Decision

- **Live fan-out guard.** A per-thread flag is set around each live fan-out (policies, record construction,
  `onLive`). An emit that arrives while the flag is set on its thread is **still appended to its span**, since the
  append happens before fan-out, so it still reaches the report path. It is **not fanned live again**. A span-less
  emit in that position has nowhere to be stored, so it is dropped. The report path is not guarded: an emit from
  `onReport` fans out live normally, because it cannot re-trigger a report.
- **Provider guard.** A per-thread flag is set while the provider runs. A fan-out that resolves the process-wide
  config on that thread gets an **installed, empty** `TraceConfig`. It reaches no adapter, does not re-enter
  `lazy`, and, because the config is non-null, does not trip strict-uninstalled mode. Without that last point, the
  strict failure would be contained by the provider guard and would permanently resolve the config as empty.
- **Not routed to the fault hook.** A skipped nested fan-out is expected wiring (a forwarding logger fires on every
  log), not a fault. Notifying would cost a throwable per forwarded log and flood the hook.

## Options considered

- **Report the skip to the fault hook once — rejected.** A per-thread "notified once" state has no natural reset,
  and the hook's contract is a `Throwable` cause, which does not exist here.
- **Drop the nested span event entirely — rejected.** The event is legitimate data (for example an adapter noting
  a slow sink on the span). Storing it keeps the report complete; only the recursive live delivery is the bug.
- **Guard only `onLive`, not the policy and record construction — rejected.** A policy or a lazy message that
  logs recurses the same way. One flag around the whole fan-out is simpler and covers every entry point.

## Consequences

- An adapter that forwards kotrace records back into kotrace no longer crashes. Its forwarded logs are stored but
  are not seen live twice.
- A message lambda that itself logs, `span.log { other.log { … }; "x" }`, stores the inner log without fanning it
  live. This is an acceptable edge.
- The guard is per-thread. An adapter that hands records to another thread, which then emits back into kotrace,
  still loops asynchronously. The `onLive` KDoc says not to do that.
- Emits made during provider resolution are lost by design. A provider should build adapters, not log through
  them.
