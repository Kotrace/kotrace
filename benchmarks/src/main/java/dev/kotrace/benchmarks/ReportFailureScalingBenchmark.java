package dev.kotrace.benchmarks;

import dev.kotrace.TraceStatus;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/** Report traversal with one distinct birthplace exception on every child span. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ReportFailureScalingBenchmark {
    @Param({"16", "128", "512"})
    public int spanCount;

    private BenchSession session;
    private ReportFixture fixture;

    @Setup(Level.Trial)
    public void setup() {
        fixture = BenchWorkloads.buildExceptionReportFixture(spanCount);
        session = new BenchSession("REPORT_CONSUME", 1, 0);
        session.enter();
        session.report(fixture, TraceStatus.ERROR);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
    }

    @Benchmark
    public long reportExceptionBirthplaces() {
        return session.report(fixture, TraceStatus.ERROR);
    }
}
