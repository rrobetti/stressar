package com.bench.report;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Aggregates HDR histograms across multiple replica runs.
 * 
 * Correctly merges histograms by adding counts (not averaging percentiles).
 * Produces aggregated summary statistics and charts.
 */
public class HistogramAggregator {
    private static final Logger logger = LoggerFactory.getLogger(HistogramAggregator.class);
    
    /**
     * Aggregate HDR histogram log files from multiple replicas.
     * 
     * @param hdrLogFiles List of paths to HDR log files
     * @return Merged histogram
     */
    public static Histogram aggregateHistogramLogs(List<Path> hdrLogFiles) throws IOException {
        if (hdrLogFiles.isEmpty()) {
            throw new IllegalArgumentException("No HDR log files provided");
        }
        
        logger.info("Aggregating {} HDR histogram log files", hdrLogFiles.size());
        
        Histogram aggregated = null;
        int filesProcessed = 0;
        
        for (Path logFile : hdrLogFiles) {
            if (!Files.exists(logFile)) {
                logger.warn("HDR log file not found: {}", logFile);
                continue;
            }
            
            try (HistogramLogReader reader = new HistogramLogReader(logFile.toFile())) {
                Histogram histogram;
                while ((histogram = (Histogram) reader.nextIntervalHistogram()) != null) {
                    if (aggregated == null) {
                        // First histogram - clone it
                        aggregated = histogram.copy();
                    } else {
                        // Add counts from this histogram
                        aggregated.add(histogram);
                    }
                }
                filesProcessed++;
                logger.debug("Processed HDR log: {}", logFile);
            } catch (Exception e) {
                logger.error("Failed to read HDR log file: {}", logFile, e);
            }
        }
        
        if (aggregated == null) {
            throw new IOException("Failed to aggregate histograms - no valid data found");
        }
        
        logger.info("Successfully aggregated {} histogram log files", filesProcessed);
        logger.info("Aggregated histogram: count={}, p50={}, p95={}, p99={}",
                   aggregated.getTotalCount(),
                   aggregated.getValueAtPercentile(50.0) / 1000.0,
                   aggregated.getValueAtPercentile(95.0) / 1000.0,
                   aggregated.getValueAtPercentile(99.0) / 1000.0);
        
        return aggregated;
    }
    
    /**
     * Find all HDR log files in a results directory tree.
     * 
     * @param resultsRoot Root directory to search
     * @param sutName Optional SUT name filter (null for all)
     * @param workloadName Optional workload name filter (null for all)
     * @return List of HDR log file paths
     */
    public static List<Path> findHdrLogFiles(Path resultsRoot, String sutName, String workloadName) throws IOException {
        List<Path> hdrLogs = new ArrayList<>();
        
        if (!Files.exists(resultsRoot)) {
            logger.warn("Results root directory does not exist: {}", resultsRoot);
            return hdrLogs;
        }
        
        try (Stream<Path> paths = Files.walk(resultsRoot)) {
            hdrLogs = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".hlog") ||
                           p.getFileName().toString().equals("hdr.hlog"))
                .filter(p -> sutName == null || p.toString().contains("/" + sutName + "/"))
                .filter(p -> workloadName == null || p.toString().contains("/" + workloadName + "/"))
                .collect(Collectors.toList());
        }
        
        logger.info("Found {} HDR log files in {}", hdrLogs.size(), resultsRoot);
        return hdrLogs;
    }
    
    /**
     * Group HDR log files by run configuration (SUT, workload, level).
     * 
     * @param resultsRoot Root directory to search
     * @return Map of run key -> list of HDR log files
     */
    public static Map<String, List<Path>> groupHdrLogsByRun(Path resultsRoot) throws IOException {
        Map<String, List<Path>> grouped = new HashMap<>();
        
        List<Path> allLogs = findHdrLogFiles(resultsRoot, null, null);
        
        for (Path logFile : allLogs) {
            // Extract run key from path
            // Expected structure: results/raw/<date>/<sut>/<workload>/<level>/<run_id>/<instance_id>/hdr.hlog
            String pathStr = logFile.toString();
            String[] parts = pathStr.split("/");
            
            if (parts.length >= 6) {
                // Find indices of key components
                int rawIdx = -1;
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equals("raw")) {
                        rawIdx = i;
                        break;
                    }
                }
                
                if (rawIdx >= 0 && rawIdx + 4 < parts.length) {
                    String sut = parts[rawIdx + 2];
                    String workload = parts[rawIdx + 3];
                    String level = parts[rawIdx + 4];
                    String runKey = sut + "/" + workload + "/" + level;
                    
                    grouped.computeIfAbsent(runKey, k -> new ArrayList<>()).add(logFile);
                }
            }
        }
        
        logger.info("Grouped {} HDR logs into {} run configurations", allLogs.size(), grouped.size());
        return grouped;
    }
    
    /**
     * Generate aggregated summary statistics from merged histogram.
     */
    public static Map<String, Object> generateSummaryStats(Histogram histogram) {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalCount", histogram.getTotalCount());
        stats.put("minLatencyUs", histogram.getMinValue());
        stats.put("maxLatencyUs", histogram.getMaxValue());
        stats.put("meanLatencyUs", histogram.getMean());
        stats.put("stdDevLatencyUs", histogram.getStdDeviation());
        
        // Percentiles in microseconds
        stats.put("p50Us", histogram.getValueAtPercentile(50.0));
        stats.put("p75Us", histogram.getValueAtPercentile(75.0));
        stats.put("p90Us", histogram.getValueAtPercentile(90.0));
        stats.put("p95Us", histogram.getValueAtPercentile(95.0));
        stats.put("p99Us", histogram.getValueAtPercentile(99.0));
        stats.put("p999Us", histogram.getValueAtPercentile(99.9));
        stats.put("p9999Us", histogram.getValueAtPercentile(99.99));
        
        // Percentiles in milliseconds for convenience
        stats.put("p50Ms", histogram.getValueAtPercentile(50.0) / 1000.0);
        stats.put("p95Ms", histogram.getValueAtPercentile(95.0) / 1000.0);
        stats.put("p99Ms", histogram.getValueAtPercentile(99.0) / 1000.0);
        stats.put("p999Ms", histogram.getValueAtPercentile(99.9) / 1000.0);
        
        return stats;
    }
    
    /**
     * Write aggregated summary to a file.
     */
    public static void writeAggregatedSummary(Histogram histogram, Path outputFile) throws IOException {
        Map<String, Object> stats = generateSummaryStats(histogram);
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Aggregated Histogram Summary\n\n");
        sb.append(String.format("Total Operations: %d\n", stats.get("totalCount")));
        sb.append(String.format("Min Latency: %.2f ms\n", (long)stats.get("minLatencyUs") / 1000.0));
        sb.append(String.format("Max Latency: %.2f ms\n", (long)stats.get("maxLatencyUs") / 1000.0));
        sb.append(String.format("Mean Latency: %.2f ms\n", (double)stats.get("meanLatencyUs") / 1000.0));
        sb.append(String.format("Std Dev: %.2f ms\n\n", (double)stats.get("stdDevLatencyUs") / 1000.0));
        
        sb.append("## Latency Percentiles\n\n");
        sb.append(String.format("p50:   %8.2f ms\n", stats.get("p50Ms")));
        sb.append(String.format("p75:   %8.2f ms\n", (long)stats.get("p75Us") / 1000.0));
        sb.append(String.format("p90:   %8.2f ms\n", (long)stats.get("p90Us") / 1000.0));
        sb.append(String.format("p95:   %8.2f ms\n", stats.get("p95Ms")));
        sb.append(String.format("p99:   %8.2f ms\n", stats.get("p99Ms")));
        sb.append(String.format("p99.9: %8.2f ms\n", stats.get("p999Ms")));
        sb.append(String.format("p99.99:%8.2f ms\n", (long)stats.get("p9999Us") / 1000.0));
        
        Files.writeString(outputFile, sb.toString());
        logger.info("Wrote aggregated summary to: {}", outputFile);
    }
}
