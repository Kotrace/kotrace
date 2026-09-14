# kotrace benchmark run — stage 2

- Run id: `20260913-sol-high-stage2`
- Requested Codex profile: `gpt-5.6-sol`, reasoning `high`
- Note: the host controls the active Codex model; the benchmark runner itself is independent of the model.
- Source commit before harness edits: `40cf20a87d5d5c49f4c1d029f07d6ac555cc3ec4`
- Source state: dirty; pre-existing edits in `DECISIONS.md` and `decisions/adr-015-exception-origin-token.md`, plus the value-stream SVG. Exact final diff is part of the handoff.
- Machine: Apple M1 Pro, 10 physical / 10 logical CPUs, 32 GiB RAM, arm64
- OS: macOS 14.6.1 (23G93)
- Benchmark JVM: Oracle JDK 21.0.3, selected by the Gradle toolchain
- Bytecode target: JVM 11
- Gradle: 9.3.1
- Kotlin plugin: 2.4.10
- kotlinx.coroutines: 1.11.0
- JMH: 1.37
- Fork JVM flags: `-Xms1g -Xmx1g -XX:+UseG1GC -Dfile.encoding=UTF-8`

## Planned commands

Correctness preflight:

```text
./gradlew :benchmarks:test :benchmarks:classes
```

Smoke, every benchmark case:

```text
./gradlew :benchmarks:jmh -PjmhArgs=".*Benchmark.* -f 1 -wi 1 -i 1 -w 1s -r 1s -rf json -rff benchmarks/results/20260913-sol-high-stage2/smoke.json -o benchmarks/results/20260913-sol-high-stage2/smoke.log"
```

Main steady-state suite, excluding the deliberately slow sink latency suite:

```text
./gradlew :benchmarks:jmh -PjmhArgs=".*(Lifecycle|Capture|EventKind|LiveFanout|SensitiveRouting|ReportScaling|ReportOutcome|Failure|Concurrency)Benchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/20260913-sol-high-stage2/main-gc.json -o benchmarks/results/20260913-sol-high-stage2/main-gc.log"
```

The sink-latency SampleTime suite is run separately. Exact executed commands and deviations are appended after the run.
