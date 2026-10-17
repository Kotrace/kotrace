# kotrace benchmark run: full suite after ADR-037

- Run id: `20261017-full`
- Purpose: refresh every figure after ADR-029 to ADR-037, the report walk trim, and the new event-cap benchmarks
- Started: 2026-10-17T07:09:43Z; main run duration 46m 36s
- Source base commit: `010ab79786ca349fa393c27fe07bc47d691d0d2a`
- Source state: dirty, the uncommitted 0.5.0 work (ADR-031 to ADR-037, the build conventions, the report walk trim)
- Machine: Apple M1 Pro, 10 physical / 10 logical CPUs, 32 GiB RAM, arm64
- OS: macOS 27.0.1 (26A434); the 2026-09-14 baseline ran on macOS 14.6.1
- Benchmark JVM: Oracle JDK 21.0.3 (21.0.3+7-LTS-152), selected by the Gradle toolchain
- Bytecode target: JVM 11, compiled against the Java 11 API (`-Xjdk-release=11`)
- Gradle: 9.3.1
- Kotlin plugin: 2.4.10; the core compiles at language and API 2.2
- kotlinx.coroutines: 1.10.2, the consumer floor (ADR-027); the baseline ran on 1.11.0
- JMH: 1.37
- Fork JVM flags: `-Xms1g -Xmx1g -XX:+UseG1GC -Dfile.encoding=UTF-8`

## Source fingerprints

Taken before the run and checked unchanged after it:

```text
0f7b62f42e3ced1850775157110a79606ff46c2e  src/main/kotlin/dev/kotrace/report/Report.kt
3e40256908b586bf2e8a09d7bf6f8d0111028d46  src/main/kotlin/dev/kotrace/report/TraceTreeIndex.kt
60803191a621ab90432971925c35b7013db2b4ea  src/main/kotlin/dev/kotrace/report/TraceFormat.kt
2daaa40ee9dfd64fc102181ab66bb1d2ded89e32  src/main/kotlin/dev/kotrace/Trace.kt
d0a8836874048cf726c4b33339fb902dd7754ee2  src/main/kotlin/dev/kotrace/Span.kt
ee557b749ae4258e29eed8e9df92aac873e379b9  src/main/kotlin/dev/kotrace/event/Emit.kt
4f73592f4c7bb0a424887a5a9948ea328849c1ef  src/main/kotlin/dev/kotrace/internal/FaultIsolation.kt
d070b319b28e649caff03aecb1902cb6c99e1f93  benchmarks/src/main/kotlin/dev/kotrace/benchmarks/BenchmarkSupport.kt
331368655b9b55b288bcb43ba694afd99830d51a  benchmarks/src/test/kotlin/dev/kotrace/benchmarks/BenchmarkPreflightTest.kt
```

## Integrity checks

- `./gradlew build` passed first, including the 7 benchmark preflight tests.
- The suite holds 90/90 expected parameterized cases; every primary score is numeric and finite.
- 3 forks, 5 × 1 s warm-up and 5 × 1 s measurement, with `-prof gc`.
- `SinkLatencyBenchmark` was not rerun; its figures stay those of `20260914-sol-high-stage2b`.

## Follow-up run

After the timeline change (`EventTimeline`, single-copy eviction) and the frozen-snapshot change, the affected
cases were rerun with the same profile; `followup-*.json` hold them (see the analysis).

```text
./gradlew :benchmarks:jmh -PjmhArgs="EventCapBenchmark -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff …/followup-cap.json"
./gradlew :benchmarks:jmh -PjmhArgs="ReportScalingBenchmark -p shape=BALANCED -p spanCount=1,16,512 … -rff …/followup-report.json"
./gradlew :benchmarks:jmh -PjmhArgs="ConcurrencyBenchmark -p mode=SHARED,SHARED_CAPPED -p workerCount=16 … -rff …/followup-conc.json"
./gradlew :benchmarks:jmh -PjmhArgs="CaptureBenchmark -p eventCount=0,100,1000 -p mode=NONE … -rff …/followup-capture.json"
./gradlew :benchmarks:jmh -PjmhArgs="LifecycleBenchmark.autoRootNoConfig … -rff …/followup-life.json"
```

## Executed command

```text
./gradlew :benchmarks:jmh -PjmhArgs=".*(Lifecycle|Capture|EventKind|EventCap|EventRead|LiveFanout|SensitiveRouting|ReportScaling|ReportFailureScaling|ReportOutcome|Failure|Concurrency)Benchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/20261017-full/main-gc.json -o benchmarks/results/20261017-full/main-gc.log"
```
