package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class LiveFanoutBenchmark {
    @Param({"1", "4", "16"})
    public int adapterCount;

    @Param({"LIVE_REJECT", "LIVE_ACCEPT"})
    public String mode;

    private BenchSession session;

    @Setup(Level.Trial)
    public void setup() {
        session = new BenchSession(mode, adapterCount, 0);
        session.enter();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
    }

    @Benchmark
    public long fanOut100Logs() {
        return session.captureLogBatch(100);
    }
}
