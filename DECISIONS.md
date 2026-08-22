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
