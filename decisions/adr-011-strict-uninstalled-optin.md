# ADR-011: `strictWhenNotInstalled`, an opt-in fail-fast for emit-before-install

- **Date:** 2026-09-07
- **Status:** Accepted
- **Amended:** 2026-10-08: renamed from `strictWhenUninstalled`, which read as "removed after
  install". kotrace has no uninstall; the latch fires when nothing was ever installed.
- **Affects:** new `Kotrace.strictWhenNotInstalled()` arm + `AtomicBoolean` latch, `resolvedThreadConfig()`
  (throws under the armed latch when it resolves to no configuration), `Kotrace.resetForTest` (also clears the
  latch), `ARCHITECTURE.md` §fan-out configuration
- **Builds on:** [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md) (install-once process-wide configuration;
  null configuration = safe no-op), [ADR-002](adr-002-remove-capture-gate.md) (fan-out is the single authority)

## Context

ADR-010 made a null fan-out configuration a **safe no-op**: with nothing installed and no per-flow override, every
emit and every `reportTrace` silently reaches nowhere. That is the right *release* default: telemetry must
never crash the process it observes, and a consumer that deliberately runs no sinks is legitimate.

But the same silence hides two real wiring bugs, and hides them exactly where they cost the most, in
development, where the fix is cheap:

1. **`install` was never called.** A consumer forgets the startup wiring entirely. Every emit no-ops
   forever; nothing reaches Crashlytics/Amplitude/etc. The failure is invisible until someone notices an
   empty dashboard days later.
2. **An emit ran before `install`.** Ordering regression: a log or a traced operation fires during early
   startup, before the dependency-injection graph resolves and `install` runs. That one record is dropped silently, then the
   configuration installs and everything after it works, so the gap never reproduces under a debugger.

Both are "resolved configuration is null when the developer intended it not to be." Today kotrace cannot tell that
apart from "configuration is null because the consumer chose no sinks." We want to surface (1) and (2) loudly in
debug **without** overturning ADR-010's fail-closed release contract, and without making a monitoring call a
crash risk in production.

## Decision

**Add an opt-in, process-wide `strict` latch that turns a null-configuration resolution into a hard error, armed
separately, before `install`, and off by default.**

- **`Kotrace.strictWhenNotInstalled()`** arms a process-wide `AtomicBoolean` (idempotent; unlike `install`,
  re-arming is a no-op, not an error). It is **not** a parameter of `install`: to catch bugs (1) and (2) the
  latch must already be armed *before* `install` runs (bug 2) and even when `install` never runs at all (bug
  1). Arming inside `install` would be too late to catch either. So it is its own call, made at the earliest
  startup point.
- **`resolvedThreadConfig()` throws under the armed latch when it would return null**, meaning no per-flow
  `TraceConfig` override *and* no installed global. `IllegalStateException` with a directive message:
  install at startup, or `install(emptyList())` to disable deliberately. All fan-out sites
  (`emitLog`/`emitNamed`/`emitException`, the span-scoped `log`/`addNamed`/`addException`, and `reportTrace`)
  resolve through this one function, so the check lives in exactly one place and covers every path.
- **"Disabled telemetry" becomes `install(emptyList())`, not "never install."** Under strict, an installed
  empty-adapter configuration is non-null → no throw → a real, intended no-op. Never calling `install` now means
  "the developer forgot," which is precisely what strict flags. This distinction is the whole point: it
  separates *deliberately off* from *accidentally off*.
- **kotrace stays build-agnostic; the consumer decides when to arm.** kotrace has no `BuildConfig`. The
  consumer calls `strictWhenNotInstalled()` only in its debug/dev build (Camailux: the debug flavor's startup,
  before `installProcessConfig`); release never arms it, so release keeps ADR-010's silent no-op unchanged.
- **`resetForTest()` clears the latch too**, so a suite that arms strict in one case cannot leak it into the
  next.

## Consequences

- Bugs (1) and (2) fail fast, at the first emit, in the build where they are cheap to fix, instead of
  surfacing as an empty dashboard later. The stack trace points straight at the unwired emit.
- **Release behavior is unchanged.** With the latch never armed, `resolvedThreadConfig()` still returns null
  and every path is the ADR-010 safe no-op. No monitoring call can crash production because of this ADR.
- Strict mode costs **two** startup calls (`strictWhenNotInstalled()` then `install`), and the arm must
  precede the first emit. This is the price of catching emissions that occur before installation; it is debug-only, and one obvious
  line in `Application.onCreate`.
- The meaning of "never installed" is now **mode-dependent**: a bug under strict, a valid no-op otherwise.
  Acceptable because strict is opt-in and the disabled-on-purpose path has an explicit, documented spelling
  (`install(emptyList())`).
- One more bit of process-global mutable state (the latch) in an otherwise context-scoped library, with the same
  justification as ADR-010's global configuration: static, process-wide, published once at startup, thread-safe.

## Rejected

- **Throw unconditionally on a null configuration** (drop the no-op entirely): overturns ADR-010's fail-closed
  release contract, makes every monitoring call a potential crash in production, and removes the legitimate
  "no sinks" mode. A telemetry library must not take down the process it observes. Rejected: strict must be
  opt-in and off in release.
- **Carry `strict` on `install` (a parameter or a `TraceConfig` policy).** Armed at install time, it catches
  neither target bug: if `install` never runs the flag is never set (misses bug 1), and an emit *before*
  `install` runs before the flag is set (misses bug 2). The check can only ever fire *after* a successful
  install, but the install-once configuration is never cleared, so a post-install resolution is never null. It
  would be a check that can never trigger. Rejected for a separately armed latch.
- **A one-time loud log instead of a throw** (warn on the first null resolution): softer, but a warning in a
  noisy logcat is the same silence one step removed: easy to miss, and it does not stop a test from
  "passing" while emitting nothing. Fail-fast in debug is the stronger signal. (A consumer can still layer a
  warn-only adapter if it wants that; kotrace's opt-in is the hard stop.)
- **Make `install` mandatory / non-optional** (no null-configuration state at all): breaks every consumer that runs
  kotrace with no sinks, and forces a global installation even in unit tests that never fan out. ADR-010 keeps
  install optional on purpose; strict re-adds the guarantee only where a consumer asks for it.

---

[← All decisions](../DECISIONS.md)
