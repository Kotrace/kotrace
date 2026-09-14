# kotrace benchmark run — stage 2b

- Run id: `20260914-sol-high-stage2b`
- Requested Codex profile: `gpt-5.6-sol`, reasoning `high`
- Purpose: clean rerun after the original stage-2 dataset was found to span two source snapshots
- Source base commit: `40cf20a87d5d5c49f4c1d029f07d6ac555cc3ec4`
- Source state: dirty, including the in-progress ADR-015/B01 implementation and benchmark harness
- Machine: Apple M1 Pro, 10 physical / 10 logical CPUs, 32 GiB RAM, arm64
- OS: macOS 14.6.1 (23G93)
- Benchmark JVM: Oracle JDK 21.0.3, selected by the Gradle toolchain
- Bytecode target: JVM 11
- Gradle: 9.3.1
- Kotlin plugin: 2.4.10
- kotlinx.coroutines: 1.11.0
- JMH: 1.37
- Fork JVM flags: `-Xms1g -Xmx1g -XX:+UseG1GC -Dfile.encoding=UTF-8`
- Main run duration: 40m 11s

## Source fingerprints

The relevant core and harness files were unchanged while the main suite ran:

```text
aef1717553737ba08180cf8c0522d2a69b74a73e  src/main/kotlin/dev/kotrace/Report.kt
4bff890c8773f9181bd7a30ee96a2e8f91762962  src/main/kotlin/dev/kotrace/Trace.kt
7548e08d94568ae68ad8dce36812df10ca092ed9  src/main/kotlin/dev/kotrace/TraceFormat.kt
500416a11c996d5411cbdd08100c57b27ce0d8c1  src/main/kotlin/dev/kotrace/event/TraceException.kt
80218047540404145266782965f09ae73d39900e  benchmarks/src/main/kotlin/dev/kotrace/benchmarks/BenchmarkSupport.kt
52a003f8ba7eba534d0ea415c00c281b15a35638  benchmarks/src/test/kotlin/dev/kotrace/benchmarks/BenchmarkPreflightTest.kt
```

## Integrity checks

- Correctness preflight was forced with `--rerun-tasks` and passed, including the fixed B01 expectation.
- Main suite contains 78/78 expected parameterized cases.
- Every primary score is numeric and finite; no fork or benchmark failure was found.
- Main suite used 3 forks, 5 × 1 s warm-up and 5 × 1 s measurement with `-prof gc`.
- One representative capture case was rerun without the GC profiler.
- The 16-worker shared-span hotspot was run in SampleTime and Throughput modes.
- The synthetic 1 ms live sink was run separately in SampleTime mode.
- Retained-heap/dominator analysis was not run; allocation B/op is not treated as retained memory.

## Executed commands

```text
./gradlew :benchmarks:test --rerun-tasks

./gradlew :benchmarks:jmh -PjmhArgs=".*(Lifecycle|Capture|EventKind|LiveFanout|SensitiveRouting|ReportScaling|ReportOutcome|Failure|Concurrency)Benchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/20260914-sol-high-stage2b/main-gc.json -o benchmarks/results/20260914-sol-high-stage2b/main-gc.log"

./gradlew :benchmarks:jmh -PjmhArgs=".*ConcurrencyBenchmark.runWorkers.* -p mode=SHARED -p workerCount=16 -bm sample,thrpt -f 3 -wi 3 -i 5 -w 1s -r 1s -rf json -rff benchmarks/results/20260914-sol-high-stage2b/concurrency-sample-throughput.json -o benchmarks/results/20260914-sol-high-stage2b/concurrency-sample-throughput.log"

./gradlew :benchmarks:jmh -PjmhArgs=".*CaptureBenchmark.captureLogBatch.* -p eventCount=100 -p mode=LIVE_ACCEPT -f 3 -wi 5 -i 5 -w 1s -r 1s -rf json -rff benchmarks/results/20260914-sol-high-stage2b/capture100-no-profiler.json -o benchmarks/results/20260914-sol-high-stage2b/capture100-no-profiler.log"

./gradlew :benchmarks:jmh -PjmhArgs=".*SinkLatencyBenchmark.synchronousSinkDelay.* -f 3 -wi 3 -i 5 -w 1s -r 1s -rf json -rff benchmarks/results/20260914-sol-high-stage2b/sink-latency.json -o benchmarks/results/20260914-sol-high-stage2b/sink-latency.log"
```
