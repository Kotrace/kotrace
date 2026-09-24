# ADR-018 — An ambient `failureDetector`: treat a *returned* failure value like a thrown one (span-level ERROR + birthplace), configurable per-process / per-call

- **Date:** 2026-09-19
- **Status:** Accepted
- **Amended:** 2026-09-21 — the root detector result now travels in an internal `SpanCompletion<T>` rather
  than a mutable field on `Span`; behavior and precedence are unchanged. **Superseded in callback shape and
  terminology by [ADR-020](adr-020-value-only-failure-classifier.md) on 2026-09-22:** the current API is
  `FailureClassifier = (Any?) -> ReturnedFailure?`, with `ValueOnly` and `CausedBy(Throwable)` results;
  ADR-018's ambient placement, fault isolation, and verdict precedence still stand.
- **Affects:** adds an optional process-wide `failureDetector: (Any?) -> Throwable?` to `Kotrace.install(...)`
  ([`Kotrace.kt:56`](../src/main/kotlin/dev/kotrace/Kotrace.kt:56)) — a separate field, **not** on `TraceConfig`
  (§ Config home); adds a **defaulted per-call override** `failureDetector: (T) -> Throwable?` to `span(...)`
  ([`Trace.kt`](../src/main/kotlin/dev/kotrace/Trace.kt)); runs the resolved detector on the **normal
  return** of **every** span, so a returned failure gets the same `markStatus(ERROR)` +
  `recordPropagatedException(throwable)` the `catch` gives a thrown one. Adds
  `FaultPhase.FAILURE_DETECTOR` (ADR-014); the root's result is returned through a private
  `SpanCompletion<T>` and never stored on `Span` or any `TraceRecord`. At the **auto-root**, when the root
  detector fires and no explicit
  `returnedOutcome` was supplied, the default trace verdict is `CANCELLED` if the detected throwable is a
  `CancellationException`, else `ERROR` (a **precedence chain**, not a second authority — § How `failureDetector`
  relates to `TraceStatus`). **No change** to `TraceConfig`, `TraceOutcome`, `reportTrace`, the report/live
  fan-out, or the non-suspend bridge `startSpan`/`end` (§ The non-suspend bridge).
- **Builds on:** [ADR-015](adr-015-exception-origin-token.md) (the lineage key makes a returned failure's
  *climb* collapse to one birthplace), [ADR-016](adr-016-auto-root-returned-outcome.md) /
  [ADR-017](adr-017-merge-span-overloads-default-returned-outcome.md) (`returnedOutcome` and the
  `alwaysOkOutcome` sentinel — reused for the verdict precedence), [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md)
  (`Kotrace` as the install-once process home for non-per-flow config), [ADR-002](adr-002-remove-capture-gate.md)
  (append is unconditional; filtering is a fan-out concern), [ADR-014](adr-014-adapter-fault-isolation.md)
  (fault containment), [ADR-011](adr-011-strict-uninstalled-optin.md) (strict-uninstalled — deliberately
  side-stepped on the detection path, § Fault isolation).
- **Amends:** [ADR-016](adr-016-auto-root-returned-outcome.md) — ADR-016 made `returnedOutcome` the sole,
  *required-to-be-explicit* source of a returned failure's trace verdict, and ADR-017 defaulted it to
  `alwaysOkOutcome`. This ADR (1) adds span-level ERROR-marking + birthplace recording for a returned failure
  at **every** span, and (2) lets the **root** detector supply the **default** trace verdict (`ERROR`) when
  `returnedOutcome` is left at its default — closing the silent-drop footgun where a root-returned failure was
  recorded on the span yet reported under `TraceStatus.OK`. An explicit `returnedOutcome` still wins.

## Context

kotrace catches a **thrown** failure structurally: every `span { }` wraps its block in a `try/catch` that, on
the way up, marks its span `ERROR` and records the throwable via the propagation recorder, then rethrows
([`Trace.kt:75-93`](../src/main/kotlin/dev/kotrace/Trace.kt:75)). The rethrow carries the failure to each
enclosing span, so the whole failing path is marked and — by ADR-015's lineage key — the report collapses the
climb to a single birthplace.

A **failure-as-value** consumer never throws. It catches at a boundary and *returns* the failure — the
canonical Kotlin shape being `Result.failure(e)`. Today kotrace is blind to this: the block returns
**normally**, the `catch` never fires, `markStatus`/`recordPropagatedException` never run, the span stays `OK`,
the failure is absent from the tree. ADR-016/017 gave the **auto-root** a `returnedOutcome` mapper that turns
the *root's* returned value into a `TraceStatus` — but that is **root-only**, **trace-level**, and (after
ADR-017) **defaults to always-OK**: it does not mark the span where the failure was produced, and a consumer
who forgets to set it gets `OK`.

So a failure-as-value consumer wanting an accurate tree must call `currentSpan()?.addException(e)` **by hand**
at each layer — the manual step ADR-002 removed for logs and the `catch` removed for throws — and (because the
public `addException` mints a *fresh* lineage key, [`TraceException.kt:164`](../src/main/kotlin/dev/kotrace/event/TraceException.kt:164))
those hand copies do **not** dedup the climb the way the internal propagation recorder does.

The ask: a **configured** rule — "what counts as a failure worth recording" — applied automatically,
defaulting to "a thrown exception" and letting a consumer add "a returned `Result.failure`" without
hand-writing `addException`.

### Why it must be *ambient* (every span), not a per-call-only knob

A thrown failure's climb is structural because the rethrow re-enters **every** enclosing `span`'s catch. A
returned failure has **no rethrow**: it propagates only because each enclosing layer *also returns the same
value*. So the climb is recreated only if the detector runs at **each** layer. If only the outermost span ran
a detector, it would become the birthplace even though the failure was produced deep below. Structural
per-span coverage (matching the catch) requires the detector to be **ambient**: resolved from process config
and run on every span's normal return. The per-call parameter is an *override* on top, not the primary carrier.

## Decision

**Add a process-wide `failureDetector: (Any?) -> Throwable?` (installed on `Kotrace`) that kotrace runs on the
normal return of every span. A non-null result is treated exactly as a thrown throwable at that span:
`markStatus(ERROR)` + `recordPropagatedException(throwable)`. `null` means "not a failure". A per-call
`span(failureDetector = …)` overrides it for one span. At the auto-root, if the root detector fires and no
explicit `returnedOutcome` was given, the default trace verdict is `CANCELLED` when the detected throwable is a
`CancellationException`, otherwise `ERROR`.**

### The callback shape

```kotlin
// A returned value → the throwable that makes it a failure, or null when it is not a failure.
// null is the "not a failure" signal (most returns are successes); maps 1:1 onto Result.exceptionOrNull().
typealias FailureDetector = (Any?) -> Throwable?
```

- **`Throwable?`, not `Throwable`.** The `?` *is* the "is this a failure?" decision. The detector runs on every
  normal return, most of which succeed, so it must be able to say "no" (`null`). It maps exactly onto
  `Result.exceptionOrNull(): Throwable?`. A non-null result must be a real `Throwable` because everything
  downstream (`ExceptionRecord.throwable` for the crash reporter, `lineageKeyOf` for dedup) is built on
  `Throwable`. A value-only domain failure with no throwable (`Either.Left(DomainError)`) is out of scope for
  *this* mechanism (backlog D03).
- **The returned throwable must be a *stable* object carried by the value — not synthesized per call.** Dedup
  (ADR-015) keys a normal throwable by **identity** ([`TraceException.kt:78`](../src/main/kotlin/dev/kotrace/event/TraceException.kt:78)).
  `Result.exceptionOrNull()` returns the *same* `e` at every layer, so a returned `Result.failure(e)` climbing
  the tree collapses to one birthplace. A detector that **synthesizes** a fresh `Throwable` on each invocation
  (or an ambient and a per-call detector that extract *different* objects for the same value) yields distinct
  lineages → one birthplace per layer. This is a **contract on the detector**, stated in its KDoc: return the
  throwable the value already carries.
- **A returned `CancellationException` mirrors a thrown one (option D).** kotrace records it and marks the span
  `ERROR` like any detected throwable — no special-casing at the record site, because a *thrown*
  `CancellationException` is also recorded and marks the span `ERROR` ([`Trace.kt:75`](../src/main/kotlin/dev/kotrace/Trace.kt:75)).
  The *only* cancellation-specific step is the **trace verdict**: when the throwable the **root** detected is a
  `CancellationException`, the default verdict is `CANCELLED`, not `ERROR` — reusing the exact rule the
  auto-root already applies to a thrown escaping `CancellationException` ([`Trace.kt:159`](../src/main/kotlin/dev/kotrace/Trace.kt:159)).
  Net, identical to thrown: the cancellation is in the tree (a log adapter sees it), the verdict is `CANCELLED`,
  and an `ERROR`-gated crash adapter skips it. This heals the common `runCatching`-swallows-`CancellationException`
  hazard — the trace reports `CANCELLED` as if the cancellation had not been swallowed into a value. (A
  `CancellationException` *thrown by the detector itself* is a different thing — a config fault, contained; §
  Fault isolation.)
- **Replace, not compose.** The resolved detector for a span is a single function; a per-call override
  *replaces* the ambient one, it does not run alongside it. Composition would reopen "which throwable wins" —
  the ambiguity kotrace avoids (cf. ADR-004 single-authority style).

### Config home — process-global on `Kotrace`, **not** per-flow on `TraceConfig`

`failureDetector` is installed once on `Kotrace`, alongside the adapters, and read directly — it is **not** a
field on `TraceConfig`:

```kotlin
object Kotrace {
    // ONE immutable holder published through the existing install-once AtomicReference latch — never two
    // separate writes, so config and detector can never be observed in a torn state, and a failed second
    // install mutates neither. resetForTest clears this same holder.
    private class Installed(val config: Lazy<TraceConfig>, val failureDetector: FailureDetector?)
    private val installed = AtomicReference<Installed?>(null)

    fun install(
        adapters: List<TraceAdapter>,
        faultHook: AdapterFaultHook? = null,
        failureDetector: FailureDetector? = null,        // NEW — process-wide classification
    ) { /* compareAndSet(null, Installed(lazyOf(TraceConfig(adapters, faultHook)), failureDetector)) */ }

    // provider overloads (ADR-010) gain the same trailing defaulted parameter, into the same holder:
    fun install(faultHook: AdapterFaultHook? = null, failureDetector: FailureDetector? = null,
                provider: () -> List<TraceAdapter>) { /* … Installed(lazy { … provider() … }, failureDetector) */ }

    internal fun failureDetector(): FailureDetector? = installed.get()?.failureDetector
}
```

Resolution at a span (no `TraceConfig`, no `resolvedThreadConfig()` involved):

```kotlin
// One sentinel, nullable resolved detector (simpler than a second no-op singleton — Codex review):
val detector: ((T) -> Throwable?)? =
    if (failureDetector === InheritAmbient) Kotrace.failureDetector() else failureDetector
// on the normal-return path: `if (detector == null) return value` — unconfigured ⇒ no invocation at all.
```

Three reasons it lives on `Kotrace`, not `TraceConfig` — each a concrete defect avoided:

1. **No per-flow coupling footgun.** `TraceConfig` overrides *replace* (they do not merge). A consumer overlaying
   a per-flow `TraceConfig(adapters = …)` to change **sinks** — a real, documented use (ARCHITECTURE §3-4) —
   would, if the detector lived there, silently **reset the detector to null** for that flow. Classification
   ("what is a failure in this app") is process-stable; a per-flow *definition of failure* is speculative
   (per-call covers the local exceptions). Coupling the speculative thing to the real one, where the real one
   silently disables it, is the wrong trade.
2. **No early lazy-provider resolution.** Were the detector inside the lazy `TraceConfig`, resolving it on the
   first normal span return would force `Kotrace.defaultConfig()` and thus the lazy adapter provider
   ([`Kotrace.kt:64`](../src/main/kotlin/dev/kotrace/Kotrace.kt:64)) **before** first fan-out. A separate field
   is a cheap read that does not touch the provider.
3. **No `ThreadContextElement` machinery needed.** `TraceConfig` is a `ThreadContextElement` only so the
   non-suspend bridge can read it via a ThreadLocal mirror. The detector runs on `span` (suspend) only — the
   bridge does not use it (§ The non-suspend bridge) — so it needs no mirror.

`null` default ⇒ **no returned-value detection**, so an unconfigured consumer sees today's behavior exactly.
Turning it on is one line:

```kotlin
Kotrace.install(
    adapters = listOf(crashAdapter, logAdapter),
    failureDetector = { (it as? Result<*>)?.exceptionOrNull() },
)
```

### Per-call override — a defaulted parameter with a sentinel (the ADR-017 pattern)

```kotlin
// Identity-checked sentinel. Non-capturing → JVM singleton (mirrors ADR-017's `alwaysOkOutcome`).
// Contravariance lets an (Any?)->… value stand in for the (T)->… parameter at every T — no cast needed.
private val InheritAmbient: (Any?) -> Throwable? = { null }   // "use the process detector"; resolves to a
                                                              // nullable detector (null ⇒ no-op, no second sentinel)

suspend fun <T> span(
    name: String,
    attributes: Map<String, String> = emptyMap(),
    links: List<TraceLink> = emptyList(),
    returnedOutcome: (T) -> TraceOutcome = alwaysOkOutcome,     // ADR-016/017
    failureDetector: (T) -> Throwable? = InheritAmbient,        // NEW: sentinel ⇒ inherit process detector
    block: suspend () -> T,
): T
```

| Per-call argument | Meaning |
|---|---|
| *(omitted)* → `InheritAmbient` | use the process detector (`Kotrace.failureDetector()`), else no-op |
| `failureDetector = { … }` | **override** for this span, call-site-typed `(T) -> Throwable?` |
| `failureDetector = { null }` | **local opt-out** — this span does not treat its own return as a failure |

**The opt-out is *local only* — it does not stop an ancestor's climb.** This is a correction over an earlier
draft. `{ null }` suppresses detection **on this span alone**. If the span still *returns* an unchanged
`Result.failure(e)`, every ambient ancestor detects `e` again — you would get `ERROR → (opted-out span) →
ERROR`, a hole in the path, not a stopped climb. A thrown failure stops climbing because a `catch` **consumes
or transforms** it; a no-op detector does neither. **The real recover boundary is returning a
success/transformed value** (a `Result.success`, or a `Result.failure(differentError)`): then the detector at
that span does not fire, and ancestors see a non-failure (or a *new* lineage). Use per-call opt-out only for a
span whose returned failure is genuinely not a failure *and* is not forwarded up as-is.

### Where it runs — every span's normal-return path (root included, via one execution path)

`executeSpan` captures the return value and runs the detector before returning; `catch`/`finally` are unchanged.
Both ordinary spans and the auto-root's root use this helper, so detection happens on one path, exactly once:

```kotlin
return try {
    val value = withContext(SpanContext(opened)) { block() }
    val detectedFailure = detector?.let { detectReturnedFailure(opened, it, value) }
    SpanCompletion(value, detectedFailure)
} catch (t: Throwable) {
    opened.markStatus(SpanStatus.ERROR)
    try { opened.recordPropagatedException(t) } catch (rf: Throwable) { if (rf.isFatalFault()) throw rf; t.alsoSuppress(rf) }
    throw t
} finally {
    opened.markEnd(System.nanoTime())
}

private fun <T> detectReturnedFailure(span: Span, detector: (T) -> Throwable?, value: T): Throwable? {
    val failure = guardDetector(::quietFaultHook) { detector(value) } ?: return null
    span.markStatus(SpanStatus.ERROR)                               // uniform: any detected throwable, incl. Cancellation
    try { span.recordPropagatedException(failure) }                 // recorded like a thrown one (option D)
    catch (rf: Throwable) { if (rf.isFatalFault()) throw rf /* else contain: never fail a returning op to record */ }
    return failure
}
```

`SpanCompletion<T>` is the **authoritative** result of "this span's detector fired, with this throwable". It
replaces both the earlier `root.status == ERROR` proxy and the first implementation's mutable
`Span.detectedFailure` side channel: `status` is a general-purpose field a bridge `end(ERROR)` or a future
writer could set without a detector firing, while completion metadata belongs to execution control flow rather
than the persistent trace model. The envelope is private and never crosses to an adapter.

**Auto-root** — executes the root through the same helper, then derives the **trace verdict** by precedence
(§ next):

```kotlin
// inside autoRootSpan(name, attributes, links, returnedOutcome, failureDetector, block):
val completion = executeSpan(
    parent = null,
    collector = collector,
    name = name,
    attributes = attributes,
    links = links,
    scopeId = scopeId,
    failureDetector = failureDetector,
    block = block,
)
val value = completion.value
val detected = completion.detectedFailure
val outcome = when {
    returnedOutcome !== alwaysOkOutcome     -> runReturnedOutcome(returnedOutcome, value)   // explicit wins (+ attached)
    detected is CancellationException       -> TraceOutcome(TraceStatus.CANCELLED)          // option D: returned cancellation
    detected != null                        -> TraceOutcome(TraceStatus.ERROR)              // root detector fired
    else                                    -> TraceOutcome(TraceStatus.OK)
}
reportAutoRoot(collector, outcome.status, escaped = null, attached = outcome.attached)
// escaping-throwable branch unchanged: core owns CANCELLED/ERROR, neither detector nor returnedOutcome consulted.
```

The verdict keys on the root's completion result, not on `root.status`, so it is unambiguous even though a
detected `CancellationException` also marks the root span `ERROR`: `status` is the span's, completion metadata
is the verdict's input.

### The non-suspend bridge (`startSpan` / `end`) is deliberately unchanged

`startSpan` / `Span.end` ([`Trace.kt:216`](../src/main/kotlin/dev/kotrace/Trace.kt:216)) get **no**
`failureDetector` — structurally, not by omission. The bridge wraps **no block**: `startSpan` opens a span and
returns it; the caller closes it with `end(status, error)`, **stating the outcome explicitly**. There is no
returned value for a detector to classify — the explicit `end(status, error)` arguments *are* the bridge's
manual equivalent, the `@NonSuspendTracingBridge` contract (ADR-003). Origin marking (ADR-019) still applies
for free: `end(error = e)` records through the same `recordPropagatedException`
([`Trace.kt:226`](../src/main/kotlin/dev/kotrace/Trace.kt:226)), so a bridge-recorded exception carries the
canonical lineage key and is classified identically.

### Fault isolation (ADR-014 / ADR-011)

A consumer detector must never turn a **succeeding** operation into a failure. `guardDetector` contains a
non-fatal detector **throw** — including a `CancellationException` *thrown by the detector* — treating it as
`null` (not a failure). (Note the contrast with the callback-shape rule: a detector that **returns** a
`CancellationException` as a value *is* a detected failure, recorded and defaulting the verdict to `CANCELLED`
(option D); a detector that **throws** one is a config fault, contained. Returning ≠ throwing.) Containing a
detector-thrown `CancellationException` is deliberate: were it left to propagate, the enclosing span's `catch`
would mark the span `ERROR` and the auto-root would report `CANCELLED` — telemetry converting a normally
completed operation into cancellation, which ADR-016 already forbids for mapper faults. Only a JVM-fatal fault
propagates.

**Fault-hook ownership** (detector lives on `Kotrace`, not on the per-flow `TraceConfig`). A detector fault
routes by the **same whole-config precedence** `resolvedThreadConfig()` uses — a present per-flow config wins
*as a whole*, else the process config — resolved **lazily, only on a fault**:

```kotlin
val hook = (currentThreadConfig() ?: Kotrace.defaultConfig())?.faultHook   // non-throwing; config exists once a detector fired
```

This matters at the null case, and is deliberately **not** `currentThreadConfig()?.faultHook ?: processHook`
(Codex review): a flow that overlays its own `TraceConfig` with `faultHook = null` **swallows** the fault
(its config wins whole, and it chose no hook) — it does not silently inherit the process hook. That is exactly
how every other fault in that flow is routed (ADR-010/014), so detector faults are consistent with the rest.
The routing uses `FaultPhase.FAILURE_DETECTOR`, `adapter == null` (a consumer-config fault, like ADR-016's
`RETURNED_OUTCOME`).

**Strict-uninstalled (ADR-011) does not reach the detection path.** Detector *resolution* reads
`Kotrace.failureDetector()` directly (not `resolvedThreadConfig()`), so it never triggers the strict latch; if
nothing is installed the detector is `null` and detection is a no-op. The *recording* of a detected failure
goes through `recordPropagatedException` → `emit`, which does resolve config; under strict-uninstalled that can
throw, but `detectReturnedFailure` **contains** that non-fatal fault (best-effort), because on a normal return
there is no escaping throwable to attach it to as suppressed, and converting a returning operation into a throw
would be worse than a missed strict signal. Strict's fail-fast therefore remains enforced on the primary
emit/report paths, not on returned-failure recording — stated explicitly so the boundary is known.

## How `failureDetector` relates to `TraceStatus` — a precedence chain, not a second authority

`SpanStatus` and `TraceStatus` answer **different questions on different inputs**: `SpanStatus` = "did *this*
operation fail?" on the span's **own** returned value; `TraceStatus` = "did the operation, **as a whole**,
end in failure?" on the **root's final** returned value. `failureDetector` marks span status on every span.
The trace verdict is resolved once, at the auto-root, by a **precedence chain**:

```
1. escaping throwable                    → core-owned CANCELLED / ERROR   (detector & returnedOutcome not consulted)
2. explicit returnedOutcome              → its mapped status (+ attached)  (returnedOutcome !== alwaysOkOutcome)
3. root detected a CancellationException → CANCELLED                       (returnedOutcome left default — option D)
4. root detected any other throwable     → ERROR                          (returnedOutcome left default)
5. otherwise                             → OK
```

This is **one authority per step, in order** — not two authorities competing. Step 2 (an explicit
`returnedOutcome`) always wins over steps 3–4, so a consumer who wants a root-returned failure to still be `OK`
(e.g. `Result.failure(NotFound)` is a fine outcome) sets `returnedOutcome` explicitly, or opts the root out
per-call. Steps 3–4 make `failureDetector` at the root the **default** verdict source when the consumer has
declared what a failure is but not overridden the verdict — a returned `CancellationException` defaulting to
`CANCELLED` (step 3) exactly as a thrown one does, everything else to `ERROR` (step 4).

**Why steps 3–4 are correct and do not break recovery.** A returned failure that reaches the root *is* the
operation's final outcome — a failed one — so defaulting the verdict to `ERROR` is right, and it is exactly
what closes the footgun: without it, `span { return Result.failure(e) }` records `e` on the root span yet
reports `TraceStatus.OK`, and a failure-only report adapter (`if (status == OK) return`) drops the crash
entirely. Recovery is unaffected because a recovered root **returns success**:

```
dataSource: return Result.failure(e)                        // detector fires → dataSource span ERROR + birthplace(e)
repo:       if (dataSource().isFailure) return Result.success(fallback())   // recovered → returns SUCCESS
root:       return repo()                                   // returns SUCCESS → root detector does NOT fire
```

Here `dataSource` span = ERROR (a genuine recorded failure), `repo`/`root` = OK, and the trace verdict = OK
(step 5). A deep span failing never forces the trace `ERROR`; only the **root's own** returned value does. This
preserves the §3 "failed here but handled" ≠ "escaped" distinction: the detector fires per span on each span's
own value, and the verdict keys solely on the root's.

The earlier draft of this ADR rejected steps 3–4 to keep the detector strictly out of `TraceStatus`. Codex
review surfaced the silent-drop footgun that rejection creates; on reflection, a *precedence chain* (explicit
`returnedOutcome` > root-detector default > OK) is not the "two competing authorities" the rejection feared —
it is an ordered fallback with a single winner at each step — and it is the more coherent model: one
declaration of "what a failure is" (`failureDetector`), reused for the default verdict, with `returnedOutcome`
reserved for overrides (force `OK`, remap a status, attach orphans).

**Dedup caution (unchanged from ADR-012/016).** If the root detector records the throwable, do **not** also
place that same throwable in an explicit `returnedOutcome`'s `TraceOutcome.attached` — it would emit twice
(tree + post-walk orphan channel, [`TraceOutcome.kt:15`](../src/main/kotlin/dev/kotrace/TraceOutcome.kt:15)).
`attached` stays for genuine orphans no span returns (saga rollback throwables).

### "Birthplace" here means the deepest span that *observed* the failure on return

A detector sees values at span **return boundaries**, so the birthplace is the deepest span that *returned*
the failure — not provably where it was produced. A pre-existing failure passed **into** a span and returned
unchanged makes that span the birthplace even though it created nothing; and per ADR-015's ancestry-scoped
dedup, the same failure returned from **parallel sibling** spans yields one birthplace *per sibling branch*,
not one globally. This matches the thrown model (a throwable's birthplace is the deepest span its rethrow
passed through) and is the honest guarantee: *deepest observed return boundary*, not *production site*. A
consumer needing the true production origin marks it explicitly at the producer.

## Source compatibility

- **`Kotrace.install(...)`** is consolidated from **four** explicit overloads to **two** defaulted ones —
  `install(adapters, faultHook = null, failureDetector = null)` and `install(faultHook = null,
  failureDetector = null, provider)`. **Source break, narrow and swept.** The trailing-lambda and
  named/positional-list forms are unaffected: `install(list)`, `install(list, hook)`, `install { … }`,
  `install(hook) { … }` all resolve to the two survivors. What breaks is a **positional provider** call —
  `install(providerVal)` or `install(hookVal, providerVal)` where the provider is passed as a *positional
  argument variable*, not a trailing lambda — because the provider slot now follows two defaulted parameters
  it cannot skip positionally. A sweep of `src`, `demo`, `benchmarks`, the integration modules, and the
  reference consumer found **no** call sites of that shape (all installs are `install(list[, hook])` or a
  trailing-lambda provider) — but an unknown external consumer of the positional-provider form remains
  possible, so this is documented as a real break, not claimed away. The compatibility stated here is
  **Kotlin-source** compatibility: a **Java** caller has no default arguments, so a recompiled Java caller of
  the former one-/two-argument *list* overloads breaks too (those JVM methods disappear → `NoSuchMethodError`),
  even though the Kotlin call sites do not — the same class of release break ADR-017 accepted for `span`'s
  positional form. Restoring the old shapes as extra forwarding overloads is possible if an external
  positional-provider (or Java) caller ever needs it.
- **`TraceConfig` is unchanged** — the detector does not live there (§ Config home).
- **`span(...)`** gains a **defaulted** `failureDetector` **before** `block`. A trailing lambda still binds
  `block` and Kotlin skips the defaulted parameters before it, so `span(name) { }`, `span(name, attrs, links)
  { }`, and `span(name, returnedOutcome = m) { }` resolve unchanged; the failure-as-value call passes
  `failureDetector = { … }` by name. **Binary:** `span`'s JVM signature changes (an added `Function1`
  parameter + a new `$default` mask), so a pre-compiled caller gets `NoSuchMethodError` — the same released
  break class ADR-017 documented; both land in one version bump.
- **Behavior:** with `failureDetector` left `null` and no per-call override, **nothing changes** — thrown
  failures caught as today, returned failures invisible, verdict from `returnedOutcome`/core as today. Purely
  additive opt-in.
- Pre-1.0: bundle with the next minor after ADR-017's cut.

## Options considered

- **Process-wide detector on `Kotrace` + defaulted per-call override, root-detector supplies the default
  verdict by precedence (chosen).** Structural per-span coverage matching the catch; reuses the ADR-015 lineage
  recorder (returned-failure climb dedups to one birthplace, no new dedup code); no per-flow coupling, no early
  lazy-provider resolution, no `ThreadContextElement` need (§ Config home); closes the silent-drop footgun via
  the precedence chain without a second verdict authority. Cost: a per-span check on normal return (unconfigured
  ⇒ resolved detector is `null` ⇒ reference-compare + null-check + return, no invocation).
- **Detector as a field on `TraceConfig` (per-flow tier) — rejected.** The "free per-flow tier" is illusory: a
  per-flow `TraceConfig(adapters = …)` silently disables the detector for that flow (replace-not-merge), forces
  the lazy adapter provider early, and drags in `ThreadContextElement` machinery the detector never needs. The
  per-flow *definition of failure* is speculative; per-call covers the real local case. (Was the chosen home in
  the first draft; moved to `Kotrace` after review.)
- **Keep the detector strictly out of `TraceStatus` (first-draft position) — rejected.** Leaves the silent-drop
  footgun: a root-returned failure recorded on the span but reported `OK`, dropped by a failure-only adapter.
  The precedence chain fixes this while preserving recovery and single-winner-per-step semantics.
- **Per-call-only `failureDetector` (no ambient) — rejected.** Cannot recreate the climb: only spans that
  passed the parameter would detect, so the birthplace lands at the outermost *instrumented* layer. Fails the
  core goal.
- **Extend `returnedOutcome` to run on every span — rejected.** It returns a `TraceOutcome` (trace verdict);
  running it per span conflates span status with trace status (the ADR-004/§2 split) and its `attached` channel
  is meaningless off the root. Two callbacks with a clear precedence beat one overloaded callback.
- **`(T) -> Boolean` predicate + separate `(T) -> Throwable` extractor — rejected.** Two functions for what
  `(T) -> Throwable?` expresses in one; the Boolean still encodes "not a failure", and it does not map onto
  `Result.exceptionOrNull()`.
- **Value-only domain failures (`Either.Left(DomainError)`, no `Throwable`) — deferred (backlog D03).** No
  throwable for the crash reporter or `lineageKeyOf`; needs a status-only `LogEvent` path, a separate decision.

## Consequences

- **Failure-as-value becomes first-class.** A consumer declares "a `Result.failure` is a failure" once; every
  span marks `ERROR` and records the birthplace automatically with the same lineage dedup a thrown failure gets;
  a root-returned failure defaults the trace verdict to `ERROR`, and a root-returned `CancellationException` to
  `CANCELLED` — no per-boundary `addException`, no silent-drop, and the `runCatching`-swallow hazard is healed.
  An explicit `returnedOutcome` remains available to remap a status, attach orphans, or force `OK`.
- **The opt-out `{ null }` is a *local* suppression, not a climb-stopper.** Documented as such; recovery means
  returning a non-failure value.
- **Hot-path cost is opt-in.** With no detector installed and `InheritAmbient` per-call, the resolved detector
  is `null`, so the normal-return path is a reference-compare + a null-check and return — no allocation, no
  invocation.
- **Pairs with [ADR-019](adr-019-exception-origin-marking.md).** Returned-failure recording uses the same
  `recordPropagatedException`, so birthplace-vs-propagated origin marking applies to thrown and returned
  failures uniformly.

## Test matrix

- **Ambient detection.** With `failureDetector = { (it as? Result<*>)?.exceptionOrNull() }` installed: a single
  `span { returns Result.failure(e) }` (non-cancellation `e`) marks the span `ERROR`, reports one
  `ExceptionRecord` for `e`, **and the trace verdict is `ERROR`** (precedence step 4); `span { returns
  Result.success(v) }` stays `OK`, no exception, verdict `OK`.
- **Explicit `returnedOutcome` overrides the verdict.** A root returning `Result.failure(e)` with an explicit
  `returnedOutcome` mapping it to `OK` (or `CANCELLED`) reports that status (step 2 wins over steps 3–4); the
  span is still `ERROR` and `e` still recorded.
- **Returned-failure climb dedups to birthplace.** Three nested spans each returning the *same*
  `Result.failure(e)`: all three span `ERROR`; report yields **one** `ExceptionRecord` at the deepest span —
  identical topology to a thrown-and-rethrown `e`.
- **Synthesizing detector does not dedup (contract violation is observable).** A detector returning a fresh
  `RuntimeException` per call yields one birthplace per layer — documented as the contract break, guarding the
  "stable throwable" rule.
- **Semantic re-wrap makes a new birthplace.** A middle span mapping `Result.failure(a)` to
  `Result.failure(b)` (different instance) yields two lineages → two birthplaces (ADR-015 fail-open).
- **Parallel siblings sharing one failure.** Two sibling spans returning the same `Result.failure(e)`: one
  birthplace per sibling branch (ADR-015 ancestry-scoped), not one globally — asserted, not assumed.
- **Local opt-out does not stop the climb.** `failureDetector = { null }` on a middle span that still returns
  `Result.failure(e)` up: that span stays `OK`, but ancestors still detect `e` (the documented hole), and the
  birthplace resolves at the deepest *observing* span. Recovery via returning `Result.success` **does** keep
  ancestors `OK`.
- **Returned `CancellationException` mirrors thrown (option D).** A `span { returns
  Result.failure(CancellationException()) }` with the detector installed: span **`ERROR`**, the exception
  **recorded** (one `ExceptionRecord`), trace verdict **`CANCELLED`** (precedence step 3); an `ERROR`-gated
  crash adapter skips it, a log adapter sees it — **semantically equivalent** to the thrown-cancellation
  outcome (same status/record topology; timestamps, ids, and the throwable instance may differ). The
  `runCatching`-swallow case (`runCatching { cancellableCall() }` under cancellation) reports `CANCELLED`, not
  `OK`; whether it reaches a crash reporter depends on that adapter's status gate (§ crash-sink gating in
  ADR-019).
- **Detector *throwing* (not returning) is contained.** A detector that **throws** on a **succeeding** return
  does not fail the operation: value returned unchanged, span `OK`, completion carries no failure, hook fires with
  `phase = FAILURE_DETECTOR`, `adapter == null`; a detector-**thrown** `CancellationException` is contained
  (trace **not** made `CANCELLED`); JVM-fatal rethrown. Contrast the returned-`CancellationException` case above.
- **Fault-hook routing (whole-config precedence).** A detector fault in a flow carrying a per-flow
  `TraceConfig(faultHook = H)` routes to `H`; a flow carrying a per-flow `TraceConfig(faultHook = null)`
  **swallows** it (whole-config wins, no inherit); with no per-flow config it routes to the `Kotrace`-installed
  hook.
- **Strict-uninstalled untouched by detection.** With strict armed and nothing installed, a **nested/manual**
  span return does **not** throw — detector resolution reads `Kotrace.failureDetector()` (null → no-op), never
  `resolvedThreadConfig()`. (A top-level auto-root still fires strict on its *report*, as today; the assertion
  is that detector *resolution* does not.)
- **Auto-root paths.** Ambient detector fires at the root (verdict `ERROR`, or `CANCELLED` for a returned
  cancellation); a top-level per-call `failureDetector = { … }` reaches the root execution path (not
  lost); a top-level `failureDetector = { null }` disables detection for the root only. Detection happens
  exactly once per span; the root `SpanCompletion` drives the verdict, not `root.status`.
- **Zero-config behavior unchanged.** No detector anywhere → the full existing suite passes. A normal return
  now produces one small, short-lived completion envelope per span; the caller unwraps it immediately and it
  does not escape at the source level, so the JIT *may* elide it — but the allocation crosses `executeSpan`'s
  suspend/continuation boundary (materialized into the state machine, returned as `Any?`), so scalar
  replacement is not guaranteed.

---

[← All decisions](../DECISIONS.md)
