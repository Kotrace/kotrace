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
public class ReportScalingBenchmark {
    @Param({"CHAIN", "STAR", "BALANCED"})
    public String shape;

    @Param({"1", "16", "128", "512"})
    public int spanCount;

    private BenchSession session;
    private ReportFixture fixture;

    @Setup(Level.Trial)
    public void setup() {
        fixture = BenchWorkloads.buildReportFixture(shape, spanCount);
        session = new BenchSession("REPORT_CONSUME", 1, 0);
        session.enter();
        session.report(fixture, TraceStatus.OK); // warm lazy messages; this benchmark is labelled warm-message.
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        session.close();
    }

    @Benchmark
    public long reportWarmMessages() {
        return session.report(fixture, TraceStatus.OK);
    }
}
