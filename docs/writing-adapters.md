# Writing an adapter

An adapter is where kotrace's records leave the library: a crash reporter, a log pipeline, an analytics SDK, a
debug watch. This guide gives the contract an adapter author relies on and the recipes that avoid known mistakes.
The rationale behind each rule is in the ADR it cites. Every code block below is compiled (`ReadmeSnippetsTest`).

## 1. Pick the path

| | `LiveAdapter.onLive(record)` | `ReportAdapter.onReport(status, records)` |
|---|---|---|
| Fires | once per event, as it is recorded | once per report: when an auto-root ends, or when a manual boundary calls `reportTrace`, covering every root of its collector (ADR-013/026) |
| Knows the outcome | no | yes: `status` comes first |
| Exception records | every one, every level of a climb, recovered ones too; `origin` is `null` | deduped by the policy; `origin` is set |
| Logs | yes | yes, buffered in the tree |
| Named (analytics) events | yes | no |
| Span-less emits (`emit*`) | yes | no: there is no tree |
| Use for | analytics, a debug watch, an exception that must ship whatever the outcome | a crash verdict, breadcrumbs of a failed flow |

One class may implement both interfaces. It then has one `policy`, applied on both paths. What each scenario
actually delivers is in the [outcome matrix](outcome-matrix.md).

## 2. The policy gates

A `TracePolicy` decides what this adapter receives. A bare `object : TracePolicy {}` is a working, fail-closed policy.

| Gate | Default | Path | What it decides |
|---|---|---|---|
| `acceptsSpan(span)` | `true` | both | breadcrumbs from this span, by its `traits`. Never applied to an exception |
| `acceptsEvent(event)` | `true` | both | this event, for example a level threshold read off its attributes |
| `acceptsSensitive` | `false` | both | log events marked `sensitive` (user data) |
| `acceptsAttribute(key, value)` | `false` | both | each attribute on a delivered record. It filters attributes; it never drops a record (ADR-039) |
| `acceptsPropagatedException` | `false` | report | the `PROPAGATED` copies of a climb, beside its `BIRTHPLACE` (ADR-019) |
| `acceptsExplicitException` | `true` | report | `EXPLICIT` records from `addException`. Applied before `acceptsEvent` (ADR-040) |

Two defaults bite most often:

- **`acceptsAttribute` is `false`.** An adapter that declares nothing receives every record with empty attribute
  maps, kotrace's own keys (`Span.EVENTS_DROPPED_ATTRIBUTE`, `HttpSpan.STATUS`) included. Admit by name what may
  leave the device; only a debug adapter on the developer's device should return `true`.
- **A report is delivered for every status.** `onReport` is called with `OK` and `CANCELLED` too, and an `OK`
  report can hold the birthplace of a failure the code recovered from. A crash sink returns early unless the status
  is `ERROR`.

## 3. Threading, faults and reentrancy

- **`onLive` runs synchronously on the thread that logged**, on the application's critical path. **`onReport`
  runs on the thread that ended the trace.** kotrace never makes delivery asynchronous: a sink doing I/O hands the
  record to a bounded worker and returns.
- **Consume `records` inside `onReport`.** The sequence is lazy and shared across adapters; reading it after the
  call returns is unsupported (ADR-014/029).
- **An adapter's throw is contained.** It never reaches the traced code, never changes the outcome, and never
  stops another adapter (ADR-014/034). A JVM-fatal error still propagates. Contained faults go to the one
  process-wide `FaultHook`; install it first, so it also hears of a failing `install` provider (ADR-037):

<!-- snippet: guide-fault-hook -->
```kotlin
Kotrace.installFaultHook { phase, faultMessage, cause ->
    Metrics.count("kotrace_fault", "${phase.name}:${faultMessage ?: cause::class.simpleName}")
}
```

- **Logging through kotrace from `onLive` is stored, not fanned out again**, and a span-less emit there is
  dropped (ADR-025). The guard is per thread: never forward kotrace's records back into kotrace from a worker.

## 4. Recipe: a crash sink

The default policy already gives one record per failure: the `BIRTHPLACE` of each lineage, plus `EXPLICIT`
records. Gate on the status, and nothing else is needed.

<!-- snippet: guide-report-crash -->
```kotlin
class ReportCrashSink(private val reporter: CrashReporter) : ReportAdapter {
    override val policy = object : TracePolicy {}

    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        if (status != TraceStatus.ERROR) return          // a recovered failure is in an OK report too
        records.filterIsInstance<ExceptionRecord>().forEach { reporter.record(it.throwable, it.traceId) }
    }
}
```

`traceId` goes with the throwable: stamp it on the crash report as a searchable key (Crashlytics
`setCustomKey("trace_id", traceId)`). It is the only join between the crash reporter and the log store
(`ExceptionRecord` KDoc). It is `null` on a span-less record.

## 5. Recipe: a crash sink that ships explicit reports live

An exception the code catches, reports and recovers from must reach the crash reporter even when the trace
ends `OK`, and the report path would drop it at the status gate above. Ship it live instead.

**Only when every `addException` in the codebase goes through one facade that marks it.** The recipe removes every
`EXPLICIT` record from this adapter's report, marked or not, so an unmarked call is shipped by neither path and lost
without a trace (ADR-040). If calls cannot all be routed through the facade, this recipe is unsafe: use the
report-only sink of § 4, which ships `EXPLICIT` records with a failing trace and not with an `OK` one. The marker
cannot deduplicate on the report side instead: the `attributes` of `addException` ride the live record only. With the facade in place, three things make it correct:

1. **Mark the record yourself.** A live record has no `origin`, so `onLive` cannot tell `addException` from a
   thrown failure. The `attributes` of `addException` (and `emitException`) ride the live record's
   `spanAttributes`: put a marker there.
2. **Admit the marker** in `acceptsAttribute`, or the filtered record arrives without it and nothing ships.
3. **Refuse `EXPLICIT` on the report** with `acceptsExplicitException = false`, or a failing trace records the
   same exception twice. Setting it is this adapter's claim that it delivered them live; kotrace cannot check it.

<!-- snippet: guide-live-crash -->
```kotlin
class LiveCrashSink(private val reporter: CrashReporter) : LiveAdapter, ReportAdapter {
    override val policy = object : TracePolicy {
        // Every addException is shipped live by onLive, so the report must not hand it over again (ADR-040).
        override val acceptsExplicitException = false
        // The marker is read off a filtered record, so it must be admitted, or onLive never sees it (ADR-039).
        override fun acceptsAttribute(key: String, value: String) = key == REPORTED
    }

    // A live record has no origin: the marker is what tells an explicit report from a thrown failure.
    override fun onLive(record: TraceRecord) {
        if (record is ExceptionRecord && record.spanAttributes[REPORTED] == "true") {
            reporter.record(record.throwable, record.traceId)
        }
    }

    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        if (status != TraceStatus.ERROR) return
        records.filterIsInstance<ExceptionRecord>().forEach { reporter.record(it.throwable, it.traceId) }
    }

    companion object { const val REPORTED = "reported" }
}
```

The facade, the codebase's only caller of `addException` and `emitException`:

<!-- snippet: guide-live-crash-call -->
```kotlin
suspend fun reportCaught(cause: Throwable) {
    val marked = mapOf(LiveCrashSink.REPORTED to "true")
    val span = currentSpan()
    if (span != null) span.addException(cause, marked) else emitException(cause, marked)
}
```

Enforce that it is the only caller, with a lint or architecture rule; a review alone will miss one.

**Report a throwable or rethrow it, never both.** `addException(e)` followed by `throw e` is two lineages to
kotrace: an `EXPLICIT` record shipped live and a `BIRTHPLACE` in the report (see the outcome matrix). kotrace does
not merge them; [ADR-041](../decisions/adr-041-birthplace-already-explicit.md) explains why it was rejected.

## 6. Recipe: a trace viewer

A viewer wants every level of a climb and reads `origin` to draw it.

<!-- snippet: guide-trace-viewer -->
```kotlin
class TraceViewerSink(private val store: TraceStore) : ReportAdapter {
    override val policy = object : TracePolicy {
        override val acceptsPropagatedException = true   // the whole climb, not one record per failure
    }

    override fun onReport(status: TraceStatus, records: Sequence<TraceRecord>) {
        records.filterIsInstance<ExceptionRecord>().forEach { record ->
            when (record.origin) {                       // exhaustive: a new origin is a compile error here
                ExceptionOrigin.BIRTHPLACE, ExceptionOrigin.EXPLICIT -> store.append(record, propagated = false)
                ExceptionOrigin.PROPAGATED -> store.append(record, propagated = true)
                null -> Unit                             // live records only; a report record always has one
            }
        }
    }
}
```

Match `origin` exhaustively, with no `else`. An `else` hides a new origin, as `EXPLICIT` was in 0.6.0, and an
adapter compiled against an older version throws `NoWhenBranchMatchedException` on it.

## 7. Checklist

- [ ] The policy admits, by name, every attribute key this adapter may ship, and no others.
- [ ] `acceptsSensitive` stays `false` unless the output never leaves the device.
- [ ] A crash sink returns early on a non-`ERROR` status, and stamps each crash report with the record's `traceId`.
- [ ] If it ships `addException` records live: every call goes through one marking facade, enforced by a rule;
      the adapter reads a marker it admits; only then `acceptsExplicitException = false`.
- [ ] I/O happens on a bounded worker, never in `onLive` or `onReport` directly.
- [ ] `records` is consumed inside `onReport`.
- [ ] A `when` over `origin` is exhaustive.
- [ ] A `FaultHook` is installed, so a contained adapter fault is visible.
