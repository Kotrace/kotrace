package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class FailureBenchmark {
    @Param({"1", "8", "32"})
    public int depth;

    @Benchmark
    public int coroutineThrowBaseline() {
        return BenchWorkloads.failureBaseline(depth);
    }

    @Benchmark
    public int tracedFailureClimb() {
        return BenchWorkloads.failureClimb(depth);
    }
}
