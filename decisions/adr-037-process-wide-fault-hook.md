# ADR-037: One process-wide fault hook, installed once, apart from the configuration

- **Date:** 2026-10-17
- **Status:** Accepted
- **Affects:**
  - new `Kotrace.installFaultHook(hook)`, install-once, callable before or after `Kotrace.install`;
  - `TraceConfig` loses its `faultHook` parameter, and both `Kotrace.install` overloads lose theirs;
  - `FaultPhase` gains `INSTALL`: a fault in the `install` provider, as `install.provider`;
  - `Kotrace.resetForTest` also clears the hook.

  Source- and binary-breaking for a caller passing a hook to `TraceConfig` or `install`, within the 0.5.0 break.
- **Amends:** [ADR-014](adr-014-adapter-fault-isolation.md), which carried the hook on `TraceConfig`;
  [ADR-011](adr-011-strict-uninstalled-optin.md)'s note that a throwing provider is a setup fault no hook hears of.

## Context

The hook lived in `TraceConfig`, so a fault reached it only when a configuration resolved. Several faults happen
exactly when none does, and so reached nothing:

- the `install` provider throwing, which turns telemetry into a silent no-op (ADR-011): the very case ADR-035 set
  out to make visible, at startup instead of in an integration;
- a per-span classifier or `returnedOutcome` throwing in a flow with nothing installed;
- a span failing to open, or an integration step failing, under no configuration.

A per-flow hook also has no known use: a flow overlays a configuration for different sinks, not for different
diagnostics. Its only effect was a precedence rule (a flow's own hook, even a null one, hid the installed one).

## Decision

- The hook is process-wide: `Kotrace.installFaultHook(hook)` sets it once; a second call throws, never a silent
  replace, like `install`. It is independent of `install` and of any overlay, so it can be installed first, at the
  earliest startup point, before a dependency-injection graph exists.
- Every contained fault goes to that one hook, whatever configuration the flow resolves, or none.
- A throwing `install` provider is now reported, as `FaultPhase.INSTALL` with `install.provider`, to a hook installed
  before the provider resolves.
- The strict-not-installed failure is unchanged: it is not a telemetry fault, and it still reaches the traced code
  (ADR-011/034/035).

## Options considered

- **A global hook as a fallback beside the per-flow one: rejected.** Two places to install a hook, a precedence rule
  to learn, and still no use for the per-flow one.
- **Keep the hook in the configuration: rejected.** It leaves the faults above silent.
- **A replaceable hook: rejected.** Install-once matches `install`, and a replaced hook would silently lose the
  faults its predecessor was counting.

## Consequences

- A consumer installs the hook as its first kotrace call; it then hears of every contained fault in the process.
- A flow can no longer route its faults to a hook of its own.
- Tests install the hook through `Kotrace` and rely on `resetForTest` between tests.
