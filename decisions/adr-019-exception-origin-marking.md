# ADR-019 — Mark each report `ExceptionRecord` `BIRTHPLACE` vs `PROPAGATED`; move the birthplace dedup from the walk to a per-adapter opt-in gate

- **Date:** 2026-09-19
- **Status:** Accepted
- **Affects:** the report walk no longer *drops* non-birthplace `ExceptionEvent`s — it collects the whole
  climb and stamps each entry's **origin** ([`Report.kt:52-60`](../src/main/kotlin/dev/kotrace/Report.kt:52));
  `ExceptionRecord` gains `origin: ExceptionOrigin?`
  ([`TraceException.kt:131`](../src/main/kotlin/dev/kotrace/event/TraceException.kt:131)); `TracePolicy` gains
  `acceptsPropagatedException: Boolean = false` ([`TracePolicy.kt:14`](../src/main/kotlin/dev/kotrace/TracePolicy.kt:14));
  the report `viewOf` applies that gate ([`Report.kt:100`](../src/main/kotlin/dev/kotrace/Report.kt:100)); `toJson`
  emits `"exception_origin"` **only** for a `PROPAGATED` record (birthplace / live / `null` unchanged). **No
  change** to capture, to the lineage-key machinery, to the live path (already un-deduped), or to `renderTree`.
- **Builds on:** [ADR-005](adr-005-birthplace-requires-throwable-drop-helper.md) (birthplace = the origin;
  dedup-to-one is the property being *preserved as default*), [ADR-015](adr-015-exception-origin-token.md) (the
  lineage key that already computes birthplace-vs-copy — this ADR only *exposes* that classification instead of
  consuming it), [ADR-004](adr-004-tojson-nested-attributes-info.md) /
  [ADR-010](adr-010-spanless-fanout-and-ambient-scope.md) (additive-field discipline: emit only when it changes
  the shape), [ADR-002](adr-002-remove-capture-gate.md) (fan-out is the single filtering authority — the new
  gate lives there), [ADR-014](adr-014-adapter-fault-isolation.md) (per-adapter view under guard).
- **Amends:** [ADR-005](adr-005-birthplace-requires-throwable-drop-helper.md) — birthplace **dedup** moves from
  a global decision *inside the walk* to a **per-adapter view gate**, so a crash sink still sees the deduped
  birthplaces (the default) while a log/trace sink can opt into the full marked climb. ADR-005's birthplace
  *definition* is untouched; only *where* the drop happens changes. ("Deduped" = one record **per lineage per
  failing branch** — a linear climb collapses to one, but N independent failing branches keep N birthplaces,
  ADR-015; see § "exactly one" caveat.)
- **Pairs with:** [ADR-018](adr-018-ambient-failure-detector.md) — returned failures record through the same
  `recordPropagatedException`, so origin marking covers thrown and returned failures uniformly.

## Context

A failure is re-recorded on **every** span it climbs: the `span` catch (and, per ADR-018, the returned-failure
detector) calls `recordPropagatedException` on each enclosing span
([`Trace.kt:88`](../src/main/kotlin/dev/kotrace/Trace.kt:88)), stamping every copy with the *same* ADR-015
lineage key. The whole climb is therefore already present in the tree as `ExceptionEvent`s on
`Span.events`.

At report time, the walk **discards** all but the deepest (birthplace) copy
([`Report.kt:55`](../src/main/kotlin/dev/kotrace/Report.kt:55)):

```kotlin
val collected = if (event is ExceptionEvent) event in birthplaces else true
if (collected) entries += WalkEntry(span, event)
```

So a `ReportAdapter` sees **one** `ExceptionRecord` per lineage — at the birthplace (a *single* linear failing
path collapses to one; independent branches keep one each, § "exactly one" caveat). This is deliberate and
correct **for a crash reporter**: the N *copies of one climbing failure* (N = its depth) would create N
duplicate crash groups, wrong counts, and noise (ADR-005). But it is a **global** decision baked into the shared walk — no adapter can opt out. A
consumer that wants a report-time view of the *full failing path* — a JSON log pipeline reconstructing where a
failure originated versus where it merely passed through, a trace visualiser — cannot get it from the report,
even though the data exists in the tree. (The **live** path already fans out the whole climb, deepest-first,
ungated — [`TraceException.kt:154`](../src/main/kotlin/dev/kotrace/event/TraceException.kt:154) — but that is
per-event as-it-happens, not the buffered tree a report adapter consumes.)

Two consumers of the **same trace** want opposite shapes:

- **`CrashAdapter`** — the birthplace(s) only, deduped per lineage per branch (today's behavior).
- **`LogAdapter`** — the full climb, each record marked so the birthplace is unambiguous ("even if it
  duplicates, be explicit about the origin").

That is precisely the situation kotrace's **"one walk, N views"** principle (§4) exists to serve — but only if
the dedup stops being a property of the *walk* and becomes a property of each *adapter's view*.

Note what the report does **not** carry today: a span's `SpanStatus` never crosses to an adapter (it lives on
`Span`, which never leaves the core). So the failing *path* is not directly visible on the record stream at all
— only the single birthplace record is. Exposing the marked climb is the report's only way to show the path
without the consumer re-deriving it from `parentId` chains.

## Decision

**Stop dropping propagated copies in the walk. Collect the whole climb, stamp each `ExceptionRecord` with its
origin (`BIRTHPLACE` | `PROPAGATED`), and let each adapter's `TracePolicy` decide — via a new
`acceptsPropagatedException` gate, default `false` — whether to receive the propagated copies. The default
reproduces today's birthplace-only report exactly.**

### The origin type and record field

```kotlin
/** Whether an emitted [ExceptionRecord] is the failure's origin, or a copy re-recorded as it climbed. */
enum class ExceptionOrigin { BIRTHPLACE, PROPAGATED }

data class ExceptionRecord(
    override val traceId: String?,
    override val spanId: String?,
    override val parentId: String?,
    override val operation: String?,
    override val atNanos: Long,
    override val scopeId: String? = null,
    override val info: Map<String, String>,
    override val links: List<TraceLink>,
    val throwable: Throwable,
    val origin: ExceptionOrigin? = null,   // NEW: null ⇒ not classified (the live path; see below)
) : TraceRecord
```

`origin` is **nullable**: the **report** path classifies every exception record (`BIRTHPLACE` /
`PROPAGATED`), but the **live** path emits an exception the moment it is thrown, *before* the tree exists, so
birthplace cannot be determined there — a live `ExceptionRecord` carries `origin = null`. (A live watcher still
sees the climb deepest-first as today; it just is not labelled.)

### The walk stamps origin instead of dropping (Report.kt)

```kotlin
private class WalkEntry(val span: Span, val event: SpanEvent, val origin: ExceptionOrigin?)

fun walk(span: Span, events: List<SpanEvent>) {              // events: the SAME snapshot the index saw
    val birthplaces = tree.birthplaceExceptionsOf(span)      // ADR-015 index, computed over that snapshot
    events.filter { it.reportable() }.sortedBy { it.atNanos }.forEach { event ->
        val origin = if (event is ExceptionEvent) {
            if (event in birthplaces) ExceptionOrigin.BIRTHPLACE else ExceptionOrigin.PROPAGATED
        } else null
        entries += WalkEntry(span, event, origin)            // NO drop: every copy is collected + labelled
    }
    tree.childrenOf(span).forEach { walk(it, tree.eventsOf(it)) }
}
```

**One immutable event snapshot per span, shared by the index and the walk.** `Span.events` is a
`CopyOnWriteArrayList` that permits concurrent append ([`Span.kt:63`](../src/main/kotlin/dev/kotrace/Span.kt:63)).
Today `TraceTreeIndex` reads a span's events while indexing birthplaces
([`Report.kt:124`](../src/main/kotlin/dev/kotrace/Report.kt:124)) and the walk reads them **again**
([`Report.kt:54`](../src/main/kotlin/dev/kotrace/Report.kt:54)); an `ExceptionEvent` appended *between* those
two reads is collected by the walk but absent from the index → it would be labelled `PROPAGATED` (dropped by
default, mislabelled under opt-in). Structured children have joined by report time, so at the auto-root
boundary this cannot happen; but a hand-seeded collector or an off-thread bridge append is not covered by that
assumption. The fix is to snapshot each span's events **once** (`tree.eventsOf(span)`) and feed the *same* list
to both the birthplace index and the walk, so classification is internally consistent regardless of a late
writer. This also makes ADR-019 strictly no-worse than today for the racy case (today the late event is
silently dropped; here it is consistently classified against the same snapshot).

`attached` orphan entries (ADR-012, appended post-walk, [`Report.kt:62`](../src/main/kotlin/dev/kotrace/Report.kt:62))
are stamped `BIRTHPLACE` — they are origins with no deeper copy.

### The gate — per-adapter, report-only (TracePolicy)

```kotlin
interface TracePolicy {
    fun acceptsSpan(span: Span): Boolean = true
    fun acceptsEvent(event: SpanEvent): Boolean = true
    val acceptsSensitive: Boolean get() = false

    /**
     * Does this adapter receive the PROPAGATED copies of a climbing exception, or only the single BIRTHPLACE?
     * Default `false` — the birthplace-only report of ADR-005, so a crash reporter is never handed N duplicate
     * crash groups. A log / trace-visualisation adapter sets `true` to receive the whole marked climb.
     * Report-only: the live path is per-event and already un-deduped, so this gate does not apply there.
     */
    val acceptsPropagatedException: Boolean get() = false   // NEW
}
```

Applied in the report `viewOf`, **before** the existing `accepts` gates, keyed on the walk-computed origin:

```kotlin
private fun ReportAdapter.viewOf(entries: List<WalkEntry>): Sequence<TraceRecord> {
    val keepPropagated by lazy { policy.acceptsPropagatedException }   // read ONCE, and only when consumed
    return entries.asSequence()
        .filter { keepPropagated || it.origin != ExceptionOrigin.PROPAGATED }   // NEW: propagated gate FIRST
        .filter { policy.accepts(it.span, it.event) }                           // then span/event/sensitive
        .map { it.span.recordOf(it.event, origin = it.origin) }                 // origin stamped onto the record
}
```

The flag is read through `by lazy`, **not** eagerly, so it is evaluated once and only when the sequence is
iterated inside `onReport`. Reading it eagerly (at `viewOf` build time, before `onReport` is entered) would let
a throwing `acceptsPropagatedException` getter abort before an adapter's `if (status == OK) return` self-gate
ever ran — regressing ADR's lazy report contract. Deferring the read preserves "a skipped outcome forces no
work".

**The propagated gate runs first, on purpose.** Today the walk drops propagated copies *before any policy runs*
([`Report.kt:55`](../src/main/kotlin/dev/kotrace/Report.kt:55)), so a default-policy adapter's `accepts` never
sees them. If the new gate ran *after* `accepts`, a default adapter's policy would now be invoked on the N
propagated entries — running its side effects N times, and letting a throwing policy on an early propagated
entry abort the lazy iteration before a later child birthplace is delivered. That is an observable regression
under the default flag. Filtering propagated entries out first restores the "policy never sees a dropped copy"
invariant exactly.

The gate is **not** folded into the shared `accepts(span, event)` predicate
([`TracePolicy.kt:48`](../src/main/kotlin/dev/kotrace/TracePolicy.kt:48)) because that predicate is used by the
**live** path too, and origin is a tree-level fact unavailable at live time. Keeping the propagated gate inside
the report `viewOf` confines it to where origin is defined.

`recordOf` gains an `origin: ExceptionOrigin? = null` parameter used only on its `ExceptionEvent` branch; the
live emit path calls it without the argument, so a live exception record keeps `origin = null`.

### Wire — `toJson` stays byte-identical unless an adapter opts in (ADR-004/010 discipline)

`toJson` emits the key **only** for a `PROPAGATED` record:

```kotlin
// inside ExceptionRecord.toJson():  … existing fields …
if (origin == ExceptionOrigin.PROPAGATED) append(""","exception_origin":"propagated"""")
// BIRTHPLACE and null emit nothing → today's JSON is unchanged
```

Since the default gate drops `PROPAGATED` records, a consumer that changes nothing gets byte-identical report
output (birthplace-only, no `exception_origin` key) — parallel to ADR-010 emitting `scope_id` only when
non-null.

**Limitation, stated honestly:** because only `PROPAGATED` is serialized, `BIRTHPLACE` and live `null` both
emit *no* key, so a backend consuming mixed live+report JSON cannot distinguish "birthplace" from "unclassified
(live)" from the wire alone — the distinction is only fully explicit on the in-memory `ExceptionRecord.origin`
API. This is deliberate (it keeps default JSON unchanged), and sufficient for the target use (a report-time
`LogAdapter` reads `origin` directly). A consumer that needs the three-way distinction on the wire opts into a
serialization that emits all classified values (`"birthplace"` / `"propagated"`, `null` omitted); that is an
additive follow-on, not part of this ADR's default.

## The consumer story this enables

```kotlin
class CrashAdapter : ReportAdapter {
    override val policy = object : TracePolicy {}                 // defaults ⇒ birthplace-only, unchanged
    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        if (status != TraceStatus.ERROR) return                  // ERROR-gated: skip OK *and* CANCELLED
        records.filterIsInstance<ExceptionRecord>().forEach { crashReporter.record(it.throwable) }  // one per branch
    }
}

class LogAdapter : ReportAdapter {
    override val policy = object : TracePolicy { override val acceptsPropagatedException = true }
    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        records.filterIsInstance<ExceptionRecord>().forEach { r ->
            val tag = if (r.origin == ExceptionOrigin.BIRTHPLACE) "origin" else "propagated"
            logSink.line(r.spanId, r.operation, tag, r.throwable::class.simpleName)   // full path, origin explicit
        }
    }
}
```

Both hang off the **same** trace and the **same single walk**; the shape of each sink is one policy flag.

**Crash-sink gating (matters once ADR-018 lands).** A crash adapter must gate on `status == TraceStatus.ERROR`,
**not** `status != OK`. A `CANCELLED` trace can now carry an `ExceptionRecord` — a thrown `CancellationException`
already does, and ADR-018 option D makes a *returned* one do the same — so a `status != OK` gate would send
cancellations to the crash reporter. `status == ERROR` skips both `OK` and `CANCELLED`, which is the intended
"crashes only" contract. kotrace does not enforce this (the adapter owns its routing); it is the documented
contract for a crash sink.

## Source compatibility

- **`ExceptionRecord`** gains a **trailing defaulted** field (`origin = null`). Source-compatible for existing
  construction/`copy`/deconstruction up to the current arity; **binary**: the generated constructor/`copy`
  signature changes, so a pre-compiled direct constructor of `ExceptionRecord` needs a recompile. Adapters that
  only *read* records are unaffected (a new read-only property is additive).
- **`TracePolicy`** gains a **defaulted** member (`acceptsPropagatedException = false`); every existing
  implementation compiles and behaves identically (birthplace-only).
- **`toJson`** output is **unchanged by default** (the key appears only on a `PROPAGATED` record, which no
  adapter receives unless it opts in). No existing log-pipeline schema breaks.
- **Not fully behaviorally identical — say it precisely.** What is unchanged by default is the **record
  selection** (birthplace-only) and the **JSON**. But every default report `ExceptionRecord` now carries
  `origin = BIRTHPLACE`, so the data-class `equals`/`hashCode`/`copy`/`toString` observably differ from a
  pre-ADR record even when count and serialization do not. An adapter that compares whole records (unusual) sees
  the difference. Claim "same record selection and JSON", not "behavior exactly unchanged".
- **Behavior default = today (selection + wire).** With no policy override, the report is birthplace-only, and
  its JSON is byte-identical — as ADR-005. Purely additive opt-in. Pre-1.0: bundle with the same minor as
  ADR-018.

## Options considered

- **Collect all copies + stamp origin + default-false per-adapter gate (chosen).** Serves the two opposite
  consumers off one walk; crash path unchanged and ADR-005 preserved as the default; the classification already
  exists (ADR-015) so no new dedup logic; wire stays byte-identical unless opted in. Cost: the shared `entries`
  list now holds N exception entries per failing branch instead of 1 (traces are small and failures rare — the
  same trade every copy-on-write/collect decision in kotrace already makes), and a lazy view means an adapter
  that does not opt in never builds the extra records.
- **Keep dropping in the walk; add a *separate* un-deduped exception stream for opt-in adapters — rejected.**
  Two code paths producing exception records (one deduped in the walk, one not), diverging over time; violates
  "one walk, N views". The chosen design keeps a single walk and moves only the *filter* to the view.
- **Stamp span `SpanStatus` onto every record instead of duplicating exceptions — rejected (as the primary
  mechanism).** Avoids duplication but is **incomplete**: an ancestor on the failing path with no other
  reportable event produces no record, so it is invisible; and it does not match the "duplicate but explicit"
  request. It remains a sensible *independent, additive* follow-on (surface `status` on records so the failing
  path is visible without exception copies) and is noted as such, not adopted here.
- **Always emit the full climb (no gate; make crash adapters filter `origin == BIRTHPLACE`) — rejected.** Forces
  every crash adapter to add a filter or regress into N-duplicate crash groups; the gate has to exist either
  way, so defaulting it to *drop* (safe, = today) beats defaulting it to *emit* (breaks the common sink).
- **Non-nullable `origin` with a third `UNKNOWN`/`LIVE` value for the live path — rejected.** `null` already
  means "unclassified", is the natural default for the additive field, and lets `toJson` treat live/birthplace
  identically (emit nothing). A third enum constant adds a wire value with no consumer.

## Consequences

- **Sink shape is one policy flag.** `CrashAdapter` (defaults) dedups to the birthplace with zero code;
  `LogAdapter` (`acceptsPropagatedException = true`) receives the full climb with each record labelled. No new
  adapter callback, no second walk.
- **The birthplace-vs-propagated distinction becomes explicit on the report API** (`ExceptionRecord.origin`),
  rather than an implicit "there is only ever one record". An opt-in `LogAdapter` can render the failing path
  and point at the origin. On the *wire* the distinction is partial by design — only `PROPAGATED` serializes
  (§ Wire limitation) — so a JSON-only backend sees "propagated vs not", while the full three-way
  (birthplace / propagated / unclassified) lives on the in-memory record.
- **Report order is DFS tree order, documented.** The walk emits a span's records, then its children
  ([`Report.kt:52`](../src/main/kotlin/dev/kotrace/Report.kt:52)), so under opt-in an ancestor's `PROPAGATED`
  record precedes the deeper `BIRTHPLACE` — the *opposite* of the live path's deepest-first climb. This is
  deliberate: report order follows the tree (natural for a path view), live order follows occurrence. A
  consumer keys on `origin`, not position; the ordering is specified and tested, not incidental.
- **ADR-005 preserved as the default, its scope narrowed to "the default view".** The birthplace *definition*
  and the crash-reporter dedup (one record **per lineage per failing branch**) are intact; what changes is that
  this dedup is now the default *view*, not a global truncation, so richer views are possible without weakening
  the crash path.
- **"Exactly one" caveat.** The default view deduplicates a *single climbing failure* to its birthplace; it does
  **not** promise one record per trace. A trace with two independent failing branches reports two birthplaces
  (ADR-015 ancestry-scoped keys), and a returned failure observed on parallel siblings likewise yields one
  birthplace per branch. Prose here says "one per lineage / per branch", never a bare "exactly one", so a crash
  adapter author does not assume single-record traces.
- **Uniform across thrown and returned failures.** Because ADR-018's returned-failure recording uses the same
  `recordPropagatedException`, a returned-failure climb is marked `BIRTHPLACE`/`PROPAGATED` identically — no
  additional work.
- **Live is untouched.** The live path already shows the un-deduped climb; live records now simply carry
  `origin = null` (unclassified), which is honest — birthplace is a whole-tree property the live moment cannot
  know.
- **`renderTree` may follow, independently.** The unredacted debug renderer
  ([`TraceFormat.kt:33`](../src/main/kotlin/dev/kotrace/TraceFormat.kt:33)) currently shows a throwable only at
  the birthplace; it could annotate ancestors as `error (propagated from child)` using the same index. Out of
  scope here (a human-render concern, `@UnredactedTraceRead`-gated), noted as a natural companion.

## Test matrix

- **Default report is birthplace-only.** A three-deep *linear* failing trace with a default-policy
  `ReportAdapter` yields **one** `ExceptionRecord`, `origin == BIRTHPLACE`, at the deepest span — byte-identical
  to today, and its `toJson` has **no** `exception_origin` key.
- **Multiple failing branches keep multiple birthplaces.** A trace with two independent failing branches yields
  two `BIRTHPLACE` records under the default policy — guards the "one per lineage per branch" wording against a
  bare "exactly one".
- **Opt-in report is the full marked climb.** The same trace with `acceptsPropagatedException = true` yields
  **N** `ExceptionRecord`s (one per span on the path): the deepest `BIRTHPLACE`, the rest `PROPAGATED`, each
  carrying its own `spanId`/`parentId`; each `PROPAGATED` record's `toJson` includes
  `"exception_origin":"propagated"`, the `BIRTHPLACE` one does not.
- **Two adapters, one walk.** A `CrashAdapter` (default) and a `LogAdapter` (opt-in) on the same trace receive
  one and N records respectively from a single `reportTrace`; neither affects the other's view.
- **Attached orphans are `BIRTHPLACE`.** An `attached` throwable (ADR-012) emitted post-walk carries
  `origin == BIRTHPLACE` and rides through regardless of the propagated gate.
- **Live records are unclassified.** A `LiveAdapter` still sees each climb copy as it is thrown; every live
  `ExceptionRecord` has `origin == null` and emits no `exception_origin`.
- **Policy interplay unchanged.** `acceptsEvent` dropping an `ExceptionEvent` still drops it regardless of
  origin; `acceptsPropagatedException` only *narrows* which exception records survive, never widens past
  `acceptsEvent`.
- **Returned-failure parity (with ADR-018).** A returned-failure climb marked via `failureDetector` produces
  the same `BIRTHPLACE`/`PROPAGATED` stamping as an equivalent thrown-and-rethrown failure.
- **Default policy is never invoked on a propagated entry (ordering regression guard).** A default-policy
  `ReportAdapter` whose `accepts` counts calls (or throws) is asserted to be invoked **only** for the surviving
  birthplace entries, never for the dropped `PROPAGATED` copies — confirming the propagated gate runs before
  `accepts`. A throwing policy on a would-be-propagated entry does not abort delivery of a later birthplace.
- **Lazy self-gate preserved.** An adapter that returns immediately for `TraceStatus.OK` is not affected by a
  throwing `acceptsPropagatedException` getter — the flag is read only when the sequence is consumed.
- **Snapshot consistency under a late append.** An `ExceptionEvent` appended to a span between indexing and the
  walk is classified against the **same** snapshot both passes use (so it is not collected by the walk yet
  missing from the index) — no mislabel; a hand-seeded/off-thread-bridge append is the case exercised.
- **Report order is DFS tree order.** Under opt-in, an ancestor's `PROPAGATED` record precedes the deeper
  `BIRTHPLACE` on the same branch — asserted, so the documented (tree, not deepest-first) order is pinned.

---

[← All decisions](../DECISIONS.md)
