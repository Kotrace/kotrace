# Backlog: open bugs, tech debt, missing tests

The **one** living log of work that is known and not done. Found something and can't fix it now? It
goes here: not a comment, not a commit message, not your head.

Everything is `Bnn` (bug), `Dnn` (debt), or `Tnn` (test). Fixed items are deleted, not struck through;
git remembers. Ids are positional and reused as items are deleted, so **do not cite a `Bnn` from source**
(a `Dnn` is safe to cite: debt is long-lived).

- What is **built** → [ARCHITECTURE.md](ARCHITECTURE.md)
- Why → [DECISIONS.md](DECISIONS.md)

---

## Open

### Bugs

_None open._

### Debt

- **D01: no span-granular export hook; adapter surface is event-flattened.** A `TraceAdapter` only ever
  receives `TraceRecord`s (one per reportable event), never a `Span`. So an adapter cannot read per-span
  `startNanos`/`endNanos`/duration/`status`, gets no span-ended signal on the live path (`onLive` fires
  while the span is open, outcome unknown), and never sees a span that produced no reportable event
  (`reportTrace`'s walk emits records per event). **Why now:** every current sink is crash/log, which is
  event-granular; none needs spans. **Cost:** no faithful exporter to a span-oriented backend (OTLP /
  Tempo / Jaeger) can be built on the public API; a consumer must either encode span timing through
  `putAttribute` (fragile, and a span-without-events stays invisible) or fork. **Trigger to repay:** the first
  consumer that needs to export spans to a trace backend. **Likely shape:** a `ReportAdapter` variant
  receiving `Span` (or a `SpanRecord` carrying start/end/status + nested events), fed from the same
  `SpanCollector` walk. Related: ADR-002 (fan-out is the single filtering authority), ADR-005
  (birthplace), ADR-006 (`Span` terminal state).

- **D02: no persistence; an in-flight trace does not survive process death.** `reportTrace` is
  in-process code that runs at trace end, and the `SpanCollector` holding the open tree lives only in the
  coroutine context (in-memory). If the process dies mid-trace (user swipes the app away, OS OOM-kill,
  force-stop, or a fatal crash), that code never runs and the tree is lost, and the trace produces **no
  report**. Only live emits already fanned before death survive, and only if their sink persisted them
  off-process (for example, a Crashlytics `recordException`/`log` written to disk). A cooperative
  `CancellationException` is **not** this case: the process is alive, so the auto-root still reports
  `CANCELLED`. **Why now:** an interrupted operation is generally not a failure (same basis
  as gating CANCELLED out of the crash sink), so losing its report is usually correct, not a defect.
  **Cost:** kotrace cannot answer "how many operations died in-flight across process death": a start
  with no end is invisible; a consumer needing that must model it out-of-band (for example, a paired
  start/end analytics `event`, the gap measured backend-side) rather than from the trace tree.
  **Trigger to repay:** a concrete need to observe in-flight-at-death traces, or attach a kotrace span
  tree to a platform fatal. **Likely shape (heavy):** a live adapter that journals span state to disk and
  replays on next launch (the OpenTelemetry-agent persistent-queue model), a large scope change to kotrace's
  in-process, in-memory report model. **Probably won't do** until such a need is real; logged so the
  boundary is known, not silently assumed.

- **D03: value-only domain failures have no machine-readable failure record.**
  [ADR-020](decisions/adr-020-value-only-failure-classifier.md)'s ambient `ReturnVerdictClassifier` handles a
  **returned** failure with no `Throwable`: `ReturnVerdict.Failure(null)` marks every returning span `ERROR` and
  supplies the auto-root's default `TraceStatus.ERROR`, without the consumer misusing `Span.end(ERROR)` or
  synthesizing a fake exception. It deliberately emits no `SpanEvent`/`TraceRecord`. A report containing no
  other events therefore reaches an adapter as `onReport(ERROR, emptySequence())`; until D01 is repaid, that
  sequence also exposes no `trace_id`, operation, timing, or span status. **Why retained:** classification is
  fixed, but a safe symbolic reason such as `login.already_exists` is still absent from machine egress. This is
  intentional for expected validation failures (they stay out of crash telemetry), not a bug in the verdict.
  **Trigger to repay:** a consumer needs to search, count, or route value-only failures automatically by a
  safe symbolic code. **Likely shape:** either an explicit producer breadcrumb (which works without any kotrace
  change) or a
  dedicated symbol-only failure record with a defined occurrence key/dedup and adapter routing contract. Do
  **not** synthesize a `Throwable`, serialize the domain object, or silently model it as `LogEvent(ERROR)`:
  severity belongs to the consumer, ambient logs duplicate on the returned-value climb, and a report adapter
  may route logs into a crash reporter. Related: ADR-020 (status-only classification), D01 (span-granular
  export), ADR-001 (symbol-only, PII-safe fields), ADR-015 (throwable lineage is unavailable here).

- **D04: a sink worker that uploads through the traced factory feeds itself.** A bridge captures no trace
  context during a live fan-out (ADR-025), so an HTTP call made synchronously inside `onLive` is never traced.
  But `LiveAdapter.onLive` tells a sink to hand records to a bounded worker, and a worker that uploads through
  `TracingCallFactory` opens a root HTTP span per upload (a live adapter is installed). When uploads fail, each
  failure is a fresh `ExceptionRecord` fanned back into the sink: offline, it uploads until its queue is full.
  **Why now:** the fix costs nothing and is documented (`onLive` KDoc, README, ADR-025): the worker calls
  through the plain `OkHttpClient`, which can be the app's own, and `OkHttpDispatcherBridgeTest` pins it.
  **Cost:** a documented trap. **Trigger to repay:** a sink that can reach only the traced factory (a dependency-injection graph
  or SDK that exposes nothing else). **Likely shape:** a scoped opt-out on the worker (`untraced { }`, a
  per-thread flag `captureOrNull` honors, with a coroutine-context twin), or a request tag the factory honors.

### Tests

- **T01: no test runs a real Room database.** `tracing()`'s wiring is unit-tested against a mocked
  `RoomDatabase.Builder`: the SQL logger on a direct executor, the app's callback forwarded inline or on its
  executor. That Room then calls it inline on the query thread, under the caller's mirrored span, is Room's
  behavior, read from its source and not exercised. **Trigger to repay:** a Room upgrade, or a report that SQL
  lines stopped reaching spans. **Likely shape:** a Robolectric test with a KSP-generated in-memory database.

