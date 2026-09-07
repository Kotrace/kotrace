# kotrace

A tiny, coroutine-native span tracer for Kotlin/JVM. Builds a per-flow tree of spans over
`CoroutineContext`, marks the failing path, and emits a **W3C `traceparent`** header for
client↔backend correlation — with **no OpenTelemetry SDK** on either side. The wire format is the
contract, so an OTel upgrade later is additive.

> Design & rationale (why the pieces are shaped this way, and vs OpenTelemetry): **[ARCHITECTURE.md](ARCHITECTURE.md)**.

- `Span` / `SpanStatus` — one unit of work and its outcome.
- `span(name) { … }` — opens a child of the ambient span, closes it on return, marks it `ERROR` and
  rethrows on failure. Instrumentation only: it observes and rethrows, never swallows. (Renamed from
  `trace` — it opens a span, not a trace; ADR-003.)
- `SpanContext` — carries the current span ambiently; also mirrors it to a `ThreadLocal` so non-suspend
  code on the coroutine's thread can read it via `currentThreadSpan()`.
- `currentSpan()` (suspend, reads `coroutineContext`) / `currentThreadSpan()` (non-suspend, reads the
  `ThreadLocal` mirror) — the two span getters. A suspend caller uses the first; an off-coroutine bridge
  (OkHttp/Room) the second.
- `SpanCollector` — trace-wide buffer (concurrent-safe) holding every span in one trace, so the whole tree
  can be rendered or fanned out at the end. Not a sink — the adapters are (see `reportTrace`).
- `Span.log(level) { … }` / `Span.addNamed(name)` / `Span.addException(cause)` — the event verbs, all
  extensions on `Span`; call them on a span you got from a getter (`currentSpan()?.log { }`) or hold
  directly. Each adapter's `TracePolicy` filters logs at fan-out (a default keeps `INFO`+`WARN`+`ERROR`);
  events are stored unconditionally and the message lambda is not built for a log no adapter accepts.
- `TraceRecord` / `ReportAdapter` / `SpanCollector.reportTrace(status)` — fan a trace out at its end as one
  searchable record per log line + the birthplace throwable, each tagged `trace_id` / `span_id` /
  `parent_span_id`, to every `ReportAdapter` in the resolved `TraceConfig`. Backends group by any of the ids.
  The searchable counterpart to `renderTree`.
- `Kotrace.install(adapters)` / `Kotrace.install { … }` — the **process-wide fan-out config** (ADR-010),
  installed once at startup (read-only after; a second install throws; the provider overload resolves lazily
  on first fan-out, DI-friendly). Every path resolves `currentThreadConfig() ?: Kotrace.defaultConfig()`, so
  a per-flow `TraceConfig` overlaid on the context **overrides** the global for that flow, while span-less
  emits and non-suspend entrypoints — which carry no context — still reach the adapters. Report needs a
  per-flow `SpanCollector`, so a collector-less flow is live-only by construction.
  - **Registering adapters — build the list, no builder DSL.** kotrace takes a plain `List<TraceAdapter>`;
    the provider overload (`install { … }`) plus stdlib `buildList` *is* the conditional-registration idiom —
    the equivalent of Retrofit's `addInterceptor`, from the standard library, so kotrace adds no builder of
    its own:
    ```kotlin
    Kotrace.install {
        buildList {
            add(CrashAdapter(reporter))
            add(AnalyticsAdapter(analytics))
            if (BuildConfig.DEBUG) add(LiveLogAdapter(logcat)) // conditional, reads like addInterceptor
        }
    }
    ```
  - **`Kotrace.strictWhenUninstalled()`** (ADR-011) — opt-in fail-fast: once armed, an emit that resolves
    **no** config (nothing installed, no override) throws instead of silently no-op'ing. Off by default;
    arm it **before** `install`, in a debug build only, to catch a forgotten or too-late install. Deliberately
    running no sinks stays valid as `install(emptyList())` (a real, empty config), not as never installing.
- `emitLog { … }` / `emitNamed(name)` / `emitException(cause, info)` — **span-less emit verbs** (ADR-010),
  the orphan counterparts of the `Span.*` verbs, for an occurrence outside any span (app lifecycle, a push
  callback, any non-coroutine entry). **Live-only**, fanned through the resolved config and gated by policy;
  the record has null identity and carries a `scope_id` when a scope is active. `emitException(cause, info)`
  — and `addException(cause, info)` — carry record-level `ExceptionRecord.info` while the exception event
  stays object-only (ADR-005), so an explicit report reaches live adapters unconditionally.
- `withScope(scopeId) { … }` / `scope_id` — a **correlation umbrella above `trace_id`** (`scope ⊇ trace ⊇
  span`, ADR-010). A live-only ambient key: a span or emit inside a scope stamps `scope_id` onto its records
  (a span alongside `trace_id`, an orphan emit alone), grouping many traces + their orphans under one
  session/journey key. **Not a span** — no reportable tree, no duration/outcome; `toJson` emits `scope_id`
  only when present (absent = today's shape). Read it with `currentScopeId()` (suspend) / `currentThreadScopeId()`
  (the `ThreadLocal` mirror, for non-suspend emits).
- `TraceRecord.toJson()` — a one-line, snake_case JSON rendering (no serialization dependency, values
  escaped) for a JSON log pipeline (ELK, Loki) to ingest `trace_id`/`span_id` as queryable fields.
- `TraceLink` — a birth-set, cross-trace edge: `span(name, links = listOf(TraceLink(otherTraceId)))` points
  the span at another *trace* by its `trace_id` (the OTel span-link shape), joining two separate trees where
  `Span.parentId` (an in-tree edge) cannot. Trace-level, `trace_id` only — no `span_id` (ADR-009). It rides
  every record off the linking span and surfaces in `toJson` as a nested `links` array; an unlinked span is
  unchanged on the wire. `attributes` are static symbols only, same PII rule as spans.
- `Span.toTraceparent()` / `TRACEPARENT_HEADER` — the W3C wire header.
- `List<Span>.renderTree()` — an indented debug rendering, span logs interleaved. An **unredacted** human
  read (renders `sensitive` messages and raw exception text), so it is gated by `@UnredactedTraceRead`: a
  call site must `@OptIn`, and must never route the output to a sink (ADR-008).

## Coordinates

```kotlin
// settings.gradle.kts — anonymous, no credentials
maven { url = uri("https://maven.kotrace.dev") }

// build.gradle.kts
implementation("dev.kotrace:kotrace:0.3.0")

// OkHttp integration only — the traceparent-on-the-wire glue. Pulls the core transitively.
implementation("dev.kotrace:kotrace-okhttp:0.3.0")

// Room integration only (Android) — logs each query's SQL onto the active span. Pulls the core.
implementation("dev.kotrace:kotrace-room:0.3.0")
```

Or align every module through the BOM — declare its version once, drop the version off each dependency:

```kotlin
implementation(platform("dev.kotrace:kotrace-bom:0.3.0"))
implementation("dev.kotrace:kotrace")
implementation("dev.kotrace:kotrace-okhttp")
```

Core imports are `dev.kotrace.*`; the OkHttp module is `dev.kotrace.okhttp.*` and the Room module is
`dev.kotrace.room.*`. The core is pure Kotlin (`kotlinx-coroutines` only) — take `kotrace-okhttp` only if
you use OkHttp and `kotrace-room` (Android) only if you use Room, so a Ktor or pure-JVM consumer never
drags an HTTP client or Android.

## How to run (build + test)

```bash
./gradlew test        # unit tests
./gradlew build       # compile + test + jar
```

Requires a JDK 11+. No secrets or env vars needed to build or test.

## Where to start

`src/main/kotlin/dev/kotrace/Trace.kt` is the entry point — `span {}` is the whole public verb surface.
Read `Span.kt` and `SpanContext.kt` next for the data + propagation model. Tests in
`src/test/kotlin/dev/kotrace/` double as usage examples (`TraceTreeTest` is the guided tour).

For OkHttp, `kotrace-okhttp/` holds `TracingCallFactory` (tags the outgoing request with the active
span, from the coroutine thread) + `TracingInterceptor` (reads the tag on OkHttp's thread and writes
`traceparent`). The split is deliberate — the interceptor cannot see the coroutine context. Wrap the
client's `Call.Factory` with the factory and add the interceptor; both are inert with no active span.

For Room, `kotrace-room/` (Android) holds `TracingQueryCallback` + the `RoomDatabase.Builder.tracing()`
installer — SQL onto the active span, resolved via `currentThreadSpan()` (Room runs under the caller's coroutine,
so the `ThreadContextElement` mirror reaches its executor thread).

Example — seed a `SpanCollector` on the context, run the flow, then read the tree:

```kotlin
val collector = SpanCollector()
withContext(collector) {                     // rides the CoroutineContext down every child span
    runCatching {                            // a real reporter lets the throwable propagate instead
        span("handler") {
            span("service") {
                span("store.write") { error("boom") }
            }
        }
    }
}
@OptIn(UnredactedTraceRead::class)           // human debug read — never route this to a sink (ADR-008)
println(collector.spans.renderTree())
```

## Using with OkHttp

Two pieces, installed together on the client. `TracingInterceptor` writes the `traceparent`, but runs on
OkHttp's own thread where the coroutine `SpanContext` is invisible — so `TracingCallFactory` first tags
the request with the active span from the coroutine thread (at `newCall`), and the interceptor reads that
tag. Both are inert without an active span, so wrap every client:

```kotlin
import dev.kotrace.okhttp.TracingCallFactory
import dev.kotrace.okhttp.TracingInterceptor

// 1. Add the interceptor to the client.
val http = OkHttpClient.Builder()
    .addInterceptor(TracingInterceptor())
    .build()

// 2. Wrap the client (a Call.Factory) with the factory — use THIS to make calls.
val calls: Call.Factory = TracingCallFactory(http)

// 3. Call inside a span. traceparent goes on the wire; the span emits http.status as info
//    (on every record off the span, not a filter key — ADR-001), and a non-2xx flips it to ERROR.
suspend fun price(): String = span("http.GET /pricing") {
    calls.newCall(Request.Builder().url(pricingUrl).build())
        .execute().use { it.body?.string().orEmpty() }
}
```

**Retrofit**: pass the wrapped factory via `Retrofit.Builder().callFactory(TracingCallFactory(http))`
(not `.client(...)`) so every generated call is tagged from the coroutine thread.

Order between the factory and interceptor does not matter (tag is placed before dispatch, read during
the chain). A call made with no active span passes through untagged and unmodified.

See `demo/…/Main.kt` for a full end-to-end run.

## Using with Room

One call in the builder. `db.tracing()` installs a `QueryCallback` that logs each statement's SQL onto
the active span. It reads the span via `currentThreadSpan()` — no request-tag bridge like OkHttp needs — because
Room runs a suspend query under the caller's coroutine (`withContext`), and `SpanContext` is a
`ThreadContextElement`, so the span is mirrored onto Room's executor thread while the query runs. The
callback is installed with a **direct executor** so it fires inline there.

```kotlin
import dev.kotrace.room.tracing

Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .tracing(mapOf("level" to "DEBUG"))   // attributes stamped on each SQL log; kotrace holds no level taxonomy
    .build()
```

Only the parameterised SQL text (symbols, `?` placeholders) is logged — never the bound values, which can
carry user data. kotrace has no severity taxonomy, so the level is just a `"level"` attribute you pass; SQL
is high-volume, so tag it at a level your capture policy drops by default (e.g. `DEBUG`).

## Span-less emits & scopes

An emit outside any span — an app-lifecycle hook, a push-notification callback, any non-coroutine entry —
has no span to attach to and no `TraceConfig` in context. Install the process-wide config once, then emit
from anywhere; `withScope` adds a correlation umbrella above `trace_id` so orphan emits and whole groups of
operations correlate under one session/journey key (ADR-010).

```kotlin
import dev.kotrace.Kotrace
import dev.kotrace.withScope
import dev.kotrace.event.emitException
import dev.kotrace.event.emitNamed

// 1. Once at startup (or Kotrace.install { diGraph.adapters() } to resolve lazily under DI).
Kotrace.install(listOf(myLiveAdapter))

// 2. Orphan emit — no coroutine, no span needed. Fans live to the installed adapters.
fun onPushReceived(cause: Throwable) {
    emitNamed("push_received", mapOf("channel" to "promo"))
    emitException(cause, mapOf("report" to "explicit"))   // info rides ExceptionRecord.info
}

// 3. A scope — everything inside carries scope_id = "session-42"; a span additionally carries its trace_id.
suspend fun handleSession() = withScope("session-42") {
    emitNamed("session_started")                 // scope_id only
    span("checkout") { /* … */ }                 // trace_id + scope_id
}
```

A flow that needs *different* sinks overlays a per-flow `TraceConfig` on its context
(`withContext(collector + TraceConfig(...))`), which overrides the global for that flow.

## Demo

`:demo` is a runnable, self-contained tour — a mobile "checkout" flow as a tree of spans, a parallel
`async` fan-out, one **real** OkHttp call whose `traceparent` is captured by a throwaway in-process
server (proving the client↔backend stitch with no OpenTelemetry), and a follow-up user-report flow in its
own trace that links back to the failed checkout by `trace_id` (`TraceLink`, ADR-009). It is never published.

```bash
./gradlew :demo:run -q
```

Prints the `renderTree()` rendering, the `traceparent` the backend saw (its trace id matches the tree),
the birthplace span, and the report trace whose `links` array carries the checkout's `trace_id`.
Source: `demo/src/main/kotlin/dev/kotrace/demo/Main.kt`.

## How to contribute

- One public verb per concern; keep the surface minimal. Coroutine types are `api`, so treat them as
  part of the contract.
- Spans carry **static symbols only** — never user data (a span can reach a crash report or the wire).
- Every public function ships with a test. Plain JUnit4.

## Publishing

`dev.kotrace:kotrace` is served as a **static Maven repository over GitHub Pages** at
`https://maven.kotrace.dev` (a dedicated repo, custom domain via `CNAME`). Consumers pull anonymously
— a static repo needs no auth. Publishing is manual (no CI Runner):

1. Clone the dedicated Maven repo locally.
2. Point the build at it (in `~/.gradle/gradle.properties` or via `-P`):
   ```properties
   kotrace.maven.repo.dir=/absolute/path/to/kotrace-maven-checkout
   ```
3. Bump `version` in `build.gradle.kts`, then:
   ```bash
   ./gradlew publishAllPublicationsToKotraceMavenRepository
   ```
4. Commit + push the Maven repo. GitHub Pages serves the new files.

## Where to go when stuck

Open an issue on the GitHub project. Licensed under the [MIT License](LICENSE).
