# plan-004 — capture-gate removal + open questions (scratch)

Scratch — delete when the decisions land. Captures an in-flight design discussion so it can resume.
The **main open decision is Q1 (remove the capture gate)**; Q2–Q3 are smaller threads surfaced alongside.

## Where we are (landed this session — context, not open)

All in kotrace unless noted; breaking, pre-1.0, consumed by Camailux via `-Pkotrace.local` source
substitution (published artifact is still `0.1.2`).

- **plan-003 option B** — `SpanEvent.reportable()` exhaustive `when` in `Report.kt`, gating report
  membership (`LogEvent`✓ `NamedEvent`✗ `ExceptionEvent`✓). Used **only** in `reportTrace`.
- **Config getter pair** — added `suspend fun currentConfig()` (direct context read); renamed the mirror
  to `currentThreadConfig()`. Now symmetric with `currentSpan()`/`currentThreadSpan()`.
- **`Span.addEvent` → `Span.addNamed`** (pairs with `addException`; every verb adds *an* event, so the
  umbrella word misled). OTel-`addEvent` doc references kept where they mean the OTel method.
- **`SpanCollector.fanOut` → `SpanCollector.reportTrace`** (`fanOut` named the mechanism both live and
  report share; this one is report-only). Camailux `Tracer.kt` updated to call `reportTrace`.
- **Doc:** `onEnd` → `onReport` in ARCHITECTURE.
- **Pending action:** the landed renames are breaking → kotrace needs `version` bump `0.1.2` → `0.2.0`
  (`build.gradle.kts`), publish (`publishAllPublicationsToKotraceMavenRepository`, manual), then Camailux
  `platform-sdk/gradle/libs.versions.toml` `kotrace = "0.2.0"`.

---

## Q1 — remove the capture gate — RESOLVED (option A, ADR-002, 2026-08-21)

Landed: capture gate removed, fan-out is the single filtering authority. `emit` filters before building the
record (message-build win preserved); `TraceConfig.eventAccepted` deleted; `formatTree` now honest; docs +
ADR-001 consequence annotated; storage-gate tests rewritten to stored-but-filtered proofs across root /
kotrace-room. See [ADR-002](../decisions/adr-002-remove-capture-gate.md). Full build + tests green.

**Follow-up — option C (deferred, as requested).** A policy-free per-span event cap (max N events/span,
drop-with-counter) is the right shape *only if* long-lived / loop-heavy traces become in-scope — the
streaming regime kotrace declares it is not for (ARCHITECTURE §1: one call tree per flow, tail). YAGNI now;
revisit when the tail buffer needs backpressure (at which point it bounds **spans** too, not just events).
Recorded in ADR-002 Options as the rejected-for-now C.

<details><summary>Original Q1 analysis (kept for the C revisit)</summary>

### What it is

The one line in `Span.log`:
```kotlin
if (config != null && !config.eventAccepted(this, event)) return
```
`eventAccepted = adapters.any { acceptsSpan(span) && acceptsEvent(event) }` (union of policies), applied
**only** in `log`. `addNamed`/`addException` never call it. It is a **storage + message-build
optimization**, not a reportability check (correcting an earlier mis-statement: the verb never consults
`reportable()`; the two facts — named ungated at capture, named not reportable — are independent hardcodes).

### The two mechanisms it sits between

- **Fan-out (delivery)** — per-adapter `policy.accepts`, at `emit` (live) and `viewOf` (report). **The
  authority.** Consumer's.
- **Capture gate (storage)** — the union above, in `log` only. An optimization *derived from* the same
  policies, pre-applied early. Never pre-empts the authority (union = "store if any adapter wants").

### Options

- **A. Remove it (recommended).** + fix `emit` (below). Uniform verbs, single authority at fan-out,
  honest `formatTree`. Deletes `eventAccepted`, its union-soundness KDoc, and 2 tests.
- **B. Config flag on/off — rejected.** Doesn't resolve the question, hands it to a consumer with *less*
  context; doubles the behavior surface (two paths to test/doc). "Add a toggle to avoid choosing."
- **C. Replace with a policy-free per-span event cap** (max N events/span, drop-with-counter). Only if
  long-lived traces become in-scope — keeps backpressure without policy-in-the-verb. Currently a scope
  expansion (kotrace is tail / one-flow), so YAGNI.

### Why A holds

1. **Message-build perf is NOT lost on removal** — it is recoverable in `emit`, which today eagerly builds
   `recordFor` (resolving the lazy message) *before* checking `accepts`:
   ```kotlin
   internal fun Span.emit(event: SpanEvent, config: TraceConfig?) {
       events += event
       val accepting = config?.liveAdapters?.filter { it.policy.accepts(this, event) }.orEmpty()
       if (accepting.isEmpty()) return
       val record = recordFor(event)          // resolved only when someone wants it
       accepting.forEach { it.onLive(record) }
   }
   ```
   Report path already builds `recordFor` only for accepted events (`viewOf` filters then maps). So **no
   rejected message is ever built**, gate or no gate.
2. **The only real loss — bounded breadcrumb storage on rejected spans — guards an out-of-scope case.**
   ARCHITECTURE §1: "one call tree **per flow**", "**tail**". A flow is finite; event count is bounded by
   it. The pathological case (long-lived / loop-heavy trace logging on a filtered layer) is the streaming
   regime kotrace declares it isn't for — and that already blows the tail-buffer on *spans*, not just
   events.

### What removal changes / costs (from the detailed "what you lose" pass)

- `formatTree` now shows **every** log incl. ones no adapter accepts — becomes honest (it claimed "all
  levels"; the gate had pre-filtered it). Demo tree print grows.
- Per rejected log: allocate `LogEvent` + append + retain its message-lambda captures for the trace
  lifetime (closure retention — the real memory cost, not the ~tiny event).
- **Not** worsened: PII/sensitive posture (sensitive was already stored unconditionally — gate never
  applied the sensitive filter); report output (fan-out `accepts` untouched).
- Deletes: `TraceConfig.eventAccepted` + its ADR-001 union-soundness KDoc; tests `capture gate drops a
  rejected span's log events from storage…` and the storage-side asserts in the acceptsSpan test.

### Next step if A

Write the ADR first (scope-boundary argument + tradeoff on record), then: apply the `emit` fix, delete
the `log` gate line + `eventAccepted`, update `formatTree`/ARCHITECTURE, delete the 2 tests, add/adjust a
test proving a rejected-span log is now stored but still filtered at fan-out.

</details>

---

## Q2 — `formatTree` visibility (open, minor)

`fun List<Span>.formatTree()` is **public** but only called by kotrace's own test + demo. It inlines
`Throwable.message` (PII) — safe for dev Logcat, unsafe if a consumer pipes it to a sink (EH-MON-4). Options:
- Keep public (dev convenience; message-in-Logcat is the same basis `LiveLogAdapter` already accepts).
- `internal` (lock to test/demo; consumers get the tree only via policy-filtered `TraceRecord`s).
- Split: safe public `formatTree()` (no message) + internal `formatTreeVerbose()`.
Not decided.

## Q3 — `reportable()` shape (open musing, low priority)

Currently `internal fun SpanEvent.reportable(): Boolean = when(this){…}` in `Report.kt`. Discussed:
- A `Boolean`-returning func *connotes* a runtime knob; report-membership is a structural invariant.
- Moving it to an **interface member** on `SpanEvent` (each event implements) IS compile-forced too, but
  injects a report-layer concern into the shared domain type, scatters the policy across 3 files, and an
  overridable `Boolean` reads *more* like a knob, not less. Confirmed report-only (single consumer:
  `reportTrace`) → argues for co-locating with the **consumer** (`Report.kt`), not the type.
- **Recommendation held: keep in `Report.kt`.** If the "looks configurable" smell bites, return a type
  (`enum ReportPhase`) instead of `Boolean` — same enforcement, reads as classification. Not the marker
  interface (opt-in, unsafe — plan-003 option C).

## Minor — misleading doc

`Emit.kt` / `TraceException.kt` KDoc says the capture gate is "what the log verbs pre-apply" (reads as
log+named); `addNamed` does **not** apply it. Fix the phrasing — or moot if Q1 removes the gate.
