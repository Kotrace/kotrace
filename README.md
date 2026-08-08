# kotrace

A tiny, coroutine-native span tracer for Kotlin/JVM. Builds a per-flow tree of spans over
`CoroutineContext`, marks the failing path, and emits a **W3C `traceparent`** header for
client↔backend correlation — with **no OpenTelemetry SDK** on either side. The wire format is the
contract, so an OTel upgrade later is additive.

- `Span` / `SpanStatus` — one unit of work and its outcome.
- `trace(name) { … }` — opens a child of the ambient span, closes it on return, marks it `ERROR` and
  rethrows on failure. Instrumentation only: it observes and rethrows, never swallows.
- `SpanContext` — carries the current span ambiently; also mirrors it to a `ThreadLocal` so non-suspend
  code on the coroutine's thread can read it via `currentSpan()`.
- `SpanCollector` — trace-wide sink (concurrent-safe) so a reporter can render the whole tree.
- `Span.toTraceparent()` / `TRACEPARENT_HEADER` — the W3C wire header.
- `List<Span>.formatTree()` — an indented debug rendering.
- `SpanCollector.birthplaceSpan()` — the deepest `ERROR` span carrying a throwable.

## Coordinates

```kotlin
// settings.gradle.kts — anonymous, no credentials
maven { url = uri("https://maven.luxgroup.dev") }

// build.gradle.kts
implementation("dev.luxgroup:kotrace:0.1.0")

// OkHttp integration only — the traceparent-on-the-wire glue. Pulls the core transitively.
implementation("dev.luxgroup:kotrace-okhttp:0.1.0")
```

Core imports are `dev.luxgroup.kotrace.*`; the OkHttp module is `dev.luxgroup.kotrace.okhttp.*`. The
core is pure Kotlin (`kotlinx-coroutines` only) — take `kotrace-okhttp` only if you use OkHttp, so a
Ktor or pure-JVM consumer never drags an HTTP client.

## How to run (build + test)

```bash
./gradlew test        # unit tests
./gradlew build       # compile + test + jar
```

Requires a JDK 11+. No secrets or env vars needed to build or test.

## Where to start

`src/main/kotlin/dev/luxgroup/kotrace/Trace.kt` is the entry point — `trace {}` and `collectTrace {}`
are the whole public verb surface. Read `Span.kt` and `SpanContext.kt` next for the data + propagation
model. Tests in `src/test/kotlin/dev/luxgroup/kotrace/` double as usage examples (`TraceTreeTest` is the
guided tour).

For OkHttp, `kotrace-okhttp/` holds `TracingCallFactory` (tags the outgoing request with the active
span, from the coroutine thread) + `TracingInterceptor` (reads the tag on OkHttp's thread and writes
`traceparent`). The split is deliberate — the interceptor cannot see the coroutine context. Wrap the
client's `Call.Factory` with the factory and add the interceptor; both are inert with no active span.

Example:

```kotlin
val spans = collectTrace {
    trace("handler") {
        trace("service") {
            trace("store.write") { error("boom") }
        }
    }
}
println(spans.formatTree())
```

## Demo

`:demo` is a runnable, self-contained tour — a mobile "checkout" flow as a tree of spans, a parallel
`async` fan-out, and one **real** OkHttp call whose `traceparent` is captured by a throwaway in-process
server (proving the client↔backend stitch with no OpenTelemetry). It is never published.

```bash
./gradlew :demo:run -q
```

Prints the `formatTree()` rendering, the `traceparent` the backend saw (its trace id matches the tree),
and the birthplace span. Source: `demo/src/main/kotlin/dev/luxgroup/kotrace/demo/Main.kt`.

## How to contribute

- One public verb per concern; keep the surface minimal. Coroutine types are `api`, so treat them as
  part of the contract.
- Spans carry **static symbols only** — never user data (a span can reach a crash report or the wire).
- Every public function ships with a test. Plain JUnit4.

## Publishing

`dev.luxgroup:kotrace` is served as a **static Maven repository over GitHub Pages** at
`https://maven.luxgroup.dev` (a dedicated repo, custom domain via `CNAME`). Consumers pull anonymously
— a static repo needs no auth. Publishing is manual (no CI Runner):

1. Clone the dedicated Maven repo locally.
2. Point the build at it (in `~/.gradle/gradle.properties` or via `-P`):
   ```properties
   kotrace.maven.repo.dir=/absolute/path/to/luxgroup-maven-checkout
   ```
3. Bump `version` in `build.gradle.kts`, then:
   ```bash
   ./gradlew publishAllPublicationsToLuxGroupMavenRepository
   ```
4. Commit + push the Maven repo. GitHub Pages serves the new files.

## Where to go when stuck

Open an issue on the GitHub project. Licensed under the [MIT License](LICENSE).
