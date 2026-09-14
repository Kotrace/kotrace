package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class SinkLatencyBenchmark {
    @Param({"1", "10"})
    public int eventCount;

    private BenchSession session;

    @Setup(Level.Trial)
    public void setup() {
        session = new BenchSession("LIVE_DELAY", 1, 1_000_000);
        session.enter();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
    }

    @Benchmark
    public long synchronousSinkDelay() {
        return session.captureLogBatch(eventCount);
    }
}
