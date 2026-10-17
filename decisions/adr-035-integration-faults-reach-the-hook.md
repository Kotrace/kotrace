# ADR-035: Integration faults reach the FaultHook, and strict mode reaches integrations

- **Date:** 2026-10-16
- **Status:** Accepted
- **Affects:**
  - core: the fault-isolation rule becomes one public function, `runIsolated`, in `dev.kotrace.bridge` behind a new
    opt-in annotation `@KotraceIntegrationApi`, and the core's own steps use it too; `FaultPhase` gains `HTTP` and
    `DATABASE`; `TraceHandoff.captureOrNull` throws under an
    armed strict check when nothing is installed and there is no span or collector;
  - `kotrace-okhttp`: every telemetry step reports its fault as `FaultPhase.HTTP`; the strict-not-installed failure
    propagates, after the span ended, and rides the call's own exception as suppressed when the call is failing;
  - `kotrace-room`: a logging fault is reported as `FaultPhase.DATABASE`; the strict failure propagates after the
    app's callback ran.

  ABI: additions only. `runIsolated` is inline, so the one internal helper it calls, `routeFault`, which holds the
  fault handling and keeps the hook's reentrancy guard private, is `@PublishedApi` and appears in the dump; it stays
  `internal` in source. Behavior: only under `strictWhenNotInstalled`, which a release
  build does not arm.
- **Amends:** [ADR-023](adr-023-okhttp-trace-handoff.md), whose interceptor contained every telemetry fault
  silently, and the Room callback's equally silent containment. **Builds on**
  [ADR-034](adr-034-own-faults-never-change-the-outcome.md), whose rules `span` already follows, and
  [ADR-011](adr-011-strict-uninstalled-optin.md).

## Context

The core reports every fault it contains to the `FaultHook`, but the integrations contained theirs in silence:
eight sites in `kotrace-okhttp` and one in `kotrace-room` caught a fault and dropped it. A consumer whose hook feeds a
crash reporter saw clean numbers while HTTP bodies or SQL logs went missing, and the first sign of a broken step
would be a debugging session short of the very data it needed. Waiting for that signal costs the data.

The silence also swallowed the strict-not-installed failure. A flow made only of bridged calls (a background sync
that makes HTTP calls under no span) never surfaced a missing install in a debug build: `captureOrNull` read the
configuration without the strict check, and a failure from a span log was dropped. ADR-023 had fixed strict firing
**wrongly**, when a per-flow override existed but the dispatcher thread could not see it; the captured
configuration now carries that override, so the check fires only when nothing is installed at all.

kotrace targets Kotlin projects beyond Android, KMP among them, and a Ktor integration is planned. Each integration
would otherwise grow its own silent copy of the same containment.

## Decision

- **One rule, one function, for the core and every integration.**
  `runIsolated(phase, faultMessage, escaping, recover, block)`, behind `@KotraceIntegrationApi`, runs a telemetry
  step under `span`'s rules (ADR-034):
  - a JVM-fatal error propagates;
  - the strict failure propagates, or rides `escaping`, the throwable the traced code is already failing with, as
    suppressed, so it never replaces it;
  - any other fault is told to the `FaultHook` as `phase`, with a `faultMessage` naming the step (ADR-036), and `recover`
    gives the result. A null `phase` keeps the hook out of it, for a fault that is expected (a probe, a `traceparent`
    for a trace id that is not W3C) or surfaces another way (suppressed on the application's throwable).

  The core's fan-out, report, span opening and classifier steps use it as the integrations do, so no module keeps a
  helper of its own. The hook is always the one of the configuration on the calling thread, resolved only on a
  fault; no caller passes another. It is inline, so the hot live path allocates nothing. Its opt-in keeps it off an
  application's surface: an application's own faults are not the hook's. It is not `@NonSuspendTracingBridge`,
  since a Ktor integration is suspend code.
- **Phases name the domain, not the library.** `HTTP` (OkHttp, Ktor) and `DATABASE` (Room, SQLDelight), so a hook
  routes by kind and the core never names a third-party library.
- **Strict reaches the traced code, as from `span`.** An integration throws the strict failure only once that cannot
  corrupt its own state: the HTTP span ends first; a failing call keeps its own exception, with the strict failure
  suppressed on it; Room's callback forwards the query to the app's callback first. `captureOrNull` throws it too,
  so a bridged call outside any span surfaces a missing install.
- **Some catches stay silent, as they are not faults:** `SnippetFull`, which stops a body copy at its cap by design;
  a trace id that is not W3C, already logged as "traceparent omitted"; and the cancellation and timeout probes on a
  chain that cannot answer them.

## Options considered

- **Keep the containment silent until a missing body is reported: rejected.** By then the data the investigation
  needs is gone.
- **Report through the hook but keep strict contained in integrations: rejected.** With nothing installed there is
  no configuration and therefore no hook, so the one failure strict exists to surface would stay silent.
- **Phases per library (`OKHTTP`, `ROOM`, `KTOR`): rejected.** Every new integration would change the core enum.
- **One `INTEGRATION` phase: rejected.** A hook could not tell an HTTP fault from a database one.
- **A public function without opt-in: rejected.** An application could route its own faults into the hook.
- **A second, silent helper beside `runIsolated` (`catchNonFatal`): rejected.** Two functions would split one rule;
  a null `phase` covers the silent case.
- **A per-integration entry point that hands the strict failure back (`containIntegrationFault`): replaced.** Each
  integration then rewrote the same rethrow-or-suppress logic; `escaping` puts it in the core.

## Consequences

- Under an armed strict check with nothing installed, a debug build now fails bridged work: a successful HTTP call
  throws the strict failure, and an asynchronous call's dispatcher thread sees it uncaught, which crashes an Android
  debug build. That is the point of strict mode (ADR-011); a release build does not arm it.
- A consumer's `FaultHook` now sees integration faults, so its counts cover HTTP and database telemetry too.
- A Ktor or SQLDelight integration reuses the same entry point and phases.
