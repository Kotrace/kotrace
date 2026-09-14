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
public class ReportOutcomeBenchmark {
    @Param({"REPORT_SKIP", "REPORT_CONSUME"})
    public String mode;

    @Param({"OK", "ERROR", "CANCELLED"})
    public String status;

    @Param({"1", "4", "16"})
    public int adapterCount;

    private BenchSession session;
    private ReportFixture fixture;

    @Setup(Level.Trial)
    public void setup() {
        fixture = BenchWorkloads.buildReportFixture("BALANCED", 128);
        session = new BenchSession(mode, adapterCount, 0);
        session.enter();
        session.report(fixture, TraceStatus.valueOf(status));
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
    }

    @Benchmark
    public long reportByOutcome() {
        return session.report(fixture, TraceStatus.valueOf(status));
    }
}
