# ADR-021 — The OkHttp span name is a static symbol (`http <METHOD>`), never the raw URL path

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** `dev.kotrace.okhttp.TracingCallFactory` — the default span name drops the path
  (`http GET /users/alice@example.com` → `http GET`), and the constructor gains an optional
  `spanName: (Request) -> String` so a caller can supply a sanitized route template. **Wire change:** the
  `operation` field of every record lifted off an HTTP span (and its `toJson` line) changes shape.
- **Builds on:** the `Span` symbol-only invariant ([ADR-001](adr-001-span-filter-attrs-vs-emitted-info.md),
  `Span` KDoc), [ADR-005](adr-005-birthplace-requires-throwable-drop-helper.md) (the crash record is not
  sensitive-gated).

## Context

`TracingCallFactory` named each HTTP span `"http ${method} ${url.encodedPath}"`. `Span.name` is not an
in-memory detail: it becomes `operation` on **every** `TraceRecord` lifted off the span — including the
`ExceptionRecord` a default crash-reporter policy receives and the `toJson` line a log store ingests.

The same module already treats the path as user data: `TracingInterceptor` marks every breadcrumb that
interpolates the path `sensitive`, because a REST path can embed an email, id, or token. But `operation` has no
sensitive gate — `TracePolicy.acceptsSensitive` only covers `LogEvent.message`, and an `ExceptionEvent` passes
`acceptsEvent` alone. So a failed request to `/users/alice@example.com` put the address into a default crash
record and the JSON log line, past every policy. The span name was the one path-bearing field the module did
not classify.

## Decision

- The **default** HTTP span name is `http <METHOD>` — method only, no path, no query. Method is a closed,
  static vocabulary, so it satisfies the symbol-only invariant.
- `TracingCallFactory` takes an optional `spanName: (Request) -> String` (defaulted). A caller that needs to
  tell endpoints apart maps a request to a **sanitized route template** (`http GET /users/{id}`). The contract,
  stated in KDoc: it must return a symbol and never interpolate the concrete path or query. kotrace does not
  try to template paths itself — it has no route table, and guessing which segments are ids is exactly the kind
  of heuristic that leaks on the case it misses.
- The concrete path stays where it already was: on the `sensitive` breadcrumbs `TracingInterceptor` logs
  (device-only unless a policy opts in).

## Options considered

- **Keep the path, mark something sensitive — rejected.** There is no sensitive flag on `operation`; adding one
  would gate a join key that every record needs, breaking correlation for every adapter that declines sensitive.
- **Built-in path templating (replace numeric/uuid segments) — rejected.** Heuristic; an email, handle, or
  opaque token segment passes a numeric/uuid filter and leaks. A caller-supplied template is exact.
- **Method-only default + caller-supplied template (chosen).** Safe by default; precise when the caller knows
  its routes.

## Consequences

- **Breaking wire change** (`operation` shape for HTTP spans), shipped in the 0.5.0 break alongside ADR-017.
  Dashboards or queries keyed on `operation = "http GET /path"` must switch to the caller's templates, or to
  `http GET` plus the span's own attributes.
- Out of the box, HTTP spans from different endpoints share one `operation`; nesting under the caller's own
  span (`span("AccountRepository.fetch") { … }`) still identifies the call site. `spanName` restores
  per-endpoint names without PII.
- Source-compatible for existing callers (the new parameter is defaulted and last), but not binary-compatible:
  the `TracingCallFactory(Call.Factory, Map)` constructor is gone, so code compiled against 0.4.0 must be
  recompiled. The 0.5.0 bump covers it.
