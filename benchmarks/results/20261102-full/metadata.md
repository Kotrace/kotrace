# kotrace benchmark run: full suite before 0.7.0

- Run identifier: `20261102-full`
- Purpose: refresh the performance figures for unreleased 0.7.0, including architectural decisions 038, 039, 040 and 044 and the `ThreadMirror` refactor.
- Started: 2026-11-02T14:02:12.218270+00:00; finished: 2026-11-02T14:48:36.631847+00:00.
- Main harness duration: 46 minutes 22 seconds; launcher duration, including Gradle: 46 minutes 24 seconds.
- Sink run: 2026-11-02T14:48:59.866688+00:00 to 2026-11-02T14:50:04.065237+00:00; harness duration 1 minute 2 seconds; launcher duration 1 minute 4 seconds.
- Source base commit and measured HEAD: `a6aed48fae701070cdc18b6de96ebd57f4b951ea`.
- Source state: clean before the first attempt and before the restart; unchanged after both completed suites. The only intended tracked additions are this file and `analysis.md`.
- Machine: Apple M1 Pro, 10 cores (8 performance and 2 efficiency), 10 logical processors, 32 gibibytes of memory, arm64.
- Operating system: macOS 27.0.1 (26A434), matching the October run.
- Benchmark Java Virtual Machine: Oracle Java Development Kit 21.0.3 (21.0.3+7-LTS-152), selected by the Gradle toolchain, matching the October run.
- Bytecode target: Java Virtual Machine 11; the published core compiles against the Java 11 application programming interface.
- Gradle: 9.3.1.
- Kotlin plugin: 2.4.10; core language and application programming interface level: 2.2.
- kotlinx.coroutines: 1.10.2, matching the October run.
- Java Microbenchmark Harness: 1.37.
- Garbage collector: G1, fixed heap of one gibibyte.
- Fork Java Virtual Machine flags: `-Xms1g -Xmx1g -XX:+UseG1GC -Dfile.encoding=UTF-8`.
- The raw result also records the inherited locale flags: `-Duser.country=VN -Duser.language=en -Duser.variant`; memory, collector and encoding flags occur twice because Gradle and the harness both supply them, as in the reference run.

## Source fingerprints

SHA-1 fingerprints of the same nine files as the October metadata, plus `ThreadMirror.kt` and `SpanRecord.kt`.
Taken before the first attempt, verified again immediately before the restart, and checked unchanged after the
completed main and sink runs at 2026-11-02T14:58:55.169675+00:00:

```text
670c8773b70ba9eef0c5025ffa903d893710c358  src/main/kotlin/dev/kotrace/report/Report.kt
84ee1c78229bd9b91996d7a411052485d45b92c0  src/main/kotlin/dev/kotrace/report/TraceTreeIndex.kt
1f624dcbbaeb5321bca7163f331e020974fee50a  src/main/kotlin/dev/kotrace/report/TraceFormat.kt
0c4557a1420b51cfcebb257d6388bac617fadf48  src/main/kotlin/dev/kotrace/Trace.kt
f98c7969d14f56c13a7e0ad9f0198b0687015de9  src/main/kotlin/dev/kotrace/Span.kt
a14a69ffeb11585d972e50c3cf8d98dbcb0171b1  src/main/kotlin/dev/kotrace/event/Emit.kt
4f73592f4c7bb0a424887a5a9948ea328849c1ef  src/main/kotlin/dev/kotrace/internal/FaultIsolation.kt
b97fc485526ce232f1200cfd9c3192ddba5848e8  benchmarks/src/main/kotlin/dev/kotrace/benchmarks/BenchmarkSupport.kt
331368655b9b55b288bcb43ba694afd99830d51a  benchmarks/src/test/kotlin/dev/kotrace/benchmarks/BenchmarkPreflightTest.kt
387c45c615561f0b8553ca51a618bf6b5037be5b  src/main/kotlin/dev/kotrace/ThreadMirror.kt
a5beba2e6ceb2ace335279142bab6a855594970b  src/main/kotlin/dev/kotrace/event/SpanRecord.kt
```

## Integrity checks

- `./gradlew :benchmarks:test` passed. Because Gradle initially reused its test output, `./gradlew :benchmarks:test --rerun` then executed the test task: 7 tests passed, none failed or skipped. Both preflight logs were preserved across the restart.
- Main suite: exactly 90 distinct cases, with the same benchmark names and parameter combinations as `20261017-full/main-gc.json`.
- Main profile: AverageTime, 1 harness thread, 3 forks, 5 warm-up iterations of 1 second, 5 measurement iterations of 1 second, garbage collection profiling.
- All 1,350 main primary measurements are finite, with 3 forks and 5 measurements per fork in every case.
- Sink suite: both expected cases, `eventCount=1` and `eventCount=10`, SampleTime, 1 harness thread, the same 3-fork and 5-iteration standard profile, garbage collection profiling.
- All 92 primary scores and all 471 primary and secondary score values across the two suites are numeric and finite. Every main primary confidence interval is finite. Percentile secondary metrics have undefined harness confidence intervals, which is expected and is distinguished from a non-finite score.
- Both Gradle launchers exited successfully. Neither harness log contains a benchmark failure, failed fork, uncaught thread exception or timeout marker.
- The first attempt was stopped manually by the user after 68 of 90 cases, leaving an empty `main-gc.json` and no completion status. At the user's request, everything in this directory except the two preflight logs was cleared before a complete restart. None of that partial run contributes to these results.
- No builds, tests, other suites or heavy analysis commands were run alongside measurement. Only short log polls ran. Process enumeration was denied by the environment, so unrelated application activity could not be independently ruled out.
- Environment diagnostics: `RTK.md`, referenced by the supplied instructions, was absent from this repository. Gradle reported an unavailable file-system event stream; the first preflight also reported that Android analytics settings were not writable. Hardware inspection returned the processor and memory details but timed out on its activation-lock check; some system queries and process enumeration were denied. These did not fail either suite.
- Only `benchmarks/results/20261102-full/` was edited directly. No source, benchmark, README or other documentation was edited. Nothing was committed or pushed.

## Follow-up run

`SinkLatencyBenchmark` was run after the main suite, without overlap, and saved as `sink-latency.json` and
`sink-latency.log`. SampleTime comes from the benchmark's mode and is also pinned explicitly on the command line.
The five warm-up iterations follow the benchmark plan's standard profile, overriding the class annotation's three.

The README's old sink values come from `20260914-sol-high-stage2b/sink-latency.json`, which used three warm-up
iterations and no garbage collection profiler, on macOS 14.6.1 with kotlinx.coroutines 1.11.0. Those differences
limit attribution. The new analysis provides explicitly labelled exploratory percentile intervals reconstructed
from the stored sample histograms, since the harness does not provide percentile confidence intervals.

For the main comparison, every matching case in the five October `followup-*.json` files supersedes that case
in October's `main-gc.json`: 12 cases are superseded in total. No additional benchmark case was added or run.

## Executed command

Preflight, before the interrupted attempt:

```text
./gradlew :benchmarks:test
./gradlew :benchmarks:test --rerun
```

Completed replacement main run:

```text
./gradlew :benchmarks:jmh -PjmhArgs=".*(Lifecycle|Capture|EventKind|EventCap|EventRead|LiveFanout|SensitiveRouting|ReportScaling|ReportFailureScaling|ReportOutcome|Failure|Concurrency)Benchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/20261102-full/main-gc.json -o benchmarks/results/20261102-full/main-gc.log"
```

Sink run:

```text
./gradlew :benchmarks:jmh -PjmhArgs="SinkLatencyBenchmark -bm sample -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/20261102-full/sink-latency.json -o benchmarks/results/20261102-full/sink-latency.log"
```

Each measurement command was launched through `nohup` in a separate session with standard input disconnected.
Launcher output is in `main-launcher.log` and `sink-launcher.log`; the harness writes its own requested result
logs. Start time, end time and exit status are in `main-status.json` and `sink-status.json`. Raw results, logs and
local comparison bookkeeping are ignored by Git; only `metadata.md` and `analysis.md` are intended for commit.
