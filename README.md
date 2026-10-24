# kotrace

A tiny, coroutine-native span tracer for Kotlin/JVM. Builds a per-flow tree of spans over
`CoroutineContext`, marks the failing path, and emits a **W3C `traceparent`** header for
client↔backend correlation, with **no OpenTelemetry SDK** on either side. The wire format is the
contract, so an OpenTelemetry upgrade later is additive.

> Design & rationale (why the pieces are shaped this way, and versus OpenTelemetry): **[ARCHITECTURE.md](ARCHITECTURE.md)**.

- `Span` / `SpanStatus`: one unit of work and its outcome. A span carries two string maps (ADR-001/032):
  `traits`, its fixed filter dimensions, set when it opens and read only by `TracePolicy.acceptsSpan`; and
  `attributes`, values a sink should see (`span(…, attributes = …)` when it opens, `putAttribute` later), which
  ride every record as `spanAttributes` and are never a filter key.
- `span(name) { … }`: opens a child of the ambient span, closes it on return, marks it `ERROR` and
  rethrows on failure. Instrumentation only: it observes and rethrows, never swallows. A failure carried as a
  returned value counts too: `returnVerdictClassifier` marks the span (ADR-020) and `returnedOutcome` sets the
  trace's outcome at an auto-root (ADR-016).
- `SpanContext`: carries the current span ambiently; also mirrors it to a `ThreadLocal` so non-suspend
  code on the coroutine's thread can read it via `currentThreadSpan()`.
- `currentSpan()` (suspend, reads `coroutineContext`) / `currentThreadSpan()` (non-suspend, reads the
  `ThreadLocal` mirror): the two span getters. A suspend caller uses the first; an off-coroutine bridge
  (OkHttp/Room) the second.
- `SpanCollector`: trace-wide buffer (concurrent-safe) holding every span in one trace, so the whole tree
  can be rendered or fanned out at the end. Not a sink; the adapters are (see `reportTrace`).
- `Span.log(attributes) { … }` / `Span.addNamed(name)` / `Span.addException(cause)`: the event verbs, all
  extensions on `Span`; call them on a span you got from a getter (`currentSpan()?.log { }`) or hold
  directly. kotrace ranks no severity: a level is just an attribute you choose (`mapOf("level" to "INFO")`).
  Each adapter's `TracePolicy` filters at fan-out (the default accepts every non-sensitive event; a policy
  that wants only `INFO`+ says so in `acceptsEvent`). Events are stored unconditionally, and the message lambda
  is not built for a log no adapter accepts.
- `TraceRecord` / `ReportAdapter` / `SpanCollector.reportTrace(status)`: fan a trace out at its end as one
  searchable record per log line + the birthplace throwable, each tagged `trace_id` / `span_id` /
  `parent_span_id`, to every `ReportAdapter` in the resolved `TraceConfig`. Backends group by any of the ids.
  The status is `OK`, `ERROR` or `CANCELLED`. A `withTimeout` inside the span is `ERROR`; a `withTimeout` that
  the caller wraps around a top-level span cancels it, so the trace is `CANCELLED` with no exception record
  (ADR-022).
  The searchable counterpart to `renderTree`.
- `Kotrace.install(adapters)` / `Kotrace.install { … }`: the **process-wide fan-out configuration** (ADR-010),
  installed once at startup (read-only after; a second installation throws; the provider overload resolves lazily
  on first fan-out, dependency-injection-friendly), with the process-wide `returnVerdictClassifier`, the
  per-span event cap (`maxEventsPerSpan`, ADR-033) and the fallback scope (`fallbackScopeId`, ADR-038). Every path reads the `TraceConfig` on its context first and
  falls back to the installed one, so a per-flow `TraceConfig` **overrides** the global for that flow, while
  span-less emits and non-suspend entrypoints (which carry no context) still reach the adapters. Report needs a
  `SpanCollector`: a top-level suspend `span { }` **auto-roots** one and reports at its outcome (ADR-013), so
  the report boundary is implicit; a flow that only ever performs span-less emission (no top-level `span`) stays
  live-only by construction.
  - **Registering adapters: build the list, no builder DSL.** kotrace takes a plain `List<TraceAdapter>`;
    the provider overload (`install { … }`) plus stdlib `buildList` *is* the conditional-registration idiom:
    the equivalent of Retrofit's `addInterceptor`, from the standard library, so kotrace adds no builder of
    its own:
    <!-- snippet: install-buildlist -->
    ```kotlin
    Kotrace.install {
        buildList {
            add(CrashAdapter(reporter))
            add(AnalyticsAdapter(analytics))
            if (BuildConfig.DEBUG) add(LiveLogAdapter(logcat)) // conditional, reads like addInterceptor
        }
    }
    ```
  - **`Kotrace.strictWhenNotInstalled()`** (ADR-011): opt-in fail-fast: once armed, an emit that resolves
    **no** configuration (nothing installed, no override) throws instead of silently no-op'ing. Off by default;
    arm it **before** `install`, in a debug build only, to catch a forgotten or too-late install. Deliberately
    running no sinks stays valid as `install(emptyList())` (a real, empty configuration), not as never installing.
- `Kotrace.installFaultHook(hook)` / `FaultHook`: kotrace contains its own faults and those of the adapters and
  integrations, so telemetry never changes what the traced code returns or throws (ADR-014/034/035). The hook,
  installed once per process and ideally first (ADR-037), hears of each one with its `FaultPhase` and a
  `faultMessage` naming the step (`live.deliver`, `http.response.body`, ADR-036). An integration builds on the same
  rule through `runIsolated` (`@KotraceIntegrationApi`).
- `emitLog { … }` / `emitNamed(name)` / `emitException(cause, attributes)`: **span-less emit verbs** (ADR-010),
  the orphan counterparts of the `Span.*` verbs, for an occurrence outside any span (app lifecycle, a push
  callback, any non-coroutine entry). **Live-only**, fanned through the resolved configuration and gated by policy;
  the record has null identity and carries the active scope's `scope_id`, else the installed fallback's. `emitException(cause, attributes)`
  (and `addException(cause, attributes)`) carry record-level `ExceptionRecord.spanAttributes` while the exception event
  stays object-only (ADR-005), so an explicit report reaches live adapters unconditionally.
- `withScope(scopeId) { … }` / `scope_id`: a **correlation umbrella above `trace_id`** (`scope ⊇ trace ⊇
  span`, ADR-010). A live-only ambient key: a span or emit inside a scope stamps `scope_id` onto its records
  (a span alongside `trace_id`, an orphan emit alone), grouping many traces + their orphans under one
  session/journey key. **Not a span**: no reportable tree, no duration/outcome; `toJson` emits `scope_id`
  only when present. Read it with `currentScopeId()` (suspend) / `currentThreadScopeId()`
  (the `ThreadLocal` mirror, for non-suspend emits).
  - **The fallback scope** (ADR-038): `Kotrace.install(…, fallbackScopeId = { sessionId })` gives every record
    emitted outside any `withScope` a `scope_id`, including a span opened there and a synchronous emit from
    non-suspend code, so no record is unjoinable. An ambient `withScope` always wins. The provider is read at each
    record (rotate the session without re-installing), so keep it cheap; a throwing one leaves that record unscoped
    and reaches the `FaultHook` (`scope.fallback`). `currentScopeId()` / `currentThreadScopeId()` read the ambient
    scope only.
- `TraceRecord.toJson()`: a one-line, snake_case JSON rendering (no serialization dependency, values
  escaped) for a JSON log pipeline (ELK, Loki) to ingest `trace_id`/`span_id` as queryable fields.
- `TraceLink`: a birth-set, cross-trace edge: `span(name, links = listOf(TraceLink(otherTraceId)))` points
  the span at another *trace* by its `trace_id` (the OpenTelemetry span-link shape), joining two separate trees where
  `Span.parentId` (an in-tree edge) cannot. Trace-level, `trace_id` only, no `span_id` (ADR-009). It rides
  every record off the linking span and surfaces in `toJson` as a nested `links` array; an unlinked span's
  records carry none. `attributes` are static symbols only, same PII rule as spans.
- `Span.toTraceparent()` / `TRACEPARENT_HEADER`: the W3C wire header.
- `List<Span>.renderTree()`: an indented debug rendering, span logs interleaved. An **unredacted** human
  read (renders `sensitive` messages and raw exception text), so it is gated by `@UnredactedTraceRead`: a
  call site must `@OptIn`, and must never route the output to a sink (ADR-008).

## Coordinates

<!-- snippet: gradle -->
```kotlin
// settings.gradle.kts: anonymous, no credentials
maven { url = uri("https://maven.kotrace.dev") }

// build.gradle.kts
implementation("dev.kotrace:kotrace:0.5.0")

// OkHttp integration only: the traceparent-on-the-wire glue. Pulls the core transitively.
implementation("dev.kotrace:kotrace-okhttp:0.5.0")

// Room integration only (Android): logs each query's SQL onto the active span. Pulls the core.
implementation("dev.kotrace:kotrace-room:0.5.0")
```

Or align every module through the BOM: declare its version once, drop the version off each dependency:

<!-- snippet: gradle -->
```kotlin
implementation(platform("dev.kotrace:kotrace-bom:0.5.0"))
implementation("dev.kotrace:kotrace")
implementation("dev.kotrace:kotrace-okhttp")
```

Core imports are `dev.kotrace.*`; the OkHttp module is `dev.kotrace.okhttp.*` and the Room module is
`dev.kotrace.room.*`. The core is pure Kotlin (`kotlinx-coroutines` only); take `kotrace-okhttp` only if
you use OkHttp and `kotrace-room` (Android) only if you use Room, so a Ktor or pure-JVM consumer never
drags an HTTP client or Android.

**Minimum versions** (ADR-027). The artifacts are compiled against, and declare, the oldest versions they
support. A newer version in your build wins through normal dependency resolution; kotrace never upgrades yours.

| | Minimum |
|---|---|
| Kotlin (your compiler) | 2.1 (the artifacts carry Kotlin 2.2 metadata and declare `kotlin-stdlib` 2.2.0) |
| `kotlinx-coroutines-core` | 1.10.2 |
| OkHttp (`kotrace-okhttp`) | 4.12.0 (the tests also run on 5.x) |
| Room (`kotrace-room`) | 2.6.1 (`compileOnly`: your own version is used) |
| Android `minSdk` | 21 (`kotrace-room` declares it; the core and `kotrace-okhttp` call no newer Android API) |
| Java (JVM modules) | 11, bytecode and API |

**Upgrading from 0.5.** 0.6.0 is binary-breaking for callers of `Kotrace.install`: recompile against it.

- **Binary.** Both `Kotrace.install` overloads gained `fallbackScopeId` (ADR-038), so their 0.5.0 JVM signatures
  are gone. The parameter is defaulted, so source compiles unchanged.
- **Behavior.** Nothing changes until you pass a `fallbackScopeId`. With one, a record outside any `withScope`
  carries its `scope_id`, and `startSpan`'s default `scopeId` resolves it too; an explicit `scopeId = null` still
  opens a span with no scope.

**Upgrading from 0.4.** 0.5.0 is a breaking release: recompile against it.

- **Binary.** `TracingCallFactory` gained `spanName` (ADR-021). In Room, `TracingQueryCallback` gained `delegate`
  and `tracing()` gained `delegate` and `delegateExecutor` (ADR-028). In the core, the opt-in `startSpan` gained
  `parent`, `collector` and `scopeId` (ADR-023). Their 0.4.0 JVM signatures are gone. `withScope` now compiles into
  `ScopeContextKt`, beside `currentScopeId` and `currentThreadScopeId`.
- **Packages.** The core is split by concern (ADR-031); fix the imports of moved symbols:
  - `dev.kotrace.adapter`: `TraceAdapter`, `LiveAdapter`, `ReportAdapter`, `TracePolicy`, `FaultHook`,
    `FaultPhase`, `isJvmFatal`;
  - `dev.kotrace.report`: `reportTrace`, `renderTree`;
  - `dev.kotrace.bridge`: `startSpan`, `end`, `TraceHandoff`, `NonSuspendTracingBridge`.

  `span`, `withScope`, `Kotrace`, `Span`, `TraceConfig` and the rest of `dev.kotrace`, and all of
  `dev.kotrace.event`, are where they were.
- **Source.** `Kotrace.strictWhenUninstalled()` is renamed `Kotrace.strictWhenNotInstalled()` (ADR-011), and
  `AdapterFaultHook.onAdapterFault` is renamed `FaultHook.onFault` (ADR-014).
  A span's two string maps are renamed (ADR-032): the filter dimensions `Span.attributes` (the `attributes`
  parameter of `span`, `startSpan` and `TracingCallFactory`) are now `traits`, and the payload `Span.info` /
  `putInfo` is now `Span.attributes` / `putAttribute`. `TraceRecord.info` is `spanAttributes`,
  `Span.EVENTS_DROPPED_INFO` is `EVENTS_DROPPED_ATTRIBUTE`, and the `info` parameter of `addException` /
  `emitException` is `attributes`. **A policy reading `span.attributes` still compiles but now reads the
  payload: switch it to `span.traits`.**
  `Kotrace.resetForTest()` needs `@OptIn(KotraceTestApi::class)`, best granted to the test source set
  only, and reading `Span.events` or `Span.exception` needs `@OptIn(UnredactedTraceRead::class)` (ADR-028).
- **Behavior and wire.**
  - `toJson` renders the span's attributes under `"span_attributes"`, formerly `"info"` (ADR-032). A log query
    on `info.http.status` becomes `span_attributes.http.status`.
  - HTTP spans are named `http <METHOD>`, never the URL path, so the `operation` field changes (ADR-021).
  - `CANCELLED` means the root's coroutine was really cancelled. A timeout inside the operation is `ERROR`, and a
    cancelled span records no exception (ADR-022).
  - Each execution of a call opens its own HTTP span, emitting under the flow's configuration (ADR-023). A call its caller
    canceled records no exception, while one that hit its `callTimeout` does; both mark the span's `http.outcome`
    attribute (ADR-022/023).
  - `Level.BODY` is bounded and reads a response only as your code does (ADR-024).
  - An emit made from inside `onLive` is stored but not fanned out live again, and an HTTP call made there is not
    traced (ADR-025).
  - A report covers every root of a collector, not only the first (ADR-026).
  - `toTraceparent()` throws on an id that is not W3C (ADR-028).
  - A span keeps up to 1,000 events, dropping the oldest first (ADR-029). `TraceConfig.maxEventsPerSpan` and
    `Kotrace.install(maxEventsPerSpan = …)` change the cap (ADR-033).
  - A fault of kotrace's own never changes what `span { }` returns or throws: a span that fails to open runs its
    block untraced, and a report fault is contained. Both reach the `FaultHook` (`FaultPhase.SPAN_OPEN`,
    `FaultPhase.REPORT`). Only `strictWhenNotInstalled` still throws on purpose (ADR-034).
  - The OkHttp and Room integrations report the faults they contain to the `FaultHook` (`FaultPhase.HTTP`,
    `FaultPhase.DATABASE`), and an armed `strictWhenNotInstalled` now reaches their callers too (ADR-035).
  - `FaultHook.onFault(phase, adapter, cause)` is now `onFault(phase, faultMessage, cause)`: a symbol naming the
    step that failed (`live.deliver`, `http.response.body`) replaces the adapter (ADR-036). An adapter that tracks
    its own health catches its own faults.
  - The fault hook is no longer a `TraceConfig` or `Kotrace.install` parameter: install it once with
    `Kotrace.installFaultHook(hook)`, ideally first, so it also hears of a throwing `install` provider
    (`FaultPhase.INSTALL`) (ADR-037).

## How to run (build + test)

```bash
./gradlew test        # unit tests
./gradlew build       # compile + test + jar
```

Building needs JDK 17+ to launch Gradle 9.3; the Gradle daemon itself runs on JetBrains JDK 21, provisioned
automatically from `gradle/gradle-daemon-jvm.properties`, and `:benchmarks` uses a JDK 21 toolchain. The
published artifacts target Java 11 bytecode. An Android SDK is needed (`sdk.dir` in `local.properties` or
`ANDROID_HOME`): `kotrace-room` builds against it, and `checkAndroidApi` checks the JVM modules against its API
levels. No secrets are needed to build or test.

## Performance

The benchmark suite lives in the non-published `:benchmarks` module. The figures below are steady-state
JMH results from an Apple M1 Pro running Oracle JDK 21.0.3, G1GC and a fixed 1 GiB heap, using 3 forks ×
5 warm-up iterations × 5 measurement iterations, measured on 2026-10-17 against the 0.5.0 code (the full run,
with the cases the event timeline touches rerun after it). They characterize this JVM workload, not Android ART
or production end-to-end latency.

| Scenario | Result | Allocation |
|---|---:|---:|
| Empty auto-root, no configuration | 1.01 ± 0.02 µs/flow | 2.1 KiB/flow |
| Capture 100 logs, no sink | 3.73 ± 0.02 µs/batch | 28.8 KiB/batch |
| Report a 512-span balanced tree | 58.00 ± 0.71 µs/report | 191.3 KiB/report |
| Report a 128-span tree whose adapter skips the outcome | 1.01 ± 0.11 µs/report | 6.2 KiB/report |
| 16 workers × 100 logs on one shared span, under the cap | 550.49 ± 17.46 µs/flow | 5.30 MiB/flow |

Span and trace ids are encoded through a lookup table, so an empty auto-root costs about one microsecond. The
report walk runs only when an adapter reads its records, so an adapter that gates on the outcome costs ~1 µs at
128 spans. A report that is read freezes each span's timeline at report start (ADR-029) in O(1), since a snapshot
shares the timeline's array, and indexes the tree once, so its cost grows close to linearly with the spans.

The shared-span case is the main known hotspot: a span's timeline is copy-on-write, which is a good trade for
the common 0–10-event trace but becomes expensive with hundreds of events or many concurrent writers. A span
keeps `Span.MAX_EVENTS` (1,000) events, discarding the oldest first: named (analytics) events before logs, an
exception only once nothing else is left. It counts them in `Span.droppedEvents`,
so a span logging in a loop stays bounded (ADR-029). The cap is per span, set by
`TraceConfig.maxEventsPerSpan` when the span opens (ADR-033). Every append copies the timeline once, an append
past the cap included, since it discards and appends in the same copy: 2,000 logs onto one span cost 142 µs at a
cap of 100, 312 µs at the default 1,000 and 330 µs with nothing discarded, and the same 16 workers past the
default cap take 587 µs. Appends are serialized, so the cap holds exactly under concurrent writers.

Live adapters are synchronous and therefore apply backpressure directly to the traced operation. In the
synthetic 1 ms blocking-sink workload, one event measured 1.28 ms at p50 and 5.42 ms at p99; ten events
measured 12.73 ms at p50 and 22.09 ms at p99. An adapter that performs I/O should enqueue onto its own
bounded worker rather than block `onLive`.

See the [benchmark plan](docs/benchmark-plan.md) for the workloads and how to run them, and the
[current run](benchmarks/results/20261017-full/analysis.md) for confidence intervals, limitations and the exact
commands. Every run is kept under `benchmarks/results/`.

## Where to start

`src/main/kotlin/dev/kotrace/Trace.kt` is the entry point: `span {}` opens every span a suspend flow records.
Read `Span.kt` and `SpanContext.kt` next for the data + propagation model. The rest of the core is one package
per concern (ADR-031): `event/` (events, records, `toJson`), `adapter/` (the sink contract and the fault hook),
`report/` (`reportTrace`, `renderTree`), `bridge/` (the non-suspend opt-in and `runIsolated`, what an
integration builds on) and `internal/`. Tests in
`src/test/kotlin/dev/kotrace/` mirror those packages and double as usage examples (`report/TraceTreeTest` is the
guided tour).

For OkHttp, `kotrace-okhttp/` holds `TracingCallFactory` (tags the outgoing request with the flow's trace
context, from the coroutine thread) + `TracingInterceptor` (reads the tag on OkHttp's thread and writes
`traceparent`). The split is deliberate: the interceptor cannot see the coroutine context. Wrap the
client's `Call.Factory` with the factory and add the interceptor. A call passes through both untouched when nothing
would consume its span: no active span or collector, and no live adapter.

For Room, `kotrace-room/` (Android) holds `TracingQueryCallback` + the `RoomDatabase.Builder.tracing()`
installer: SQL onto the active span, resolved via `currentThreadSpan()` (Room runs under the caller's coroutine,
so the `ThreadContextElement` mirror reaches its executor thread).

Example: seed a `SpanCollector` on the context, run the flow, then read the tree:

<!-- snippet: render-tree -->
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
@OptIn(UnredactedTraceRead::class)           // human debug read, never route this to a sink (ADR-008)
println(collector.spans.renderTree())
```

## Using with OkHttp

Two pieces, installed together on the client. `TracingInterceptor` writes the `traceparent`, but runs on
OkHttp's own thread where the coroutine context is invisible, so `TracingCallFactory` first captures the
flow's trace context (active span, collector, per-flow `TraceConfig`) on the coroutine thread at `newCall` and
tags it on the request; the interceptor opens **one http span per execution** from that tag and emits under the
flow's own configuration (ADR-023). A retry via `call.clone()` therefore gets its own span, and a telemetry fault never
changes the call's response or exception: it reaches the `FaultHook` as `FaultPhase.HTTP` instead (ADR-035). The
one exception is `strictWhenNotInstalled` in a debug build, which fails the call when nothing is installed. A call
is traced only when something would consume the span: an active span or collector, or a live adapter in the
flow's (or the process-wide) configuration. Otherwise it passes through untagged and unmodified, so wrap every
client:

<!-- snippet: okhttp-setup -->
```kotlin
import dev.kotrace.okhttp.TracingCallFactory
import dev.kotrace.okhttp.TracingInterceptor

// 1. Add the interceptor to the client.
val http = OkHttpClient.Builder()
    .addInterceptor(TracingInterceptor())
    .build()

// 2. Wrap the client (a Call.Factory) with the factory; use THIS to make calls.
val calls: Call.Factory = TracingCallFactory(http)

// 3. Call inside a span. traceparent goes on the wire; the span emits http.status as an attribute
//    (on every record off the span, not a filter key, ADR-001), and a non-2xx flips it to ERROR.
suspend fun price(): String = span("pricing.fetch") {
    calls.newCall(Request.Builder().url(pricingUrl).build())
        .execute().use { it.body?.string().orEmpty() }
}
```

**Retrofit**: pass the wrapped factory via `Retrofit.Builder().callFactory(TracingCallFactory(http))`
(not `.client(...)`) so every generated call is tagged from the coroutine thread.

**A trace sink's own uploads** go through the plain client, never the factory: `http.newCall(…)`, or a
separate `Retrofit.Builder().client(http)`. Only a call the factory made is tagged, and the interceptor passes
an untagged one straight through, so the sink can share the app's client and connection pool. A sink that
uploaded from a worker thread through the factory would open a span per upload whose failure feeds back into
the sink: offline, it uploads until its queue is full. (A call made inside `onLive` itself is never traced.)

Order between the factory and interceptor does not matter (tag is placed before dispatch, read during
the chain). With no span, no collector and no live adapter to consume it, a call passes through untagged and
unmodified; with only a live adapter installed, a call outside any span opens a root http span.

**Span name.** The HTTP span is named `http <METHOD>`, never the URL path, which can carry user data and
would otherwise ride `operation` onto every record, crash reports and `toJson` included (ADR-021). The path
stays on the interceptor's `sensitive` breadcrumbs. To tell endpoints apart, map requests to a sanitized
route template yourself:

<!-- snippet: okhttp-span-name -->
```kotlin
val calls = TracingCallFactory(http, spanName = { req -> "http ${req.method} ${routeTemplateOf(req)}" })  // e.g. /users/{id}
```

**Body logging.** `TracingInterceptor(Level.BODY, bodyLimit = 8 * 1024)` logs request/response bodies as
`sensitive` events (debug builds only). It is bounded and never changes the transport (ADR-024). A request body
larger than `bodyLimit` is not written at all, and a smaller or streamed one only up to `bodyLimit + 1` bytes
(the extra byte detects truncation), except that an unknown-length body writing one large chunk has that chunk
staged in memory once before the cap stops it. One-shot and duplex bodies are omitted rather than consumed. Nothing is read
from a response before it is returned: the first `bodyLimit + 1` bytes are copied **as your code reads the
body**, and the snippet is logged when you reach its end or close it. So a stream is never waited on, and a body
you never read logs nothing.

See `demo/…/Main.kt` for a full end-to-end run.

## Using with Room

One call in the builder. `db.tracing()` installs a `QueryCallback` that logs each statement's SQL onto
the active span. It reads the span via `currentThreadSpan()` (no request-tag bridge like OkHttp needs) because
Room runs a suspend query under the caller's coroutine (`withContext`), and `SpanContext` is a
`ThreadContextElement`, so the span is mirrored onto Room's executor thread while the query runs. The
callback is installed with a **direct executor** so it fires inline there.

<!-- snippet: room-setup -->
```kotlin
import dev.kotrace.room.tracing

Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .tracing(mapOf("level" to "DEBUG"))   // attributes stamped on each SQL log; kotrace holds no level taxonomy
    .build()
```

The SQL text is logged, never the bound values, which can carry user data. The line is `sensitive`: parameterized
SQL (symbols, `?` placeholders) holds no user data, but a `@RawQuery` or `SimpleSQLiteQuery` built by string
concatenation inlines its values into the text, so a report adapter receives the line only if its policy accepts
sensitive events. kotrace has no severity taxonomy, so the level is just a `"level"` attribute you pass; SQL
is high-volume, so tag it at a level your capture policy drops by default (for example, `DEBUG`).

Room keeps a single query callback, so `.tracing()` replaces one you set with `setQueryCallback`. Pass yours as
`.tracing(attrs, delegate = myCallback, delegateExecutor = myExecutor)` to keep it. `.tracing()` does not work
with `setDriver(SQLiteDriver)`: Room rejects every query callback when a driver is configured, and `build()`
throws. A trace sink that writes to a database built with `.tracing()` logs its own inserts (as `sensitive` lines)
onto every traced flow; give the sink its own, uninstrumented database. (For a sink's HTTP uploads, see "A
trace sink's own uploads" under Using with OkHttp.)

## Span-less emits & scopes

An emit outside any span (an app-lifecycle hook, a push-notification callback, any non-coroutine entry)
has no span to attach to and no `TraceConfig` in context. Install the process-wide configuration once, then emit
from anywhere; `withScope` adds a correlation umbrella above `trace_id` so orphan emits and whole groups of
operations correlate under one session/journey key (ADR-010). A `fallbackScopeId` installed with the configuration
scopes whatever runs outside any `withScope` (ADR-038).

<!-- snippet: spanless-scope -->
```kotlin
import dev.kotrace.Kotrace
import dev.kotrace.withScope
import dev.kotrace.event.emitException
import dev.kotrace.event.emitNamed

// 1. Once at startup (or Kotrace.install { diGraph.adapters() } to resolve lazily under dependency injection).
//    The optional fallbackScopeId scopes every record emitted outside any withScope (ADR-038).
Kotrace.install(listOf(myLiveAdapter), fallbackScopeId = { sessionStore.currentSessionId() })

// 2. Orphan emit: no coroutine, no span needed. Fans live to the installed adapters, under the fallback scope_id.
fun onPushReceived(cause: Throwable) {
    emitNamed("push_received", mapOf("channel" to "promo"))
    emitException(cause, mapOf("report" to "explicit"))   // rides ExceptionRecord.spanAttributes
}

// 3. A scope: everything inside carries scope_id = "journey-42" instead; a span additionally carries its trace_id.
suspend fun handleJourney() = withScope("journey-42") {
    emitNamed("journey_started")                 // scope_id only
    span("checkout") { /* … */ }                 // trace_id + scope_id
}
```

A flow that needs *different* sinks overlays a per-flow `TraceConfig` on its context
(`withContext(collector + TraceConfig(...))`), which overrides the global for that flow.

`install` is once per process. A test suite that installs per test resets between cases with
`Kotrace.resetForTest()`, which is gated by `@KotraceTestApi` so production code cannot call it. Opt in for
the test source set only:

<!-- snippet: gradle -->
```kotlin
kotlin { sourceSets.test { languageSettings.optIn("dev.kotrace.KotraceTestApi") } }
```

Reading a span's raw timeline (`Span.events`, `Span.exception`) is gated like `renderTree` by
`@UnredactedTraceRead` (ADR-028): it hands out `sensitive` messages and raw throwables, so it is for a person or a
test, never a sink.

## Android surfaces → what to use

kotrace reaches wherever the coroutine `CoroutineContext` flows: structured concurrency carries it, and
because `SpanContext` is a `ThreadContextElement` the active span is re-mirrored onto each thread a
coroutine resumes on, so **dispatcher hops keep the trace**. It stops at any boundary that *leaves* the
coroutine world (raw threads, framework callbacks, Java) or *defers* execution to later (posted/async
callbacks): the `ThreadLocal` mirror is not inheritable and is restored the moment a `span { }` scope
exits, so across those boundaries you carry the span by hand: pass the `Span` object (its `trace_id` +
`span_id` survive anything), or re-seed `SpanContext(span) + collector + config` when re-entering a
coroutine (same idea as OkHttp's request-tag bridge).

| Android surface | In a coroutine? | Use | Why |
| --- | --- | --- | --- |
| `viewModelScope.launch`, suspend repo/usecase | yes | `span("…") { }` | ambient parent + collector flow with the context |
| `withContext(Dispatchers.IO/Default)` hop | yes | `span { }` spans the hop | `SpanContext` is a `ThreadContextElement`, re-mirrored on the new thread |
| Retrofit / OkHttp call | yes (call site) | `TracingCallFactory` + `TracingInterceptor`, call inside `span { }` | factory captures the flow's context on the coroutine thread; interceptor opens and ends one http span per execution on OkHttp's thread via the request tag (ADR-023) |
| Room suspend DAO query | yes | `db.tracing()` | `QueryCallback` reads `currentThreadSpan()` inline on Room's executor thread |
| `CoroutineWorker.doWork()` | yes | `span { }` | it hands you a coroutine |
| Non-suspend factory/interceptor invoked **inline on the coroutine thread** | no (but on that thread) | `startSpan()` / `Span.end()` (opt-in `@NonSuspendTracingBridge`) | the mirror is live inline: the one legitimate `startSpan` site (ADR-003) |
| Non-suspend hook that only **attaches** to the open span | no (inline) | `currentThreadSpan()?.log(…)` / `emit*` | read the mirror, don't open a node |
| Lifecycle (`onCreate`/`onResume`), click listeners, RecyclerView | no (main thread) | launch a coroutine + open a **root** `span("…") { }`, or `emitNamed`/`emitException` for a one-off | no ambient span to inherit; this is a new trace root |
| `BroadcastReceiver.onReceive`, Service, `JobScheduler` | no | root `span { }` in a launched coroutine, or `emit*` | new entry point, not a continuation |
| 3rd-party SDK asynchronous callback, `LocationListener`, sensor/camera | no (SDK thread, deferred) | pass the `Span` explicitly, or open a root span / `emit*` | mirror is dead on the SDK thread (`ThreadLocal` not inheritable) |
| Raw `Thread` / `Executors` / RxJava `Scheduler` | no | pass the `Span` by hand, then `SpanContext(span) + collector + config` to re-enter a coroutine | the mirror is never carried to non-coroutine threads |
| `Handler.post` / `runOnUiThread` | no (deferred) | capture the `Span` **before** posting, use it explicitly | mirror is restored by the time the `Runnable` runs |
| `GlobalScope.launch` / an unrelated scope | yes, as a **new root** | `span("…") { }` auto-roots and reports its own trace (ADR-013) | nothing is inherited from the launching flow, so it is a separate trace; prefer a structured scope when it belongs to the caller's work |
| Java you don't own, JNI/native | no | instrument at the nearest Kotlin/coroutine boundary you control | out of reach |

Rule of thumb: **in a coroutine → `span { }`; a non-suspend hook running inline on the coroutine thread →
`startSpan` (open) or `currentThreadSpan()` (attach); everywhere else → start a new root or carry the
`Span` across by hand.**

## Demo

`:demo` is a runnable, self-contained tour: a mobile "checkout" flow as a tree of spans, a parallel
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
- Spans carry **static symbols only**, never user data (a span can reach a crash report or the wire).
- Every public function ships with a test. Plain JUnit4.
- README code is compiled: every ```` ```kotlin ```` block is preceded by `<!-- snippet: name -->`, and a Kotlin
  snippet has a twin, a `// README snippet: name` region in a module's `ReadmeSnippets.kt` that `check`
  compiles. `ReadmeSnippetsTest` fails when the two differ; build-script blocks are tagged `gradle` (ADR-030).
- `./gradlew dokkaGenerate` builds the API documentation of the core, `kotrace-okhttp` and `kotrace-room`, with
  warnings as errors.

## Publishing

`dev.kotrace:kotrace` is served as a **static Maven repository over GitHub Pages** at
`https://maven.kotrace.dev` (a dedicated repository, custom domain via `CNAME`). Consumers pull anonymously
because a static repository needs no auth. Publishing is manual (no CI Runner):

1. Clone the dedicated Maven repository locally.
2. Point the build at it (in `~/.gradle/gradle.properties` or via `-P`):
   ```properties
   kotrace.maven.repo.dir=/absolute/path/to/kotrace-maven-checkout
   ```
3. Bump `version` in `gradle.properties` (the one place every module's coordinates come from). A
   binary-incompatible change needs a new version (pre-1.0, bump the minor). `./gradlew check` runs
   `checkKotlinAbi` against the committed `api/` dumps of every published module, so an ABI change fails the
   build until you review it and run `./gradlew updateKotlinAbi`.
4. Commit, then tag that commit `v<version>` **before** publishing.
5. Publish:
   ```bash
   ./gradlew publishAllPublicationsToKotraceMavenRepository
   ```
   Publishing refuses a dirty working tree (the artifact must be exactly a commit), a version whose
   `v<version>` tag is on a different commit, and a version already in the Maven repository from a different commit
   (each POM records its commit as `kotrace.git.commit`): a published version is immutable; bump instead of
   overwriting it. A `-SNAPSHOT` version is mutable, so it needs only the clean tree: skip step 4 and
   re-publish it from any commit. The guard is `build-logic`'s `kotrace.release-guard` plugin, and `check` runs
   its tests against throwaway Git and Maven repositories.
6. Commit + push the Maven repository. GitHub Pages serves the new files.

## Where to go when stuck

Open an issue on the GitHub project. Licensed under the [MIT License](LICENSE).
