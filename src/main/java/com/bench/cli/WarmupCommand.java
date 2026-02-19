package com.bench.cli;

import com.bench.config.BenchmarkConfig;
import com.bench.load.BenchmarkRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * Run warmup phase only (no metrics collection).
 */
@Command(
    name = "warmup",
    description = "Run warmup phase to prepare the database and connections"
)
public class WarmupCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(WarmupCommand.class);
    
    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file (YAML)")
    private String configFile;
    
    @Option(names = {"--duration"}, description = "Warmup duration in seconds", defaultValue = "300")
    private int durationSeconds;
    
    @Override
    public Integer call() throws Exception {
        logger.info("Loading configuration from: {}", configFile);
        BenchmarkConfig config = ConfigLoader.loadFromYaml(configFile);
        
        // Override to warmup-only mode
        config.getWorkload().setWarmupSeconds(durationSeconds);
        config.getWorkload().setDurationSeconds(0);
        config.getWorkload().setCooldownSeconds(0);
        
        // Run benchmark (will only do warmup)
        logger.info("Running warmup for {} seconds", durationSeconds);
        BenchmarkRunner runner = new BenchmarkRunner(config, "/tmp/warmup");
        runner.run();
        
        logger.info("Warmup complete");
        return 0;
    }
}
