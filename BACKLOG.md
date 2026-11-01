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
  other events therefore reaches an adapter as `onReport(ERROR, emptySequence())`, unless the adapter accepts span
  records (ADR-044): then it receives the span, `ERROR`, with its identity and timing, but no reason. **Why retained:** classification is
  fixed, but a safe symbolic reason such as `login.already_exists` is still absent from machine egress. This is
  intentional for expected validation failures (they stay out of crash telemetry), not a bug in the verdict.
  **Trigger to repay:** a consumer needs to search, count, or route value-only failures automatically by a
  safe symbolic code. **Likely shape:** either an explicit producer breadcrumb (which works without any kotrace
  change) or a
  dedicated symbol-only failure record with a defined occurrence key/dedup and adapter routing contract. Do
  **not** synthesize a `Throwable`, serialize the domain object, or silently model it as `LogEvent(ERROR)`:
  severity belongs to the consumer, ambient logs duplicate on the returned-value climb, and a report adapter
  may route logs into a crash reporter. Related: ADR-020 (status-only classification), ADR-044 (span
  records), ADR-001 (symbol-only, PII-safe fields), ADR-015 (throwable lineage is unavailable here).

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

- **D05: a span that ends after its trace's report is never delivered as ended.** ADR-044 freezes each span's
  status and end when the report begins, so a bridge span whose call outlives the root (an OkHttp body read after the
  root returned) reaches a span exporter open, with `endNanos == null`, and its end reaches no adapter. **Why now:** on
  mobile most traces end within one operation, so late spans are rare. **Cost:** an exporter drops the span or shows it
  open. **Trigger to repay:** an exporter seeing open spans often. **Likely shape:** a trace-finalization step that
  keeps the trace's verdict, tracks spans still open and delivers their ends later (with timeout, retention and
  deduplication rules), or live span records at each span end behind their own gate. Related: ADR-044, ADR-025.

- **D06: no span kind.** OpenTelemetry's `SpanKind` (client, server, internal, producer, consumer) has no kotrace
  counterpart, so an exporter maps every span to one kind, and a backend's service graph cannot tell an OkHttp call
  from local work. **Why now:** kotrace has no server integration yet. **Cost:** a span export is less faithful.
  **Trigger to repay:** a server integration (Ktor), or an exporter feeding a service graph. **Likely shape:** a
  kind fixed when the span opens, set by the integrations (`kotrace-okhttp` as client), never inferred later.

- **D07: a link names a trace, never a span.** `TraceLink` holds a `traceId` only (ADR-009), while an
  OpenTelemetry link targets a span, so an exporter cannot convert a link faithfully. **Why now:** no consumer
  exports links. **Cost:** links are dropped or approximated in a span export. **Trigger to repay:** a consumer
  exporting links to a trace backend. **Likely shape:** an optional span id on `TraceLink`, set only when it is
  known; never a fabricated root span id.

- **D08: no capture-time wall clock.** Every time kotrace records is `System.nanoTime()`, so an exporter converts
  with an anchor taken at export (`unix ≈ wallNow - (nanoNow - atNanos)`): an approximation that drifts with the
  monotonic clock and a device's clock changes. **Why now:** the core stays free of a clock contract until KMP needs
  one. **Cost:** absolute span times in a backend are approximate. **Trigger to repay:** a backend needing accurate
  absolute times, or the KMP port's time abstraction. **Likely shape:** a portable clock in the core, read once per
  span open and end, defined by its own ADR.

### Tests

_None open._
