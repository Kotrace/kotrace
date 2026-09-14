package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class SensitiveRoutingBenchmark {
    @Param({"LIVE_ACCEPT", "LIVE_SENSITIVE"})
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
    public long route100SensitiveLogs() {
        return session.sensitiveBatch(100, true);
    }
}
