# ADR-030: README snippets are compiled; every published module's KDoc is checked

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** documentation and tooling only:
  - README snippet tags;
  - `ReadmeSnippets.kt` in the core, `kotrace-okhttp` and `kotrace-room` test sources;
  - `ReadmeSnippetsTest`;
  - the Dokka plugin on `kotrace-okhttp` and `kotrace-room`;
  - README, AGENTS and ARCHITECTURE corrections;
  - `span` KDoc and a sample for use inside `flow { }`.

  No API or behavior change.
- **Extends:** [ADR-007](adr-007-dokka-and-compiled-kdoc-samples.md), which compiled the KDoc samples but left
  README code as prose.

## Context

ADR-007 made KDoc examples compiled `@sample`s, so they cannot drift. The README, the first thing a consumer
copies from, had no such guard, and it had drifted:

- It documented `Span.log(level)`, but the verb takes `attributes`; kotrace has no level parameter.
- It said the default policy keeps `INFO`+`WARN`+`ERROR`, but the default accepts every non-sensitive event.
- It said a `span` under `GlobalScope` is never collected, which has been false since auto-root (ADR-013).
- It said the build needs JDK 11+. Gradle 9.3 needs 17+ to launch, and the daemon and benchmarks run on 21.
- Its OkHttp example named a span `http.GET /pricing`, putting the URL path into `operation`, which ADR-021
  exists to prevent.

The integration modules had no Dokka at all, so a broken KDoc link there went unnoticed. Separately, nothing
warned that `emit` inside `span { }` in a `flow { }` fails with "Flow invariant is violated". `span` runs its
block under `withContext`.

## Decision

- **Compiled twins.** Every ```` ```kotlin ```` block in the README is preceded by `<!-- snippet: name -->`.
  Build-script blocks are tagged `gradle`. Each Kotlin snippet is duplicated verbatim, imports aside, as a
  `// README snippet: name` region in a `ReadmeSnippets.kt` test source of the module whose API it uses (core,
  `kotrace-okhttp`, `kotrace-room`). `check` compiles those regions against the real API; only the stubs around
  them are invented, and they are never executed.
- **Drift test.** `ReadmeSnippetsTest` (core) fails when:
  - a README Kotlin block is untagged;
  - a tagged snippet has no compiled region;
  - a region is orphaned;
  - a README block and its region differ, after normalizing imports and indentation.
- **Dokka per module.** `kotrace-okhttp` and `kotrace-room` apply Dokka with warnings as errors, and
  `./gradlew dokkaGenerate` builds all three. Aggregating them into the root publication was tried and
  rejected: DGP v2 dropped the root project's own module from the aggregate.
- **Flow guidance.** The `span` KDoc explains the flow invariant, a `spanInFlowUsage` sample shows the correct
  shape (trace the work, `emit` outside the span), and a test pins both the sample and the failure it avoids.
- The stale statements listed above are corrected.

## Options considered

- **Extract README code into files and include them in the README: rejected.** GitHub renders no includes, so
  the README would need a generation step that everyone must remember to run.
- **Compile the README blocks directly with a Kotlin scripting harness: rejected.** The snippets depend on
  consumer-side names (an adapter, `BuildConfig`, a Room database) that need stubs anyway. A plain source file
  plus a text comparison is simpler and runs in the normal `check`.
- **A `Flow.traced(name)` operator: deferred.** The guidance and sample cover the failure. An operator is new
  API surface to design separately.

## Consequences

- A README example that stops compiling, or that is edited without its twin, now fails `check`.
- Editing a README snippet means editing two places. `ReadmeSnippetsTest` names the snippet that drifted.
- KDoc in the integration modules is held to the same standard as the core's.
