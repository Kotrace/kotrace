package dev.kotrace.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class LifecycleBenchmark {
    private final BenchSession empty = new BenchSession("EMPTY", 0, 0);
    private final BenchSession live = new BenchSession("LIVE_ACCEPT", 1, 0);
    private final BenchSession report = new BenchSession("REPORT_SKIP", 1, 0);
    private int value;

    @Setup(Level.Iteration)
    public void varyInput() {
        value++;
    }

    @Benchmark
    public int plain() {
        return BenchWorkloads.plain(value);
    }

    @Benchmark
    public int coroutineBaseline() {
        return BenchWorkloads.coroutineBaseline(value);
    }

    @Benchmark
    public int configuredCoroutineBaseline() {
        return empty.configuredBaseline(value);
    }

    @Benchmark
    public int autoRootNoConfig() {
        return BenchWorkloads.autoRootNoConfig(value);
    }

    @Benchmark
    public int autoRootEmptyConfig() {
        return empty.autoRoot(value);
    }

    @Benchmark
    public int autoRootLiveAdapter() {
        return live.autoRoot(value);
    }

    @Benchmark
    public int autoRootReportSkip() {
        return report.autoRoot(value);
    }

    @TearDown(Level.Trial)
    public void close() {
        empty.close();
        live.close();
        report.close();
    }
}
