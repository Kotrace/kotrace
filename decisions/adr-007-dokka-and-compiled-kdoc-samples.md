# ADR-007 — Adopt Dokka; KDoc examples are compiled `@sample`s, not prose snippets

- **Date:** 2026-08-22
- **Status:** Accepted (implemented — Dokka v2 applied to core; the `ReportAdapter` self-gate is a `@sample`)
- **Affects:** root `build.gradle.kts`, `gradle/libs.versions.toml`, `TraceAdapter.kt` / `Report.kt` KDoc,
  new `src/test/kotlin/dev/kotrace/samples/ReportAdapterSamples.kt`, backlog **D3**

## Context

KDoc code examples were hand-written strings. Nothing compiled them, so they drifted from the real API — a
copy-paste example once read `if (status != TraceStatus.Error) return`, which does not compile (`ERROR` is
the constant). The canonical `ReportAdapter` self-gate is exactly the snippet a consumer pastes, so drift
there is high-impact.

kotrace ships no API documentation site, so there was also no rendered reference for consumers.

## Decision

Adopt **Dokka v2** on the core module and make the canonical KDoc example a compiled **`@sample`** rather
than an inline literal:

- Dokka Gradle plugin v2 (`org.jetbrains.dokka`, via the version catalog).
- A samples root registered on the `main` source set:
  `dokkaSourceSets.named("main") { samples.from("src/test/kotlin/.../ReportAdapterSamples.kt") }`.
- The sample (`ReportAdapterSamples.onReportSelfGate`) lives in **test source**, so `check` compiles it
  against the real API — a sample that stops matching the API fails to compile and breaks the build.
- `TraceAdapter.onReport` / `Report.kt` reference it with `@sample dev.kotrace.samples.ReportAdapterSamples.onReportSelfGate`;
  the inline literal snippet is removed from the prose, so there is no un-compiled example left to drift.
- `failOnWarning` is enabled on the HTML publication, so a dangling KDoc link or an unresolved `@sample`
  reference fails `dokkaGenerate` (run in CI) instead of warning silently. Existing dangling links were
  cleaned to make this pass.

## Consequences

- **The drift class is closed for the sample.** The authoritative example is compiled by `check`; the exact
  bug that motivated this (a snippet that does not compile) can no longer reach `main` unnoticed.
- **Two enforcement layers.** The sample *code* compiling is enforced locally by `test`; the `@sample`
  *reference* resolving and links being valid is enforced by `dokkaGenerate` + `failOnWarning`, which CI must
  run. `dokkaGenerate` is deliberately **not** wired into `check` — it adds ~10s to every build, and the
  local `test` compile already covers the primary (code-compiles) guarantee.
- **API docs exist.** `./gradlew dokkaGenerate` produces the HTML site as a side benefit.
- **Pattern for future examples:** a KDoc example goes in `ReportAdapterSamples` (or a sibling) and is
  referenced with `@sample`; inline literal code in KDoc is discouraged.
- **Scope:** Dokka is applied to the core module only, where the flagged snippets live. `:kotrace-okhttp` /
  `:kotrace-room` can adopt it later for a full multi-module docs site; not needed for D3.

## Rejected alternatives

- **Remove the literal snippet, point to `:demo` in prose (no Dokka).** Zero-infra and closes the drift
  surface, but gives no rendered, clickable, or reference-checked example, and core KDoc cannot link across
  modules to `:demo` anyway. Rejected once Dokka was chosen — `@sample` delivers the rendered example *and*
  the compile check.
- **Wire `dokkaGenerate` into `check`.** Fully local enforcement, but a ~10s tax on every build for a
  guarantee the `test` compile already gives for the code itself. Left to CI.
- **A dedicated compiled `samples` source set.** Cleaner separation than reusing test source, but more Gradle
  wiring (a new source set + its classpath) for one sample. Reuse of test source is enough; revisit if
  samples grow.
