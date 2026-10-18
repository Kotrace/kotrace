# ADR-027: Published artifacts declare a consumer baseline, not this build's latest

- **Date:** 2026-09-25
- **Status:** Accepted
- **Affects:** the build only (`gradle/libs.versions.toml`, every published module's `build.gradle.kts`). It
  changes what the POM and Gradle module metadata declare, and which Kotlin metadata the classes carry. No source
  API change; `kotrace-okhttp` gains a null-safe read of `Response.body` so it compiles against OkHttp 4.
- **Related:** [ADR-023](adr-023-okhttp-trace-handoff.md) and [ADR-024](adr-024-bounded-body-capture.md) (the
  OkHttp code that must compile at the floor).

## Context

The build pinned nothing about its consumers. The published POMs (and `module.json`, as `requires`) declared
exactly the versions this repository builds with:

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

- **`compileOnly` OkHttp: rejected.** OkHttp types are on `TracingInterceptor`/`TracingCallFactory`'s public
  surface, so `api` is the honest scope, and a declared floor at 4.12 already stops the forced major bump.
- **Compile at the floor but test on the latest: rejected.** It verifies neither end fully. Testing at the floor
  proves the declared minimum works. The newest versions are the consumer's upgrade, and binary compatibility
  covers them.
- **Keep the Kotlin 2.4 language level: rejected.** It locks out every consumer that is not on the newest
  compiler, for no feature the code needs. The code already compiles at 2.1.

## Consequences

- Consumers on Kotlin 2.0+, coroutines 1.10.2+, and OkHttp 4.12+ can adopt kotrace without upgrades.
- Raising any floor is now a visible, deliberate edit to `libs.versions.toml`, reviewed as a compatibility
  change.
- The demo and benchmarks also run on the floor versions. They are unpublished, so this is harmless.
- Not yet covered: a consumer-build smoke test (Gradle TestKit on the oldest supported Kotlin). The class-file
  metadata version and the generated POM were checked by hand for this change.

## Amendment (2026-10-17): the JDK and Android API floors, lower Room floors, one place for the conventions

"The build cannot start using newer API" held for the declared libraries, not for the platform. `jvmTarget = 11`
sets only the bytecode version: the compiler still resolved calls against the JDK it ran on (21, the Gradle
daemon's), so a call added in Java 12 or later compiled, passed every test on 21, and would throw
`NoSuchMethodError` on a consumer's Java 11. A probe calling `Thread.threadId()` (Java 19) in the core compiled
cleanly. Android had the same gap: the core and `kotrace-okhttp` run on Android apps, and nothing checked them
against Android's API levels.

- **Java 11 is an API floor too.** The JVM modules compile with `-Xjdk-release=11`, so that probe is now an
  unresolved reference.
- **Android API 21 is an API floor for every module.** A scan of the shipped bytecode against the SDK's
  `api-versions.xml` found `ThreadLocal.withInitial` (API 26), `AtomicReference.updateAndGet` and the
  `RuntimeException` constructor taking `writableStackTrace` (API 24): an app below API 26 crashed on its first
  kotrace call. They are replaced by a `ThreadLocal` subclass, a compare-and-set loop and an overridden
  `fillInStackTrace`. The `Long.hashCode` and `Boolean.hashCode` of data classes stay: D8 rewrites them at
  `--min-api 21`, checked on the dexed core jar. Room's lint does not see the core's sources even with
  `checkDependencies`, so `kotrace.conventions` gives each JVM module a `checkAndroidApi` task, run by `check`:
  it looks every class, method and field reference in the jar up in `api-versions.xml` of the compile SDK, and
  fails on one newer than `androidMinSdk` or absent from Android. An inherited member counts the level of each
  superclass or interface link it comes through (`URLClassLoader` implements `Closeable` only from API 24), and
  one referenced through the jar's own subclass is resolved to the platform type that declares it. Lambda and
  string-template bootstraps (`java/lang/invoke`) and the backported `hashCode` calls pass; any other call into
  `java/lang/invoke` is checked.
- **Room and Android floors are declared, and lowered.** `kotrace-room` required `minSdk` 28 for no technical
  reason (Room 2.8.4 itself requires 23); it now requires **21**. It is compiled and tested against Room
  **2.6.1**, the last release before Room's Kotlin Multiplatform rewrite (2.7), which many apps have not taken;
  the module calls only `QueryCallback` and `setQueryCallback`. Room stays `compileOnly` and the consumer keeps
  its version. A consumer below `minSdk` 21 fails at manifest merge. The README states both.
- **Coroutines and OkHttp stay.** The build also passes on coroutines 1.8.1 and OkHttp 4.10.0, but the apps those
  would admit are few, and every floor lowered is one more version to keep testing.
- **The Kotlin baseline rises to 2.2.** Kotlin 2.4 reports language versions 2.0 and 2.1 as deprecated ("Update
  the version to 2.2"); a quiet build (`-q`) hid that warning, so the "margin inside its supported window" above
  no longer held, and a later compiler that drops 2.1 would have forced the raise during a toolchain upgrade. It
  is raised now, while 0.5.0 is unreleased and already breaking: `languageVersion` and `apiVersion` **2.2**,
  `coreLibrariesVersion` **2.2.0**, classes carrying `mv=[2,2,0]`, which a Kotlin **2.1+** compiler reads. The
  compiler is quiet again, the ABI dumps are unchanged (`jvmDefault` was already pinned to the 2.2 default), and
  coroutines 1.10.2 needs only stdlib 2.1. A consumer on Kotlin 2.0 now has to upgrade its compiler.
- **The conventions live in one plugin.** Java 11, the consumer baseline, the ABI gate, the opt-ins, Dokka's
  `failOnWarning` and the POM were copied into each module's build file, which a new module (Ktor, a KMP target)
  could miss. `build-logic`'s `kotrace.conventions` now applies them to every module next to the plugins it uses;
  each POM description is the module's `description`.
