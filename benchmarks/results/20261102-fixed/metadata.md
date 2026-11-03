# kotrace benchmark run: full suite after the report performance fix

- Run identifier: `20261102-fixed`.
- Purpose: produce the performance figures for 0.7.0 after `d0d8ac6`, following the profile and result layout of `20261102-full`.
- Started: 2026-11-02T15:49:26.913050+00:00; finished: 2026-11-02T16:35:49.060759+00:00.
- Main harness duration: 46 minutes 20 seconds; launcher duration, including Gradle: 2782.125 seconds, approximately 46 minutes 22 seconds.
- Source base commit and measured HEAD: `d0d8ac6dcfb02169997cf9f2ac2dbba693857f83`.
- Source state: no working-tree change outside `benchmarks/results/` before preflight or after measurement. The existing untracked directory `benchmarks/results/20261102-full/` was preserved. Its fifteen files have unchanged SHA-1 fingerprints.
- Machine: Apple M1 Pro, 10 cores, including 8 performance and 2 efficiency cores, 10 logical processors, 32 gibibytes of memory, arm64. Processor model, core split and memory are carried forward from the same-machine reference metadata; this session confirmed arm64 but its hardware query was denied.
- Operating system: macOS 27.0.1 (26A434), confirmed in this session and matching both reference runs.
- Benchmark Java Virtual Machine: Oracle Java Development Kit 21.0.3 (21.0.3+7-LTS-152), selected by the Gradle toolchain and confirmed by the harness.
- Bytecode target: Java Virtual Machine 11; the published core compiles against the Java 11 application programming interface.
- Gradle: 9.3.1.
- Kotlin plugin: 2.4.10; core language and application programming interface level: 2.2.
- kotlinx.coroutines: 1.10.2, matching both reference runs.
- Java Microbenchmark Harness: 1.37.
- Garbage collector: G1, fixed heap of one gibibyte.
- Fork Java Virtual Machine flags: `-Xms1g -Xmx1g -XX:+UseG1GC -Dfile.encoding=UTF-8`.
- Inherited locale flags: `-Duser.country=VN -Duser.language=en -Duser.variant`. Memory, collector and encoding flags occur twice because Gradle and the harness both supply them, as in `20261102-full`.

## Source fingerprints

SHA-1 fingerprints of the same eleven files as `20261102-full`, taken before preflight at
2026-11-02T15:48:57.198329+00:00 and verified unchanged after measurement at 2026-11-02T16:41:12.865245+00:00:

```text
d35881fe270ef634dc350269c045e4f510f68711  src/main/kotlin/dev/kotrace/report/Report.kt
fe26823a4fd8e43b9f4e8e8d7016b204485ff754  src/main/kotlin/dev/kotrace/report/TraceTreeIndex.kt
1f624dcbbaeb5321bca7163f331e020974fee50a  src/main/kotlin/dev/kotrace/report/TraceFormat.kt
0c4557a1420b51cfcebb257d6388bac617fadf48  src/main/kotlin/dev/kotrace/Trace.kt
a2393bf58dd082850fb2cc380f90969483d875a7  src/main/kotlin/dev/kotrace/Span.kt
a14a69ffeb11585d972e50c3cf8d98dbcb0171b1  src/main/kotlin/dev/kotrace/event/Emit.kt
4f73592f4c7bb0a424887a5a9948ea328849c1ef  src/main/kotlin/dev/kotrace/internal/FaultIsolation.kt
b97fc485526ce232f1200cfd9c3192ddba5848e8  benchmarks/src/main/kotlin/dev/kotrace/benchmarks/BenchmarkSupport.kt
331368655b9b55b288bcb43ba694afd99830d51a  benchmarks/src/test/kotlin/dev/kotrace/benchmarks/BenchmarkPreflightTest.kt
387c45c615561f0b8553ca51a618bf6b5037be5b  src/main/kotlin/dev/kotrace/ThreadMirror.kt
a5beba2e6ceb2ace335279142bab6a855594970b  src/main/kotlin/dev/kotrace/event/SpanRecord.kt
```

## Integrity checks

- `./gradlew :benchmarks:test` passed, initially reusing its test output. `./gradlew :benchmarks:test --rerun` then executed all seven tests: none failed, errored or were skipped. The two preflight logs and their completion statuses are preserved.
- Exactly 90 distinct main cases ran. Benchmark names and complete parameter combinations match `20261102-full/main-gc.json` and `20261017-full/main-gc.json`.
- Every case uses AverageTime, one harness thread, three forks, five warm-up iterations of one second and five measurement iterations of one second, with garbage collection profiling.
- All 90 primary scores and all 447 primary and secondary score values are numeric and finite. All primary errors and confidence bounds are finite. All 1,350 primary measurements are finite, with exactly three forks and five measurements per fork in every case.
- The main launcher exited with status zero. The harness completed normally, and neither its log nor the launcher log contains a benchmark failure, failed fork, uncaught thread exception or timeout marker.
- All eleven requested source fingerprints are unchanged. An additional check verified all 196 initially tracked files outside results and all fifteen files in `20261102-full` unchanged. HEAD was checked again after measurement.
- There was one complete main attempt, with no interruption or replacement run. No build, test, other benchmark or heavy analysis command ran alongside measurement. Only short log polls ran. Process enumeration was denied, so unrelated application activity could not be independently ruled out.
- Environment diagnostics: `RTK.md`, referenced by the supplied instructions, is absent from this repository. Gradle warned that it could not start the file-system event stream. The initial preflight also warned that Android analytics settings were not writable. Hardware inspection and process enumeration were denied. None of these diagnostics failed preflight or the benchmark run.
- Only `benchmarks/results/20261102-fixed/` was edited directly. No source, README, benchmark code or previous result was changed. Nothing was committed or pushed.

## Follow-up run

`SinkLatencyBenchmark` was deliberately not rerun. The fix changes ended-span lifecycle handling and the report
freeze and walk, without changing live sink delivery. The sink figures in
`../20261102-full/sink-latency.json` and its analysis stand. They are inherited measurements, not new results
from this run. The main regex excludes this benchmark.

For comparison with 0.5.0, every matching case in `followup-cap.json`, `followup-capture.json`,
`followup-conc.json`, `followup-life.json` and `followup-report.json` supersedes the corresponding October
`main-gc.json` case. Twelve cases are superseded. Every one of the 90 new cases is compared with both the
resolved October reference and `20261102-full` in `analysis.md`.

The supplied five-case report bisect is also preserved locally under `bisect/`: `v060.json` measures
`f912632d6b5267d8a782f67c9eef40f81ddd93bd` (`v0.6.0`), `pre044.json` measures
`01c1721fa2a85f1abd6bf092e8e27bda238a1d80` (`a6aed48^`), `head.json` measures
`a6aed48fae701070cdc18b6de96ebd57f4b951ea`, and `fix.json` measures the uncommitted fix before `d0d8ac6`.
The name `head.json` belongs to that earlier bisect, not to this run's HEAD. Its command is preserved as
`bisect/run-command.log`; `bisect/provenance.json` records the supplied directory and original SHA-1 values.
These are supplied measurements, not additional executions in this session. The full committed-fix run is the
source for the new README figures.

## Executed command

Preflight:

```text
./gradlew :benchmarks:test
./gradlew :benchmarks:test --rerun
```

Main run:

```text
./gradlew :benchmarks:jmh -PjmhArgs=".*(Lifecycle|Capture|EventKind|EventCap|EventRead|LiveFanout|SensitiveRouting|ReportScaling|ReportFailureScaling|ReportOutcome|Failure|Concurrency)Benchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/20261102-fixed/main-gc.json -o benchmarks/results/20261102-fixed/main-gc.log"
```

The main command was started in the background through `nohup python3` with a small launcher, a new session,
standard input disconnected and output redirected to `main-launcher.log`. Its original launcher source is
archived as `main-runner.log`. The harness wrote `main-gc.json` and `main-gc.log`; start time, finish time,
command and exit status are in `main-status.json`. `integrity-before.json`, `integrity-after.json` and
`comparison.json` preserve the source checks and unrounded comparison bookkeeping. Raw results and local
bookkeeping are ignored by Git; only `metadata.md` and `analysis.md` are intended additions for review.
