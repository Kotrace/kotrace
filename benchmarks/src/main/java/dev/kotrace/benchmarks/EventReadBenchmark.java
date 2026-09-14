package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/** Traverses an already-built span event snapshot; no append happens in the timed operation. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class EventReadBenchmark {
    @Param({"10", "100", "1000"})
    public int eventCount;

    private EventReadFixture fixture;

    @Setup(Level.Trial)
    public void setup() {
        fixture = BenchWorkloads.buildEventReadFixture(eventCount);
    }

    @Benchmark
    public long traverseSnapshot() {
        return fixture.readAll();
    }
}
