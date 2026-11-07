# Outcome matrix

What each adapter receives, scenario by scenario. **The table is generated**: `OutcomeMatrixTest` runs every row
against one live adapter and three report adapters and fails when a cell no longer matches. Regenerate it after a
deliberate behavior change, and review the table's diff as the statement of that change:

```bash
KOTRACE_UPDATE_DOCS=1 ./gradlew :test --tests '*OutcomeMatrixTest' --rerun
```

Read the columns as:

- **Trace status**: the `TraceStatus` handed to every `ReportAdapter`, or `no report` when no trace ran.
- **Live exceptions**: the `ExceptionRecord`s a `LiveAdapter` got, in order, as `operation` and the scenario's
  message, or the class name of a library exception. A live
  record has no origin: it is delivered as it happens, before any outcome is known.
- **Report** columns: the `ExceptionRecord`s in the report, with their `ExceptionOrigin`, under three policies:
  the default, one that also takes the `PROPAGATED` copies of a climb (a trace viewer), and one that refuses
  `EXPLICIT` records because it already shipped them live (ADR-040).
- **Report: `acceptsSpanRecords`**: the `SpanRecord`s a span exporter's report holds (ADR-044), in tree order, as
  `operation` and `SpanStatus`. A cancelled span reads `CANCELLED`, never `ERROR`.

Every scenario installs a classifier that maps `Result.failure(e)` to `ReturnVerdict.Failure(e)` and one sentinel
value to `ReturnVerdict.Failure(null)` (ADR-018/020).

<!-- outcome-matrix:start -->
| Scenario | Trace status | Live exceptions | Report: default | Report: `acceptsPropagatedException` | Report: `acceptsExplicitException = false` | Report: `acceptsSpanRecords`, span statuses |
|---|---|---|---|---|---|---|
| root returns normally | OK | — | — | — | — | root OK |
| root throws | ERROR | root `root` | root `root` BIRTHPLACE | root `root` BIRTHPLACE | root `root` BIRTHPLACE | root ERROR |
| child throws, escapes the root | ERROR | child `child`, root `child` | child `child` BIRTHPLACE | root `child` PROPAGATED, child `child` BIRTHPLACE | child `child` BIRTHPLACE | root ERROR, child ERROR |
| child throws, root catches and recovers | OK | child `child` | child `child` BIRTHPLACE | child `child` BIRTHPLACE | child `child` BIRTHPLACE | root OK, child ERROR |
| child throws, root catches and rethrows the same | ERROR | child `child`, root `child` | child `child` BIRTHPLACE | root `child` PROPAGATED, child `child` BIRTHPLACE | child `child` BIRTHPLACE | root ERROR, child ERROR |
| child throws, root catches and throws a new one | ERROR | child `child`, root `root` | root `root` BIRTHPLACE, child `child` BIRTHPLACE | root `root` BIRTHPLACE, child `child` BIRTHPLACE | root `root` BIRTHPLACE, child `child` BIRTHPLACE | root ERROR, child ERROR |
| `addException`, root returns normally | OK | root `explicit` | root `explicit` EXPLICIT | root `explicit` EXPLICIT | — | root OK |
| `addException`, root throws another | ERROR | root `explicit`, root `root` | root `explicit` EXPLICIT, root `root` BIRTHPLACE | root `explicit` EXPLICIT, root `root` BIRTHPLACE | root `root` BIRTHPLACE | root ERROR |
| `addException(e)`, root throws the same `e` | ERROR | root `same`, root `same` | root `same` EXPLICIT, root `same` BIRTHPLACE | root `same` EXPLICIT, root `same` BIRTHPLACE | root `same` BIRTHPLACE | root ERROR |
| `emitException` outside any span | no report | span-less `span-less` | — | — | — | — |
| root returns `Result.failure` (classifier `Failure(throwable)`) | ERROR | root `returned` | root `returned` BIRTHPLACE | root `returned` BIRTHPLACE | root `returned` BIRTHPLACE | root ERROR |
| root returns a value classified `Failure(null)` | ERROR | — | — | — | — | root ERROR |
| root `returnedOutcome` = OK over a returned `Result.failure` | OK | root `returned` | root `returned` BIRTHPLACE | root `returned` BIRTHPLACE | root `returned` BIRTHPLACE | root ERROR |
| root throws, with a `returnedOutcome` = OK | ERROR | root `root` | root `root` BIRTHPLACE | root `root` BIRTHPLACE | root `root` BIRTHPLACE | root ERROR |
| root `returnedOutcome` = ERROR with an attached throwable | ERROR | — | root `attached` ATTACHED | root `attached` ATTACHED | root `attached` ATTACHED | root OK |
| child `returnedOutcome` = ERROR with an attached throwable | OK | — | — | — | — | root OK, child OK |
| child `attachException`, root returns OK | OK | — | child `attached` ATTACHED | child `attached` ATTACHED | child `attached` ATTACHED | root OK, child OK |
| child `attachException`, root throws ERROR | ERROR | root `root` | root `root` BIRTHPLACE, child `attached` ATTACHED | root `root` BIRTHPLACE, child `attached` ATTACHED | root `root` BIRTHPLACE, child `attached` ATTACHED | root ERROR, child OK |
| child `attachException`, caller cancels the root | CANCELLED | — | child `attached` ATTACHED | child `attached` ATTACHED | child `attached` ATTACHED | root CANCELLED, child OK |
| `withTimeout` inside the root expires | ERROR | root `TimeoutCancellationException` | root `TimeoutCancellationException` BIRTHPLACE | root `TimeoutCancellationException` BIRTHPLACE | root `TimeoutCancellationException` BIRTHPLACE | root ERROR |
| active root awaits a `Deferred` cancelled elsewhere | ERROR | root `JobCancellationException` | root `JobCancellationException` BIRTHPLACE | root `JobCancellationException` BIRTHPLACE | root `JobCancellationException` BIRTHPLACE | root ERROR |
| caller's `withTimeout` around the root expires | CANCELLED | — | — | — | — | root CANCELLED |
<!-- outcome-matrix:end -->

## What the table says

- **A report is delivered for every outcome, `OK` included.** A crash sink gates on the status itself (the
  `onReportSelfGate` sample); a recovered child failure is in an `OK` report, so an ungated sink would ship it.
- **The live path is not gated by the trace's status.** A live adapter sees every exception its policy accepts as it
  is recorded, including ones the code later recovers from, and every level of a climb.
- **`addException` and a thrown failure are separate lineages.** Recording `e` and then throwing the same `e`
  produces an `EXPLICIT` and a `BIRTHPLACE` record; kotrace does not merge them (ADR-041, rejected). A consumer that
  must not double-count routes each throwable one way.
- **`returnedOutcome` works only at an auto-root.** On a child it is never invoked, so its status and attached
  throwables are lost (`span` KDoc, ADR-016).
- **A cancellation from outside is not a failure.** The caller's timeout yields `CANCELLED` with no exception
  record; a timeout inside the span, or a `Deferred` cancelled elsewhere and awaited from an active span, is an
  `ERROR` with a birthplace (ADR-022).
- **A thrown escape decides the verdict before `returnedOutcome`.** On a normal return, an explicit
  `returnedOutcome` wins over the classifier, `OK` included.

See [troubleshooting.md](troubleshooting.md) for the symptoms these rows explain.
