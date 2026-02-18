package com.bench.cli;

import com.bench.config.BenchmarkConfig;
import com.bench.load.BenchmarkRunner;
import com.bench.metrics.SummaryWriter;
import com.google.gson.Gson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Implements capacity sweep functionality to find maximum throughput.
 */
@Command(
    name = "sweep",
    description = "Run a capacity sweep to find maximum sustainable throughput"
)
public class SweepCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(SweepCommand.class);
    
    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file (YAML)")
    private String configFile;
    
    @Option(names = {"--increment"}, description = "RPS increment percentage per level", defaultValue = "15.0")
    private double incrementPercent;
    
    @Option(names = {"--slo-p95-ms"}, description = "P95 latency SLO in milliseconds", defaultValue = "50")
    private int sloP95Ms;
    
    @Option(names = {"--error-rate-threshold"}, description = "Maximum acceptable error rate", defaultValue = "0.001")
    private double errorRateThreshold;
    
    @Option(names = {"--repeat"}, description = "Number of repetitions per level", defaultValue = "5")
    private int repeatCount;
    
    @Option(names = {"-o", "--output-dir"}, description = "Output directory")
    private String outputDir;
    
    @Override
    public Integer call() throws Exception {
        logger.info("Loading configuration from: {}", configFile);
        BenchmarkConfig config = ConfigLoader.loadFromYaml(configFile);
        
        // Set sweep parameters
        config.setSweepIncrementPercent(incrementPercent);
        config.setSloP95Ms(sloP95Ms);
        config.setErrorRateThreshold(errorRateThreshold);
        
        // Get initial RPS from config
        int initialRps = config.getWorkload().getTargetRps();
        logger.info("Starting sweep from {} RPS", initialRps);
        logger.info("Increment: {}%", incrementPercent);
        logger.info("SLO P95: {} ms, Error rate threshold: {}", sloP95Ms, errorRateThreshold);
        logger.info("Repetitions per level: {}", repeatCount);
        
        // Create timestamp for this sweep
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        
        if (outputDir == null) {
            outputDir = config.getOutputDir();
        }
        
        // Track results across levels
        List<LevelResult> levelResults = new ArrayList<>();
        int consecutiveViolations = 0;
        int currentRps = initialRps;
        int level = 0;
        
        while (true) {
            level++;
            logger.info("=== Level {}: {} RPS ===", level, currentRps);
            
            // Run N repetitions at this level
            List<RunResult> runResults = new ArrayList<>();
            
            for (int run = 1; run <= repeatCount; run++) {
                logger.info("Running repetition {}/{} at {} RPS", run, repeatCount, currentRps);
                
                // Set RPS for this run
                config.getWorkload().setTargetRps(currentRps);
                
                // Create output directory for this run
                String runOutputDir = String.format("%s/raw/%s/%s/%s/rps_%d/run_%d",
                    outputDir,
                    timestamp,
                    config.getConnectionMode(),
                    config.getWorkload().getType(),
                    currentRps,
                    run
                );
                
                // Run benchmark
                try {
                    BenchmarkRunner runner = new BenchmarkRunner(config, runOutputDir);
                    runner.run();
                    
                    // Load summary to get metrics
                    RunResult result = loadRunResult(runOutputDir);
                    runResults.add(result);
                    
                    logger.info("Run {}: P95={:.2f}ms, ErrorRate={:.4f}", 
                        run, result.p95Ms, result.errorRate);
                    
                } catch (Exception e) {
                    logger.error("Run {} failed", run, e);
                    return 1;
                }
            }
            
            // Aggregate results for this level
            LevelResult levelResult = aggregateLevel(currentRps, runResults);
            levelResults.add(levelResult);
            
            logger.info("Level {} aggregate: P95={:.2f}ms (median), ErrorRate={:.4f} (median)",
                level, levelResult.medianP95Ms, levelResult.medianErrorRate);
            
            // Check stopping conditions
            boolean sloViolation = levelResult.medianP95Ms > sloP95Ms;
            boolean errorViolation = levelResult.medianErrorRate > errorRateThreshold;
            boolean instability = detectInstability(runResults);
            
            if (sloViolation || errorViolation) {
                consecutiveViolations++;
                logger.warn("SLO violation detected (count: {})", consecutiveViolations);
                
                if (consecutiveViolations >= 2) {
                    logger.info("Stopping sweep: 2 consecutive SLO violations");
                    break;
                }
            } else {
                consecutiveViolations = 0;
            }
            
            if (instability) {
                logger.info("Stopping sweep: instability detected");
                break;
            }
            
            // Increase RPS for next level
            currentRps = (int) Math.ceil(currentRps * (1.0 + incrementPercent / 100.0));
        }
        
        // Write summary of sweep
        writeSweepSummary(outputDir, timestamp, levelResults);
        
        logger.info("=== Sweep complete ===");
        logger.info("Maximum sustainable RPS: {}", 
            levelResults.size() > 1 ? levelResults.get(levelResults.size() - 2).rps : initialRps);
        
        return 0;
    }
    
    private RunResult loadRunResult(String runOutputDir) throws Exception {
        Path summaryPath = Paths.get(runOutputDir, "summary.json");
        Gson gson = new Gson();
        
        try (FileReader reader = new FileReader(summaryPath.toFile())) {
            SummaryWriter.SummaryData summary = gson.fromJson(reader, SummaryWriter.SummaryData.class);
            
            RunResult result = new RunResult();
            result.p95Ms = summary.latencyMs.p95;
            result.p99Ms = summary.latencyMs.p99;
            result.errorRate = summary.errorRate;
            result.achievedRps = summary.achievedThroughputRps;
            
            return result;
        }
    }
    
    private LevelResult aggregateLevel(int rps, List<RunResult> runs) {
        LevelResult result = new LevelResult();
        result.rps = rps;
        
        // Extract metrics
        List<Double> p95s = new ArrayList<>();
        List<Double> p99s = new ArrayList<>();
        List<Double> errorRates = new ArrayList<>();
        List<Double> achievedRpsList = new ArrayList<>();
        
        for (RunResult run : runs) {
            p95s.add(run.p95Ms);
            p99s.add(run.p99Ms);
            errorRates.add(run.errorRate);
            achievedRpsList.add(run.achievedRps);
        }
        
        // Compute medians
        result.medianP95Ms = computeMedian(p95s);
        result.medianP99Ms = computeMedian(p99s);
        result.medianErrorRate = computeMedian(errorRates);
        result.medianAchievedRps = computeMedian(achievedRpsList);
        
        // Compute IQR for stability assessment
        result.p95IQR = computeIQR(p95s);
        
        return result;
    }
    
    private double computeMedian(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        
        int size = sorted.size();
        if (size % 2 == 0) {
            return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        } else {
            return sorted.get(size / 2);
        }
    }
    
    private double computeIQR(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        
        int size = sorted.size();
        int q1Index = size / 4;
        int q3Index = 3 * size / 4;
        
        double q1 = sorted.get(q1Index);
        double q3 = sorted.get(q3Index);
        
        return q3 - q1;
    }
    
    private boolean detectInstability(List<RunResult> runs) {
        // Check for high variance in error rates
        List<Double> errorRates = new ArrayList<>();
        for (RunResult run : runs) {
            errorRates.add(run.errorRate);
        }
        
        double medianErrorRate = computeMedian(errorRates);
        
        // If any run has error rate > 3x median, consider it unstable
        for (double errorRate : errorRates) {
            if (medianErrorRate > 0 && errorRate > 3 * medianErrorRate) {
                return true;
            }
        }
        
        return false;
    }
    
    private void writeSweepSummary(String outputDir, String timestamp, List<LevelResult> results) throws Exception {
        Path summaryPath = Paths.get(outputDir, "sweep_summary_" + timestamp + ".csv");
        
        StringBuilder sb = new StringBuilder();
        sb.append("rps,median_p95_ms,median_p99_ms,median_error_rate,median_achieved_rps,p95_iqr\n");
        
        for (LevelResult result : results) {
            sb.append(String.format("%d,%.2f,%.2f,%.6f,%.2f,%.2f\n",
                result.rps,
                result.medianP95Ms,
                result.medianP99Ms,
                result.medianErrorRate,
                result.medianAchievedRps,
                result.p95IQR
            ));
        }
        
        Files.write(summaryPath, sb.toString().getBytes());
        logger.info("Sweep summary written to: {}", summaryPath);
    }
    
    private static class RunResult {
        double p95Ms;
        double p99Ms;
        double errorRate;
        double achievedRps;
    }
    
    private static class LevelResult {
        int rps;
        double medianP95Ms;
        double medianP99Ms;
        double medianErrorRate;
        double medianAchievedRps;
        double p95IQR;
    }
}
