# Decisions (ADR index)

Architecture Decision Records for kotrace. One file per ADR in `decisions/`. Read this index first;
open only the ADR you need.

| # | Title | Status | Date |
|---|---|---|---|
| [001](decisions/adr-001-span-filter-attrs-vs-emitted-info.md) | Span filter attributes (fixed) vs emitted info (dynamic) | Accepted | 2026-08-18 |
| [002](decisions/adr-002-remove-capture-gate.md) | Remove the capture gate; fan-out is the single filtering authority | Accepted | 2026-08-21 |
| [003](decisions/adr-003-span-verb-rename-and-startspan-optin.md) | Rename the suspend `trace` verb to `span`, and gate `startSpan` behind `@RequiresOptIn` | Accepted | 2026-08-22 |
| [004](decisions/adr-004-tojson-nested-attributes-info.md) | `toJson` nests `attributes` / `info` instead of spreading them as sibling keys | Accepted | 2026-08-22 |
| [005](decisions/adr-005-birthplace-requires-throwable-drop-helper.md) | Birthplace = deepest throwable-bearing span; drop the public `birthplaceSpan()` helper | Accepted | 2026-08-22 |
| [006](decisions/adr-006-span-terminal-state-safe-publication.md) | `Span` terminal state (`status`/`endNanos`) via safe publication of an immutable pair | Accepted | 2026-08-22 |
| [007](decisions/adr-007-dokka-and-compiled-kdoc-samples.md) | Adopt Dokka; KDoc examples are compiled `@sample`s, not prose snippets | Accepted | 2026-08-22 |
| [008](decisions/adr-008-unredacted-trace-read-optin.md) | Gate `renderTree` behind an `@UnredactedTraceRead` opt-in marker | Accepted | 2026-08-22 |
| [009](decisions/adr-009-trace-link-cross-trace-correlation.md) | `TraceLink`: cross-trace correlation on `trace_id` only (no `span_id`) | Proposed | 2026-08-26 |
| [010](decisions/adr-010-spanless-fanout-and-ambient-scope.md) | Span-less live fan-out + `scope_id`, a correlation level above `trace_id` (`scope ⊇ trace ⊇ span`) | Accepted | 2026-09-06 |
| [011](decisions/adr-011-strict-uninstalled-optin.md) | `strictWhenUninstalled`: an opt-in fail-fast for emit-before-install | Proposed | 2026-09-07 |
| [012](decisions/adr-012-reporttrace-attached-orphan-failures.md) | `reportTrace(attached)`: trace-level orphan failures (e.g. saga rollback throwables) on the report path, keyed to root, past the birthplace dedup | Accepted | 2026-09-07 |
| [013](decisions/adr-013-auto-root-span.md) | A top-level `span` self-owns its trace and reports at the outcome (auto-root, iff no span/collector in context) — no new verb | Accepted | 2026-09-12 |
| [014](decisions/adr-014-adapter-fault-isolation.md) | Per-adapter fault isolation across every fan-out phase (live / span-less / report) + opt-in diagnostic hook | Accepted | 2026-09-12 |
| [015](decisions/adr-015-exception-origin-token.md) | Birthplace dedup by a stable, fail-open exception lineage key, not "deepest throwable-bearing span" (fixes recover-and-rethrow-different drop) | Accepted | 2026-09-13 |
| [016](decisions/adr-016-auto-root-returned-outcome.md) | A return-aware `span` overload: auto-root maps the *returned value* to the trace outcome (failure-as-value) — amends ADR-013 | Accepted (two-overload shape superseded by ADR-017) | 2026-09-14 |
| [017](decisions/adr-017-merge-span-overloads-default-returned-outcome.md) | Merge the two `span` overloads into one (`returnedOutcome` defaulted to always-OK); one `autoRootSpan` boundary — supersedes ADR-016's two-overload shape; breaking (0.4.0→0.5.0) | Accepted | 2026-09-18 |
| [018](decisions/adr-018-ambient-failure-detector.md) | Ambient `failureDetector` ((Any?)→Throwable?): treat a *returned* failure value like a thrown one (span-level ERROR + birthplace), process-global on `Kotrace` + per-call override; root detector supplies the default trace verdict by precedence — amended and renamed by ADR-020 | Accepted | 2026-09-19 |
| [019](decisions/adr-019-exception-origin-marking.md) | Mark each report `ExceptionRecord` `BIRTHPLACE` vs `PROPAGATED`; move birthplace dedup from the walk to a per-adapter opt-in gate (`acceptsPropagatedException`, default false) — amends ADR-005 | Accepted | 2026-09-19 |
| [020](decisions/adr-020-value-only-failure-classifier.md) | Generalize returned-failure detection into `FailureClassifier`; `ValueOnly` marks status without synthetic records, `CausedBy(Throwable)` preserves exception lineage — amends ADR-018/019 | Accepted | 2026-09-23 |
