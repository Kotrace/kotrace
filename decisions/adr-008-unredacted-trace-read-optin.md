# ADR-008 — Gate `renderTree` behind an `@UnredactedTraceRead` opt-in marker

- **Date:** 2026-08-22
- **Status:** Accepted (implemented)
- **Affects:** `dev.kotrace.renderTree` (`TraceFormat.kt`) — gains an opt-in marker;
  `dev.kotrace.UnredactedTraceRead` (new public annotation). Public API — a source-compatible-only change
  for existing callers (each must add `@OptIn`); see Migration.

## Context

kotrace has two trace egress paths, and they sit at opposite ends of the PII spectrum.

- **Machine path** — `reportTrace` / `toJson` (ADR-004). PII-safe *by construction*: each record passes its
  adapter's `TracePolicy`, and a `sensitive` `LogEvent` reaches a sink only when that adapter declares
  `acceptsSensitive`. A `ReportAdapter` bound to a crash reporter never sees a captured body.
- **Human path** — `List<Span>.renderTree(): String`. A deliberately *ungated* debug read: it walks every
  span and every event and renders everything — `sensitive` `LogEvent` messages, `NamedEvent` attributes,
  and the raw `throwable.message` at the birthplace. That is the point: a person reading a trace wants the
  full, unredacted picture; gating it would defeat the purpose and just re-derive `toJson`.

The problem is the boundary between them rests on **one KDoc sentence** — "its output must never reach a
sink or crash report." Nothing in the type system enforces it. A `String` is a `String`; the compiler is
happy to route it anywhere. The realistic failure mode is a single line:

```kotlin
Log.d(TAG, spans.renderTree())   // → Logcat → log pipeline → off device
```

This leaks the same PII the `toJson` path exists to withhold — and *worse*: **all** sensitive messages and
every raw exception string, not just the one field a redaction bug would expose. The boundary that matters
most is guarded the most weakly. This was raised as backlog **D3** and pulled forward for a fix.

## Decision

Add a `@RequiresOptIn` marker and apply it to `renderTree`.

```kotlin
@RequiresOptIn(
    message = "renderTree is an unredacted human debug read — it renders sensitive log messages and raw " +
        "exception text. Never route its output to a sink, logger, or crash report. For machine egress use " +
        "reportTrace / toJson instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class UnredactedTraceRead

@UnredactedTraceRead
fun List<Span>.renderTree(): String
```

Every `renderTree` call now fails to compile until the caller writes `@OptIn(UnredactedTraceRead::class)`.
The legitimate human-read sites — a test dump, the demo's stdout print, interactive debugging — opt in once,
with a comment. An accidental `Log.d(TAG, renderTree())` becomes a build failure pointing at the marker.

This mirrors ADR-003's `@NonSuspendTracingBridge`: same mechanism (`@RequiresOptIn`, `ERROR` level, `BINARY`
retention), same shape — a hazardous-but-legitimate API turned into a deliberate, self-documenting opt-in
that travels with the artifact.

**What the marker does and does not buy.** It does not *prevent* the leak — a caller can `@OptIn` and still
pipe the output to Logcat. It converts a **silent** mistake into a **conscious** one: the accidental ship
stops compiling, and the deliberate human read carries a marker naming the contract. That is the honest
ceiling for a debug-read API — you cannot both hand back the full unredacted picture and mechanically forbid
its misuse. The marker makes the boundary visible and intentional; it does not make the string safe.

## Options considered

- **`@RequiresOptIn` marker (chosen).** Precedent exists twice (`@NonSuspendTracingBridge`, ADR-003).
  Cheap, consistent, travels with the artifact, turns the silent leak into a compile error. Keeps the full
  unredacted read for the humans who legitimately want it.
- **A redacting variant — rejected.** A `renderTree` that strips sensitive fields *is* `toJson` with tree
  indentation; it does not serve the use the ungated read exists for (a person wanting the whole picture).
  Worth adding as a *separate* safe API someday, but it does not repay this debt — it sidesteps it.
- **Debug-build-only guard — rejected.** Impractical for a library. `BuildConfig.DEBUG` belongs to the
  consumer's app module; a JVM/Android library cannot read the consumer's debug flag without the consumer
  threading it in. The guard would live in the wrong place and be trivially bypassed.
- **Runtime / lint detection — rejected as primary.** No runtime signal distinguishes a legitimate human
  read from an about-to-ship one; both are just a `String` return. A downstream Konsist/lint rule ("`renderTree`
  result must not flow into a logger") could be layered on as a hard gate, but it lives in the wrong repo and
  is brittle across the module boundary — same reasoning ADR-003 gave for preferring the marker on the API.
- **Do nothing / defer to first incident — rejected.** The trigger in D3 was "new sink added or a consumer
  found routing off-device," but the marker is cheapest *now*, while every caller is in-repo (one test, the
  demo). Once an external consumer calls the unmarked `renderTree`, adding an `ERROR`-level opt-in becomes a
  breaking change to them. A safety boundary is worth locking before it is loaded — as ADR-003 locked
  `startSpan` proactively, not after a misparenting bug shipped.

## Consequences

- **The PII boundary is now enforced by the compiler, not a doc sentence.** An un-opted `renderTree` call
  does not compile. The silent `Log.d(TAG, renderTree())` leak cannot reach a build without a visible,
  commented `@OptIn`.
- **Symmetry with the machine path is explicit.** `toJson` is PII-safe by construction; `renderTree` now
  *announces* that it is not, at every call site. The two egress paths no longer look interchangeable.
- **Cost is one `@OptIn` per legitimate site.** Two today: `TraceTreeTest` (test dump) and the demo `main`
  (stdout). Both carry a rationale comment.
- **Honest limits, documented.** The marker's KDoc states that opting in does not make the output safe to
  route off-device — it only makes the human-only contract a conscious choice. No false sense of a leak-proof
  API.
- **Source-compatible-only for the sole consumer.** kotrace is built from source (`includeBuild`), so the
  marker lands atomically: every existing `renderTree` site gains `@OptIn` in the same change, nothing
  straddles a published boundary.

## Migration

Applied in one change (kotrace is consumed from source):

1. Add `annotation class UnredactedTraceRead` (`UnredactedTraceRead.kt`); annotate `renderTree`
   (`TraceFormat.kt`).
2. Add `@OptIn(UnredactedTraceRead::class)` with a comment at each human-read site — `TraceTreeTest`, the
   demo `main`, and any Camailux debug read that calls `renderTree`.
3. No behavior change: annotations only. `compileKotlin` / `compileTestKotlin` / `:demo:compileKotlin` and
   the test suite all pass unchanged.
