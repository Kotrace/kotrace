# ADR-027 — Published artifacts declare a consumer baseline, not this build's latest

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** the build only (`gradle/libs.versions.toml`, every published module's `build.gradle.kts`). It
  changes what the POM and Gradle module metadata declare, and which Kotlin metadata the classes carry. No source
  API change; `kotrace-okhttp` gains a null-safe read of `Response.body` so it compiles against OkHttp 4.
- **Related:** [ADR-023](adr-023-okhttp-trace-handoff.md) and [ADR-024](adr-024-bounded-body-capture.md) (the
  OkHttp code that must compile at the floor).

## Context

The build pinned nothing about its consumers. The published POMs (and `module.json`, as `requires`) declared
exactly the versions this repo builds with:

- `kotlin-stdlib` **2.4.10**, with classes carrying Kotlin **2.4** metadata. A consumer on Kotlin 2.1 or 2.2
  cannot read that metadata, so the build fails the moment the artifact is added.
- `kotlinx-coroutines-core` **1.11.0**, which transitively requires a matching stdlib.
- `okhttp-jvm` **5.5.0**, at `api` scope. An app on OkHttp 4.12, the version Retrofit 2.11 brings, was silently
  bumped a **major** version by adding a tracing library.

`kotrace-room` already avoided this for Room (`compileOnly`); the core and `kotrace-okhttp` did the opposite. The
POMs also had no name, license or source location.

## Decision

- **Kotlin:** the published modules (core, `kotrace-okhttp`, `kotrace-room`) compile with `languageVersion` and
  `apiVersion` **2.1** and `coreLibrariesVersion` **2.1.0**. Classes carry 2.1 metadata (`mv=[2,1,0]`), which a
  Kotlin 2.0+ compiler reads. The compiler itself stays current (2.4.x). Kotlin 2.4 no longer accepts language
  version 1.9, and 2.1 keeps a margin inside its supported window.
- **Coroutines:** declared, compiled **and tested** at **1.10.2**, the release built with Kotlin 2.1.
- **OkHttp:** declared, compiled and tested at **4.12.0**, still `api` (its types are on the module's surface).
  OkHttp 5 keeps binary compatibility with 4.x, and a consumer on 5.x keeps 5.x through Gradle's highest-version
  resolution. The one source difference, a nullable `Response.body` on 4, is handled null-safely.
- **OkHttp 5 is tested too (amendment).** Binary compatibility was an argument, not a test. `check` also runs
  `:kotrace-okhttp:testOkHttp5`: the same test classes, compiled at the floor, on a runtime with every OkHttp
  artifact at the newest 5.x (`okhttp5Test` in the catalog, test only), the way a consumer on OkHttp 5 runs the
  jar. The task fails if resolution did not pick 5.x.
- **The floor is what the build runs.** The catalog versions *are* the baseline, so every compile and every test
  exercises the declared minimum. The build cannot start using newer API without a failing compile.
- **POM identity:** every publication (BOM included) gets a name, description, URL, MIT license, developer and
  SCM, configured once in the root build.

## Options considered

- **`compileOnly` OkHttp — rejected.** OkHttp types are on `TracingInterceptor`/`TracingCallFactory`'s public
  surface, so `api` is the honest scope, and a declared floor at 4.12 already stops the forced major bump.
- **Compile at the floor but test on the latest — rejected.** It verifies neither end fully. Testing at the floor
  proves the declared minimum works. The newest versions are the consumer's upgrade, and binary compatibility
  covers them.
- **Keep the Kotlin 2.4 language level — rejected.** It locks out every consumer that is not on the newest
  compiler, for no feature the code needs. The code already compiles at 2.1.

## Consequences

- Consumers on Kotlin 2.0+, coroutines 1.10.2+, and OkHttp 4.12+ can adopt kotrace without upgrades.
- Raising any floor is now a visible, deliberate edit to `libs.versions.toml`, reviewed as a compatibility
  change.
- The demo and benchmarks also run on the floor versions. They are unpublished, so this is harmless.
- Not yet covered: a consumer-build smoke test (Gradle TestKit on the oldest supported Kotlin). The class-file
  metadata version and the generated POM were checked by hand for this change.
