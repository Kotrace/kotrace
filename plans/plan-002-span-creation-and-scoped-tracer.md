# plan-002 — `createSpan` extract + `ScopedTracer` layer factory

Two related ergonomics changes. Scratch — delete when landed. No ADR: neither is contested or
irreversible (a private extract + additive convenience API). If `ScopedTracer` ships in kotrace core it is
public surface — worth a one-line note in ARCHITECTURE, not an ADR.

## Scope decisions (locked)

- **Span construction** — a **private** `createSpan(parent, name, attributes)` helper in `Trace.kt`, shared
  by `trace()` and `startChildSpanHere()`. **Not** the injected `SpanFactory`/`DefaultSpanFactory` design
  (id/clock seam) — rejected as YAGNI, no deterministic-id test drives it. Revisit only if such a test appears.
- **Parent lookup stays split** — `createSpan` takes `parent` as a value. `trace()` resolves it from
  `currentCoroutineContext()[SpanContext]` (authoritative, suspend); `startChildSpanHere()` from
  `currentSpan()` (the ThreadLocal mirror, non-suspend). Unifying these would be wrong-alike, not DRY.
- **`ScopedTracer`** — a factory binding fixed birth `attributes` to `trace`/`startHere`, so a consumer
  opening many spans in one layer stops repeating `mapOf("layer" to "repo")`. Ships the **generic
  mechanism** in kotrace core (no layer names — kotrace holds no taxonomy, ARCHITECTURE §1); the consumer
  instantiates named tracers (`repo`/`uc`/`ds`) in their own module.

## Chunk A — `createSpan` extract (kotrace core, `Trace.kt`)

- Add `private fun createSpan(parent: Span?, name: String, attributes: Map<String, String>): Span` holding
  the single `Span(traceId = parent?.traceId ?: hex(16), spanId = hex(8), parentId = parent?.spanId, …)`
  block (currently duplicated at `Trace.kt:19` and `:57`).
- `trace()` → `createSpan(currentCoroutineContext()[SpanContext]?.span, name, attributes)`.
- `startChildSpanHere()` → `createSpan(currentSpan(), name, attributes)`.
- `hex`/`random`/`System.nanoTime()` stay in `Trace.kt` — no new file, no injection.
- Pure refactor: behaviour identical. Existing `LogTest` / okhttp / room tests cover it; `check` must stay
  green with no test change.

## Chunk B — `ScopedTracer` (kotrace core, new `ScopedTracer.kt`)

```kotlin
class ScopedTracer(private val attributes: Map<String, String>) {
    suspend fun <T> trace(name: String, block: suspend () -> T): T =
        dev.kotrace.trace(name, attributes, block)
    suspend fun <T> trace(name: String, extra: Map<String, String>, block: suspend () -> T): T =
        dev.kotrace.trace(name, attributes + extra, block)   // extra overrides bound on key clash
    fun startHere(name: String): Span = startChildSpanHere(name, attributes)
}
fun tracerFor(vararg attributes: Pair<String, String>) = ScopedTracer(attributes.toMap())
```

- Binds the span's **fixed** filter dimensions (ADR-001) at construction — the correct place for a birth
  attribute.
- Generic: no `Layer` enum, no hardcoded `"layer"` key. Consumer supplies the pairs.
- Tests (new `ScopedTracerTest`): bound attributes land on the opened span's `attributes`; `trace(name,
  extra)` merges with `extra` winning a key clash; `startHere` tags a non-suspend span the same way.

## Chunk C — `TraceOutcome` classifier (kotrace core) — DESIGNED, NOT BUILT

**Why the original C was dropped.** Camailux already has richer per-layer helpers than a bare
`ScopedTracer`: `traceRepo`/`traceDataSource`/`traceResult` over a `Layer` enum, and `@Trace(layer=…)` KSP.
There are **no** raw `trace("…", mapOf("layer" to …))` call sites to migrate. Crucially `traceRepo` is
**Result-aware** — a `Result.Failure` is a returned *value*, not a throw, so it marks the span ERROR and
attaches the throwable itself (ADR-006/023). Plain `ScopedTracer.trace` wraps `trace`, which only reacts to
*throws*, so it cannot back `traceRepo` without losing that. So `ScopedTracer` alone can't unify Camailux's
stack; a value-outcome hook can.

**The hook** (kotrace core, additive + defaulted → backward compatible):

```kotlin
sealed interface TraceOutcome {
    data object Ok : TraceOutcome
    data class Error(val cause: Throwable? = null) : TraceOutcome   // cause != null → birthplace
}

suspend fun <T> trace(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    outcome: (T) -> TraceOutcome = { TraceOutcome.Ok },   // NEW; default = plain trace, today's behaviour
    block: suspend () -> T,
): T
```

- On **normal return**: classify `outcome(result)`. `Error(cause)` → `span.status = ERROR`, and
  `cause != null` → `addException(cause)` (that span becomes the birthplace). A **throw** keeps today's
  path (ERROR + addException). Both coexist.
- `ScopedTracer.trace` gains the same `outcome` param; so does `traceResult`-style usage.
- kotrace stays Result-agnostic: it never names `Result`, only takes `(T) -> TraceOutcome`. No taxonomy leak.
  Value-denoted failure is general (any `Result`/`Either`-returning code), so it earns a place in core.

**Collapses Camailux** (post-release, separate MR): `traceRepo`/`traceResult`/`traceDataSource` become
`ScopedTracer` + one shared outcome lambda —

```kotlin
private val resultOutcome: (Result<*, *>) -> TraceOutcome =
    { r -> if (r is Result.Failure) TraceOutcome.Error(r.error as? Throwable) else TraceOutcome.Ok }
private val repo = tracerFor(Layer.KEY to Layer.Repo.tag)
suspend fun <T, E> traceRepo(name: String, block: suspend () -> Result<T, E>) =
    repo.trace(name, outcome = resultOutcome, block)
```

Layer binding + Result-awareness both compose through kotrace — one mechanism, not a parallel Camailux stack.

**Build sub-steps when greenlit:**
1. `TraceOutcome.kt` (core) + `outcome` param on `trace` (wire the normal-return classify into `Trace.kt`,
   after `createSpan`, before/around the existing try/catch).
2. `outcome` param on `ScopedTracer.trace` (+ merge overload).
3. Tests: a failure *value* marks ERROR; a `cause` becomes the birthplace; `Ok`/default unchanged; a throw
   still works alongside.
4. Short **ADR-002** — "kotrace models value-denoted outcomes, not only throws" (additive stance).
5. Camailux MR (post-release): collapse the trace helpers onto `tracerFor` + `resultOutcome`; delete the
   bespoke wrappers where they add nothing beyond the outcome lambda. Keep `Layer`/`@Trace` KSP.

**Status:** design only (2026-08-18). Not built — awaiting greenlight. Gated on a kotrace release before
the Camailux MR.

## Chunk D — docs

- ARCHITECTURE §5 (Capture verbs table): add `ScopedTracer.trace` / `tracerFor` as the layer-bound entry
  point; one line noting kotrace ships the mechanism, the consumer names layers.
- No README change unless the http example wants a `tracerFor` mention (optional).

## Order & verification

A → B → D in kotrace, each `./gradlew check` green before the next; A is a pure refactor (no test delta),
B adds `ScopedTracerTest`. C is a downstream Camailux MR after a kotrace release — not in this tree.
