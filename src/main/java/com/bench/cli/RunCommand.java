package com.bench.cli;

import com.bench.config.BenchmarkConfig;
import com.bench.load.BenchmarkRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

/**
 * Run a single benchmark iteration.
 */
@Command(
    name = "run",
    description = "Run a single benchmark iteration"
)
public class RunCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(RunCommand.class);
    
    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file (YAML)")
    private String configFile;
    
    @Option(names = {"-o", "--output"}, description = "Output directory")
    private String outputDir;
    
    @Option(names = {"--instance-id"}, description = "Instance ID for multi-replica runs", defaultValue = "0")
    private int instanceId;
    
    @Override
    public Integer call() throws Exception {
        logger.info("Loading configuration from: {}", configFile);
        BenchmarkConfig config = ConfigLoader.loadFromYaml(configFile);
        config.setInstanceId(instanceId);
        
        // Set output directory
        if (outputDir == null) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            outputDir = String.format("%s/raw/%s/%s/%s/instance_%d",
                config.getOutputDir(),
                timestamp,
                config.getConnectionMode(),
                config.getWorkload().getType(),
                instanceId
            );
        }
        
        logger.info("Output directory: {}", outputDir);
        
        // Run benchmark
        BenchmarkRunner runner = new BenchmarkRunner(config, outputDir);
        runner.run();
        
        return 0;
    }
}
