package dev.kotrace.benchmarks;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/** Pins the fork JVM memory/GC settings while leaving benchmark selection and iteration controls on the CLI. */
public final class BenchmarkMain {
    private BenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        Options options = new OptionsBuilder()
            .parent(new CommandLineOptions(args))
            .jvmArgsAppend("-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8")
            .build();
        new Runner(options).run();
    }
}
