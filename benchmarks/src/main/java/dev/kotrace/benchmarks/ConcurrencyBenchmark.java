package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ConcurrencyBenchmark {
    @Param({"BASELINE", "SHARED", "CHILDREN"})
    public String mode;

    @Param({"1", "4", "16"})
    public int workerCount;

    private ConcurrencyHarness harness;

    @Setup(Level.Trial)
    public void setup() {
        harness = new ConcurrencyHarness(mode);
        harness.run(workerCount, 10);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        harness.close();
    }

    @Benchmark
    public long runWorkers() {
        return harness.run(workerCount, 100);
    }
}
