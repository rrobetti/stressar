package com.bench.cli;

import com.bench.config.BenchmarkConfig;
import com.bench.load.BenchmarkRunner;
import com.bench.metrics.SummaryWriter;
import com.google.gson.Gson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

/**
 * Implements overload testing to observe system behavior under extreme load.
 */
@Command(
    name = "overload",
    description = "Run overload test at high RPS to test system recovery"
)
public class OverloadCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(OverloadCommand.class);
    private static final double STABILITY_ERROR_RATE_THRESHOLD = 0.01;
    private static final double STABILITY_P95_LATENCY_MS = 1000.0;
    
    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file (YAML)")
    private String configFile;
    
    @Option(names = {"--max-rps"}, description = "Maximum sustainable RPS (from sweep results)")
    private Integer maxRps;
    
    @Option(names = {"--overload-factor"}, description = "Multiplier for overload", defaultValue = "1.3")
    private double overloadFactor;
    
    @Option(names = {"--duration"}, description = "Test duration in seconds", defaultValue = "600")
    private int durationSeconds;
    
    @Option(names = {"-o", "--output-dir"}, description = "Output directory")
    private String outputDir;
    
    @Option(names = {"--sweep-results"}, description = "Path to sweep summary CSV to read max RPS")
    private String sweepResultsPath;
    
    @Override
    public Integer call() throws Exception {
        logger.info("Loading configuration from: {}", configFile);
        BenchmarkConfig config = ConfigLoader.loadFromYaml(configFile);
        
        // Determine max RPS
        int targetMaxRps;
        if (maxRps != null) {
            targetMaxRps = maxRps;
            logger.info("Using provided max RPS: {}", targetMaxRps);
        } else if (sweepResultsPath != null) {
            targetMaxRps = readMaxRpsFromSweep(sweepResultsPath);
            logger.info("Read max RPS from sweep results: {}", targetMaxRps);
        } else {
            logger.error("Must provide either --max-rps or --sweep-results");
            return 1;
        }
        
        // Calculate overload RPS
        int overloadRps = (int) Math.ceil(targetMaxRps * overloadFactor);
        logger.info("Overload test at {} RPS ({}x max throughput)", overloadRps, overloadFactor);
        
        // Update config
        config.getWorkload().setTargetRps(overloadRps);
        config.getWorkload().setDurationSeconds(durationSeconds);
        
        // Extend cooldown to observe recovery
        int originalCooldown = config.getWorkload().getCooldownSeconds();
        int extendedCooldown = Math.max(originalCooldown, 300);
        config.getWorkload().setCooldownSeconds(extendedCooldown);
        logger.info("Extended cooldown to {} seconds to observe recovery", extendedCooldown);
        
        // Create timestamp
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        
        if (outputDir == null) {
            outputDir = config.getOutputDir();
        }
        
        // Create output directory
        String runOutputDir = String.format("%s/raw/%s/%s/%s/overload_%d",
            outputDir,
            timestamp,
            config.getConnectionMode(),
            config.getWorkload().getType(),
            overloadRps
        );
        
        logger.info("Output directory: {}", runOutputDir);
        logger.info("Running overload test for {} seconds", durationSeconds);
        
        // Run benchmark
        try {
            BenchmarkRunner runner = new BenchmarkRunner(config, runOutputDir);
            runner.run();
            
            // Load and display results
            Path summaryPath = Paths.get(runOutputDir, "summary.json");
            Gson gson = new Gson();
            
            try (FileReader reader = new FileReader(summaryPath.toFile())) {
                SummaryWriter.SummaryData summary = gson.fromJson(reader, SummaryWriter.SummaryData.class);
                
                logger.info("=== Overload test complete ===");
                logger.info("Target RPS: {}", overloadRps);
                logger.info("Achieved RPS: {:.2f}", summary.achievedThroughputRps);
                logger.info("Error rate: {:.4f}", summary.errorRate);
                logger.info("P95 latency: {:.2f} ms", summary.latencyMs.p95);
                logger.info("P99 latency: {:.2f} ms", summary.latencyMs.p99);
                logger.info("Max latency: {:.2f} ms", summary.latencyMs.max);
                logger.info("Results saved to: {}", runOutputDir);
                
                // Check if system maintained stability
                if (summary.errorRate < STABILITY_ERROR_RATE_THRESHOLD 
                        && summary.latencyMs.p95 < STABILITY_P95_LATENCY_MS) {
                    logger.info("System handled overload gracefully");
                } else {
                    logger.warn("System showed stress under overload (high errors or latency)");
                }
            }
            
        } catch (Exception e) {
            logger.error("Overload test failed", e);
            return 1;
        }
        
        return 0;
    }
    
    private int readMaxRpsFromSweep(String sweepPath) throws Exception {
        Path path = Paths.get(sweepPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Sweep results file not found: " + sweepPath);
        }
        
        // Read CSV and find the last valid RPS (before violations)
        String content = new String(Files.readAllBytes(path));
        String[] lines = content.split("\n");
        
        int maxRps = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            String[] parts = line.split(",");
            if (parts.length >= 1) {
                try {
                    int rps = Integer.parseInt(parts[0].trim());
                    maxRps = rps;
                } catch (NumberFormatException e) {
                    // Skip invalid lines
                }
            }
        }
        
        if (maxRps == 0) {
            throw new IllegalArgumentException("Could not parse max RPS from sweep results");
        }
        
        // Use second-to-last level as max sustainable (last level likely violated SLO)
        if (lines.length > 2) {
            String[] parts = lines[lines.length - 2].split(",");
            if (parts.length >= 1) {
                try {
                    maxRps = Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException e) {
                    // Use the one we already found
                }
            }
        }
        
        return maxRps;
    }
}
