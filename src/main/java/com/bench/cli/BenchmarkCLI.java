package com.bench.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main CLI entry point for the benchmark tool.
 */
@Command(
    name = "bench",
    description = "OJP Performance Benchmark Tool",
    mixinStandardHelpOptions = true,
    version = "1.0.0",
    subcommands = {
        InitDbCommand.class,
        WarmupCommand.class,
        RunCommand.class,
        SweepCommand.class,
        OverloadCommand.class,
        EnvSnapshotCommand.class,
        AggregateCommand.class
    }
)
public class BenchmarkCLI implements Runnable {
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new BenchmarkCLI()).execute(args);
        System.exit(exitCode);
    }
    
    @Override
    public void run() {
        // If no subcommand is specified, show help
        CommandLine.usage(this, System.out);
    }
}
