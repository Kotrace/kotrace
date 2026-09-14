# Report tree index candidate v2 — metadata

- Baseline: `../20260914-sol-high-stage2b/main-gc.json`
- Candidate data: `report-gc.json`
- Rejected first candidate: `../20260914-report-index-candidate/report-gc.json`
- JVM/JMH/fork profile: identical to the stage2b main suite
- Result count: 15; invalid primary scores: 0

## Source fingerprints

```text
32dfdd8b3fca3a1cf0e811e6549004b6ab129f17  src/main/kotlin/dev/kotrace/Report.kt
870d38cf6b332a183c35b920c36df1c2d7755917  src/main/kotlin/dev/kotrace/TraceFormat.kt
53897cdf4268a48f716f824b05e2754bcc4f9353  benchmarks/src/main/kotlin/dev/kotrace/benchmarks/BenchmarkSupport.kt
0bd2de3ad94b048b3532084c22d1bcee24b7bbda  benchmarks/src/main/java/dev/kotrace/benchmarks/ReportFailureScalingBenchmark.java
761c7b5063ce440e9ce53aa765b190db9816cfde  benchmarks/src/test/kotlin/dev/kotrace/benchmarks/BenchmarkPreflightTest.kt
```

## Command

```text
./gradlew :benchmarks:jmh -PjmhArgs=".*Report.*ScalingBenchmark.* -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff benchmarks/results/20260914-report-index-candidate-v2/report-gc.json -o benchmarks/results/20260914-report-index-candidate-v2/report-gc.log"
```
