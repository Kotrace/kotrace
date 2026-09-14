package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class CaptureBenchmark {
    @Param({"0", "10", "100", "1000"})
    public int eventCount;

    @Param({"NONE", "LIVE_REJECT", "LIVE_ACCEPT"})
    public String mode;

    private BenchSession session;

    @Setup(Level.Trial)
    public void setup() {
        session = new BenchSession(mode, 1, 0);
        session.enter();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
    }

    @Benchmark
    public long captureLogBatch() {
        return session.captureLogBatch(eventCount);
    }
}
