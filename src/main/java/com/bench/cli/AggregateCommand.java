package com.bench.cli;

import com.bench.metrics.SummaryWriter;
import com.bench.report.HistogramAggregator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.HdrHistogram.Histogram;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Aggregates benchmark results across multiple runs and instances.
 */
@Command(
    name = "aggregate",
    description = "Aggregate benchmark results from multiple runs and instances"
)
public class AggregateCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(AggregateCommand.class);
    
    @Option(names = {"-i", "--input-dir"}, description = "Input directory with raw results", defaultValue = "results/raw")
    private String inputDir;
    
    @Option(names = {"-o", "--output-file"}, description = "Output CSV file", defaultValue = "results/summary_aggregated.csv")
    private String outputFile;
    
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    @Override
    public Integer call() throws Exception {
        logger.info("Aggregating results from: {}", inputDir);
        
        Path inputPath = Paths.get(inputDir);
        if (!Files.exists(inputPath) || !Files.isDirectory(inputPath)) {
            logger.error("Input directory does not exist or is not a directory: {}", inputDir);
            return 1;
        }
        
        // Scan directory structure and group runs
        Map<String, List<RunData>> groupedRuns = scanAndGroupRuns(inputPath);
        
        if (groupedRuns.isEmpty()) {
            logger.warn("No benchmark runs found in: {}", inputDir);
            return 0;
        }
        
        logger.info("Found {} unique configurations with multiple runs", groupedRuns.size());
        
        // Aggregate each group
        List<AggregatedResult> results = new ArrayList<>();
        for (Map.Entry<String, List<RunData>> entry : groupedRuns.entrySet()) {
            String key = entry.getKey();
            List<RunData> runs = entry.getValue();
            
            logger.info("Aggregating {} runs for: {}", runs.size(), key);
            AggregatedResult aggregated = aggregateRuns(key, runs);
            if (aggregated != null) {
                results.add(aggregated);
            }
        }
        
        // Write summary CSV
        writeSummaryCsv(results);
        
        logger.info("Aggregation complete. Results written to: {}", outputFile);
        return 0;
    }
    
    private Map<String, List<RunData>> scanAndGroupRuns(Path rootPath) throws Exception {
        Map<String, List<RunData>> grouped = new HashMap<>();
        
        // Walk directory tree looking for summary.json files
        Files.walk(rootPath)
            .filter(p -> p.getFileName().toString().equals("summary.json"))
            .forEach(summaryPath -> {
                try {
                    RunData runData = parseRunMetadata(rootPath, summaryPath);
                    if (runData != null) {
                        String key = generateGroupKey(runData);
                        grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(runData);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to process: {}", summaryPath, e);
                }
            });
        
        return grouped;
    }
    
    private RunData parseRunMetadata(Path rootPath, Path summaryPath) throws Exception {
        // Read summary.json to get metadata
        SummaryWriter.SummaryData summary;
        try (FileReader reader = new FileReader(summaryPath.toFile())) {
            summary = gson.fromJson(reader, SummaryWriter.SummaryData.class);
        }
        
        RunData data = new RunData();
        data.summaryPath = summaryPath;
        data.summary = summary;
        
        // Extract from path structure: results/raw/{timestamp}/{sut}/{workload}/{level}/{run_or_instance}/...
        Path relativePath = rootPath.relativize(summaryPath.getParent());
        String[] parts = relativePath.toString().split(File.separator.equals("\\") ? "\\\\" : File.separator);
        
        if (parts.length < 4) {
            logger.warn("Unexpected path structure: {}", relativePath);
            return null;
        }
        
        int idx = 0;
        data.timestamp = parts[idx++];
        data.sut = parts[idx++];
        data.workload = parts[idx++];
        
        // Parse RPS/level directory
        String levelDir = parts[idx++];
        if (levelDir.startsWith("rps_")) {
            data.rps = Integer.parseInt(levelDir.substring(4));
        } else if (levelDir.startsWith("overload_")) {
            data.rps = Integer.parseInt(levelDir.substring(9));
            data.isOverload = true;
        }
        
        // Parse run number or instance ID if present
        if (idx < parts.length) {
            String runOrInstance = parts[idx];
            if (runOrInstance.startsWith("run_")) {
                data.runNumber = Integer.parseInt(runOrInstance.substring(4));
            } else if (runOrInstance.startsWith("instance_")) {
                data.instanceId = Integer.parseInt(runOrInstance.substring(9));
            }
        }
        
        // Find HDR histogram file
        Path hdrPath = summaryPath.getParent().resolve("hdr.hlog");
        if (Files.exists(hdrPath)) {
            data.hdrPath = hdrPath;
        }
        
        return data;
    }
    
    private String generateGroupKey(RunData data) {
        // Group by timestamp, SUT, workload, and RPS level
        return String.format("%s/%s/%s/%d", data.timestamp, data.sut, data.workload, data.rps);
    }
    
    private AggregatedResult aggregateRuns(String key, List<RunData> runs) throws Exception {
        AggregatedResult result = new AggregatedResult();
        
        String[] parts = key.split("/");
        result.timestamp = parts[0];
        result.sut = parts[1];
        result.workload = parts[2];
        result.rps = Integer.parseInt(parts[3]);
        result.numRuns = runs.size();
        
        // Check if these are multiple instances of the same run or multiple repetitions
        boolean hasMultipleInstances = runs.stream().anyMatch(r -> r.instanceId >= 0);
        boolean hasMultipleRuns = runs.stream().anyMatch(r -> r.runNumber > 0);
        
        if (hasMultipleInstances) {
            // Aggregate across instances (same run, different replicas)
            result = aggregateInstances(result, runs);
        }
        
        if (hasMultipleRuns) {
            // Aggregate across repetitions (different runs)
            result = aggregateRepetitions(result, runs);
        }
        
        // If neither, just use the single run
        if (!hasMultipleInstances && !hasMultipleRuns && !runs.isEmpty()) {
            RunData run = runs.get(0);
            result.achievedRps = run.summary.achievedThroughputRps;
            result.errorRate = run.summary.errorRate;
            result.p50 = run.summary.latencyMs.p50;
            result.p95 = run.summary.latencyMs.p95;
            result.p99 = run.summary.latencyMs.p99;
            result.p999 = run.summary.latencyMs.p999;
        }
        
        return result;
    }
    
    private AggregatedResult aggregateInstances(AggregatedResult result, List<RunData> runs) throws Exception {
        logger.info("Aggregating {} instances", runs.size());
        
        // Sum throughput and errors across instances
        double totalAttemptedRps = 0;
        double totalAchievedRps = 0;
        long totalErrors = 0;
        long totalCompleted = 0;
        
        for (RunData run : runs) {
            totalAttemptedRps += run.summary.attemptedRps;
            totalAchievedRps += run.summary.achievedThroughputRps;
            
            // Calculate errors from error rate
            long completed = (long) (run.summary.achievedThroughputRps * 
                (run.summary.latencyMs.p50 > 0 ? 60 : 1)); // Estimate duration
            totalCompleted += completed;
            totalErrors += (long) (completed * run.summary.errorRate);
        }
        
        result.attemptedRps = totalAttemptedRps;
        result.achievedRps = totalAchievedRps;
        result.errorRate = totalCompleted > 0 ? (double) totalErrors / totalCompleted : 0.0;
        
        // Merge HDR histograms for correct percentiles
        List<Path> hdrPaths = runs.stream()
            .filter(r -> r.hdrPath != null)
            .map(r -> r.hdrPath)
            .collect(Collectors.toList());
        
        if (!hdrPaths.isEmpty()) {
            try {
                Histogram merged = HistogramAggregator.aggregateHistogramLogs(hdrPaths);
                result.p50 = merged.getValueAtPercentile(50.0) / 1000.0;
                result.p95 = merged.getValueAtPercentile(95.0) / 1000.0;
                result.p99 = merged.getValueAtPercentile(99.0) / 1000.0;
                result.p999 = merged.getValueAtPercentile(99.9) / 1000.0;
                result.max = merged.getMaxValue() / 1000.0;
                
                logger.info("Merged histogram: p50={:.2f}ms, p95={:.2f}ms, p99={:.2f}ms", 
                    result.p50, result.p95, result.p99);
            } catch (Exception e) {
                logger.warn("Failed to merge histograms: {}", e.getMessage());
                // Fall back to averaging percentiles (not correct, but better than nothing)
                result.p50 = runs.stream().mapToDouble(r -> r.summary.latencyMs.p50).average().orElse(0);
                result.p95 = runs.stream().mapToDouble(r -> r.summary.latencyMs.p95).average().orElse(0);
                result.p99 = runs.stream().mapToDouble(r -> r.summary.latencyMs.p99).average().orElse(0);
                result.p999 = runs.stream().mapToDouble(r -> r.summary.latencyMs.p999).average().orElse(0);
            }
        }
        
        return result;
    }
    
    private AggregatedResult aggregateRepetitions(AggregatedResult result, List<RunData> runs) {
        logger.info("Aggregating {} repetitions", runs.size());
        
        // Compute median and IQR for each metric
        List<Double> achievedRpsList = runs.stream()
            .map(r -> r.summary.achievedThroughputRps)
            .collect(Collectors.toList());
        List<Double> errorRates = runs.stream()
            .map(r -> r.summary.errorRate)
            .collect(Collectors.toList());
        List<Double> p50s = runs.stream()
            .map(r -> r.summary.latencyMs.p50)
            .collect(Collectors.toList());
        List<Double> p95s = runs.stream()
            .map(r -> r.summary.latencyMs.p95)
            .collect(Collectors.toList());
        List<Double> p99s = runs.stream()
            .map(r -> r.summary.latencyMs.p99)
            .collect(Collectors.toList());
        List<Double> p999s = runs.stream()
            .map(r -> r.summary.latencyMs.p999)
            .collect(Collectors.toList());
        
        result.achievedRps = computeMedian(achievedRpsList);
        result.achievedRpsIQR = computeIQR(achievedRpsList);
        
        result.errorRate = computeMedian(errorRates);
        result.errorRateIQR = computeIQR(errorRates);
        
        result.p50 = computeMedian(p50s);
        result.p50IQR = computeIQR(p50s);
        
        result.p95 = computeMedian(p95s);
        result.p95IQR = computeIQR(p95s);
        
        result.p99 = computeMedian(p99s);
        result.p99IQR = computeIQR(p99s);
        
        result.p999 = computeMedian(p999s);
        result.p999IQR = computeIQR(p999s);
        
        return result;
    }
    
    private double computeMedian(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        
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
        if (values.size() < 2) return 0.0;
        
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        
        int size = sorted.size();
        int q1Index = Math.max(0, size / 4);
        int q3Index = Math.min(size - 1, 3 * size / 4);
        
        double q1 = sorted.get(q1Index);
        double q3 = sorted.get(q3Index);
        
        return q3 - q1;
    }
    
    private void writeSummaryCsv(List<AggregatedResult> results) throws Exception {
        Path outputPath = Paths.get(outputFile);
        Files.createDirectories(outputPath.getParent());
        
        try (FileWriter writer = new FileWriter(outputPath.toFile())) {
            // Write header
            writer.write("timestamp,sut,workload,rps,num_runs,");
            writer.write("attempted_rps,achieved_rps,achieved_rps_iqr,");
            writer.write("error_rate,error_rate_iqr,");
            writer.write("p50_ms,p50_iqr,p95_ms,p95_iqr,p99_ms,p99_iqr,p999_ms,p999_iqr,max_ms\n");
            
            // Write data rows
            for (AggregatedResult result : results) {
                writer.write(String.format("%s,%s,%s,%d,%d,",
                    result.timestamp, result.sut, result.workload, result.rps, result.numRuns));
                writer.write(String.format("%.2f,%.2f,%.2f,",
                    result.attemptedRps, result.achievedRps, result.achievedRpsIQR));
                writer.write(String.format("%.6f,%.6f,",
                    result.errorRate, result.errorRateIQR));
                writer.write(String.format("%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f\n",
                    result.p50, result.p50IQR,
                    result.p95, result.p95IQR,
                    result.p99, result.p99IQR,
                    result.p999, result.p999IQR,
                    result.max));
            }
        }
        
        logger.info("Wrote {} aggregated results to: {}", results.size(), outputPath);
    }
    
    private static class RunData {
        Path summaryPath;
        Path hdrPath;
        SummaryWriter.SummaryData summary;
        String timestamp;
        String sut;
        String workload;
        int rps;
        int runNumber = -1;
        int instanceId = -1;
        boolean isOverload;
    }
    
    private static class AggregatedResult {
        String timestamp;
        String sut;
        String workload;
        int rps;
        int numRuns;
        
        // Aggregated metrics
        double attemptedRps;
        double achievedRps;
        double achievedRpsIQR;
        double errorRate;
        double errorRateIQR;
        
        // Latency percentiles
        double p50;
        double p50IQR;
        double p95;
        double p95IQR;
        double p99;
        double p99IQR;
        double p999;
        double p999IQR;
        double max;
    }
}
