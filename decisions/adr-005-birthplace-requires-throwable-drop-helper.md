# ADR-005 — Birthplace = deepest throwable-bearing span; drop the public `birthplaceSpan()` helper

- **Date:** 2026-08-22
- **Status:** Accepted (implemented — `isBirthplaceAmong` gated on the throwable; `birthplaceSpan()` removed)
- **Affects:** `dev.kotrace.isBirthplaceAmong` (`Report.kt`), `reportTrace`, `renderTree`,
  `dev.kotrace.Trace` (public `birthplaceSpan()` removed), `:demo`

## Context

"Birthplace" — the one span a trace's `ExceptionRecord` is emitted on — had **two** divergent definitions:

- **`isBirthplaceAmong(children)`** (used by `reportTrace` and `renderTree`): `status == ERROR && no ERROR
  child` — the *leaf-most ERROR* span. Could hold for several spans; **did not require a throwable**.
- **`birthplaceSpan()`** (public helper on `SpanCollector`): the single *deepest* span with
  `status == ERROR && exception != null` — **required a throwable**, returned exactly one.

The two could name different spans. Worse, the report-side predicate had a latent **crash-loss bug** of its
own, independent of the helper mismatch.

A throwable-less ERROR span is representable: `Span.end(status = ERROR, error = null)` sets ERROR without
recording an `ExceptionEvent`, and the **shipped OkHttp bridge uses exactly this path** — `TracingInterceptor`
ends a span `ERROR` on a non-2xx response that *returned* rather than threw (an HTTP 500 is a normal OkHttp
response, not an exception). So this flow is common:

```kotlin
span("checkout") {                         // ancestor span S
    val r = httpCall(pricingUrl)           // bridge child H: 500 -> end(ERROR, null), NO throwable
    if (!r.ok) throw AppException("...")   // S catches, records the throwable on S
}
```

Tree: `S(ERROR, throwable) -> H(ERROR, no throwable, leaf)`. Under the leaf-most predicate:

- **H** is the birthplace (ERROR, no ERROR child) but has no `ExceptionEvent`, so it emits nothing.
- **S** has an ERROR child, so it is *not* the birthplace — its real throwable is **silently dropped from
  the report**.

Net: a failed trace fans out with **zero** `ExceptionRecord`. The crash cause vanishes.

## Decision

**1. Birthplace is defined by the throwable, not by leaf-most ERROR status.** `isBirthplaceAmong` becomes:
a span is a birthplace iff `exception != null` and **no span in its subtree** carries a throwable — the
deepest throwable-bearing failure on its branch. The throwable climbs coroutine ancestors (re-recorded on
each enclosing `span`), so this uniquely selects the origin per branch; a throwable-less ERROR leaf is no
longer a birthplace and can never shadow an ancestor's throwable. The predicate now takes the whole span
list and walks descendants (via `parentId`), so a throwable nested below an intermediate throwable-less span
is still found — not just immediate children. Traces hold few spans, so the walk cost is negligible (the
same assumption the collector's copy-on-write list already makes).

**2. `report` is the sole birthplace authority; the public `birthplaceSpan()` helper is removed.** Two code
paths computing "birthplace" is two chances to disagree. The report already emits the birthplace
`ExceptionRecord`(s); a consumer wanting the crash reads them off the report stream. The `:demo` migrates
from `collector.birthplaceSpan()` to reading the `ExceptionRecord` its `ReportAdapter` receives.

## Consequences

- **The crash-loss bug is fixed**, with a regression test: a bridge `end(ERROR, null)` leaf under a
  throwing ancestor now reports the ancestor's throwable, not nothing.
- **`report` and any consumer agree by construction** — there is no second definition to drift from.
- **Multiple independent failures** (throwables in two sibling branches) yield *multiple* birthplaces, and
  the report emits one `ExceptionRecord` per branch — correct, and no longer misrepresented as a single span
  by a helper that returned only one.
- **Breaking API change:** `SpanCollector.birthplaceSpan()` is gone. Accepted — it was a convenience over
  `spans`, duplicated the report's own logic, and disagreed with it in exactly the cases that matter (bridge
  errors). No consumer was locked to it.
- **`renderTree`** shows the throwable at the same throwable-bearing span the report does — the human read
  and the machine report no longer disagree on where the crash originated.

## Rejected alternatives

- **Unify onto the existing leaf-most predicate (keep "no ERROR child", no throwable check).** This is the
  buggy definition — it drops an ancestor's throwable behind a throwable-less ERROR leaf. Rejected: it is the
  cause, not a candidate.
- **Keep `birthplaceSpan()` but have it delegate to the shared predicate.** Removes the divergence, but a
  single-span return still misrepresents a multi-branch failure, and it keeps a second entry point over the
  same data. Rejected: the report is already the authority; a parallel helper earns its keep only if it says
  something the report cannot, and it does not.
- **Gate only the immediate children (`children.none { it.exception != null }`).** Correct for the coroutine
  path (the throwable climbs every level) but misses a throwable nested below an intermediate throwable-less
  bridge span. Rejected in favour of the full-subtree walk — cheap on small traces, and unconditionally
  correct.
