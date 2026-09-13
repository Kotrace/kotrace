# Agent Instructions

This repository is a Kotlin/JVM tracing library. Use this file as the repo-local setup for Codex and other coding agents.

## Language

- Match the user's language. Reply in Vietnamese when the user writes in Vietnamese; reply in English when the user writes in English.

## Project Map

- Root project `dev.kotrace:kotrace`: pure Kotlin core tracer.
- `src/main/kotlin/dev/kotrace/Trace.kt`: public `span {}` entry point.
- `src/main/kotlin/dev/kotrace/Span.kt` and `SpanContext.kt`: span model and coroutine propagation.
- `src/main/kotlin/dev/kotrace/Report.kt`, `TraceAdapter.kt`, `TraceConfig.kt`, and `TracePolicy.kt`: fan-out/reporting surface.
- `kotrace-okhttp`: OkHttp integration. Keep HTTP dependencies out of core.
- `kotrace-room`: Android Room integration. Keep Android dependencies out of core and OkHttp.
- `kotrace-bom`: Maven BOM.
- `demo`: runnable showcase only; do not treat it as a published artifact.
- `decisions/` and `ARCHITECTURE.md`: source of truth for design rationale. Read the relevant ADR before changing behavior it covers.

## Build And Test

- Requires JDK 11+.
- Run focused tests with Gradle where possible, for example `./gradlew test` for core/unit tests.
- Run `./gradlew build` before finishing broad or cross-module changes.
- Run `./gradlew dokkaGenerate` when touching public KDoc or samples, because Dokka warnings are configured to fail.
- No secrets or environment variables are needed for normal build/test.

## Coding Constraints

- Preserve the module split: core must stay pure Kotlin plus coroutines; OkHttp and Android/Room dependencies belong only in their modules.
- Instrumentation observes and rethrows; it must not swallow user exceptions.
- `renderTree()` is an unredacted human debug read. Keep `@UnredactedTraceRead` opt-in boundaries intact and never route unredacted output to sinks.
- Trace wire fields use snake_case JSON and W3C `traceparent`; keep wire-format changes deliberate and documented.
- Prefer tests that double as usage examples. Public behavior changes should update tests and, when needed, README/ADR documentation.

## Git And Auth

- Do not append `Co-Authored-By`, `Generated with Codex`, or similar AI attribution trailers to commit messages or PR bodies unless the user explicitly asks.
- The existing Claude setup only allowed `gh auth *`. In Codex, treat GitHub auth as task-specific: check `gh auth status` first, and run `gh auth ...` only when the user request genuinely requires it.
- Do not revert or discard user changes unless the user explicitly asks.

## Tooling Habits

- Use `rg`/`rg --files` for searches.
- Keep edits scoped to the requested behavior and the repository's existing Gradle/Kotlin style.
- Do not edit generated build outputs under `build/`, `.gradle/`, `.kotlin/`, `.idea/`, or `local.properties`.
