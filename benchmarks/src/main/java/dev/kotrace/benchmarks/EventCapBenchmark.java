package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * The per-span event cap (ADR-029/033) on one thread with no sink: 2,000 logs onto a span capped at 100, 1,000 or
 * 10,000. The first two evict on every append past the cap; the last never evicts but copies a longer array.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class EventCapBenchmark {
    @Param({"2000"})
    public int eventCount;

    @Param({"100", "1000", "10000"})
    public int maxEvents;

    private BenchSession session;

    @Setup(Level.Trial)
    public void setup() {
        session = new BenchSession("NONE", 1, 0);
        session.enter();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
    }

    @Benchmark
    public long captureCappedBatch() {
        return session.captureCappedBatch(eventCount, maxEvents);
    }
}
