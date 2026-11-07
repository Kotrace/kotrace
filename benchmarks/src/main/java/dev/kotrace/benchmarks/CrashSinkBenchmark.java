package dev.kotrace.benchmarks;

import dev.kotrace.TraceStatus;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class CrashSinkBenchmark {
    @Param({"0", "1", "10"})
    public int attached;

    @Param({"OK", "ERROR"})
    public String status;

    @Param({"ALONE", "MIXED"})
    public String layout;

    private CrashSinkSession session;
    private ReportFixture fixture;

    @Setup(Level.Trial)
    public void setup() {
        fixture = BenchWorkloads.buildCrashSinkFixture(attached);
        session = new CrashSinkSession(layout);
        session.enter();
        session.report(fixture, TraceStatus.valueOf(status));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
    }

    @Benchmark
    public long reportCrashes() {
        return session.report(fixture, TraceStatus.valueOf(status));
    }
}
