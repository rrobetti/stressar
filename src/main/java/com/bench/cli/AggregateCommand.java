package com.bench.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Aggregates benchmark results across multiple runs.
 */
@Command(
    name = "aggregate",
    description = "Aggregate benchmark results from multiple runs"
)
public class AggregateCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(AggregateCommand.class);
    
    @Option(names = {"-i", "--input-dir"}, description = "Input directory with raw results", defaultValue = "results/raw")
    private String inputDir;
    
    @Option(names = {"-o", "--output-file"}, description = "Output CSV file", defaultValue = "results/summary_aggregated.csv")
    private String outputFile;
    
    @Override
    public Integer call() throws Exception {
        logger.info("Aggregating results from: {}", inputDir);
        
        Path inputPath = Paths.get(inputDir);
        if (!Files.exists(inputPath) || !Files.isDirectory(inputPath)) {
            logger.error("Input directory does not exist or is not a directory: {}", inputDir);
            return 1;
        }
        
        // Scan directory structure
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
            results.add(aggregated);
        }
        
        // Write summary CSV
        writeSummaryCsv(results);
        
        logger.info("Aggregation complete. Results written to: {}", outputFile);
        return 0;
    }
    
    private Map<String, List<RunData>> scanAndGroupRuns(Path rootPath) throws Exception {
        Map<String, List<RunData>> grouped = new HashMap<>();
        
        // Walk directory tree looking for summary.json files
        try (Stream<Path> paths = Files.walk(rootPath)) {
            paths
                .filter(p -> p.getFileName().toString().equals("summary.json"))
                .forEach(summaryPath -> {
                    try {
                        // Parse the directory structure to extract metadata
                        RunData runData = parseRunMetadata(rootPath, summaryPath);
                        if (runData != null) {
                            String key = generateGroupKey(runData);
                            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(runData);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to process: {}", summaryPath, e);
                    }
                });
        }
        
        return grouped;
    }
    
    private RunData parseRunMetadata(Path rootPath, Path summaryPath) {
        // Expected structure: results/raw/{timestamp}/{sut}/{workload}/rps_{rps}/run_{n}/summary.json
        // Or: results/raw/{timestamp}/{sut}/{workload}/overload_{rps}/summary.json
        
        Path relativePath = rootPath.relativize(summaryPath.getParent());
        String[] parts = relativePath.toString().split(File.separator);
        
        if (parts.length < 4) {
            return null;
        }
        
        RunData data = new RunData();
        data.summaryPath = summaryPath;
        
        // Parse from path structure
        int idx = 0;
        data.timestamp = parts[idx++];
        data.sut = parts[idx++];
        data.workload = parts[idx++];
        
        // Parse RPS level
        String rpsDir = parts[idx++];
        if (rpsDir.startsWith("rps_")) {
            data.rps = Integer.parseInt(rpsDir.substring(4));
        } else if (rpsDir.startsWith("overload_")) {
            data.rps = Integer.parseInt(rpsDir.substring(9));
            data.isOverload = true;
        }
        
        // Parse run number if present
        if (idx < parts.length && parts[idx].startsWith("run_")) {
            data.runNumber = Integer.parseInt(parts[idx].substring(4));
        }
        
        return data;
    }
    
    private String generateGroupKey(RunData data) {
        // Group by SUT, workload, and RPS level
        return String.format("%s/%s/%d", data.sut, data.workload, data.rps);
    }
    
    private AggregatedResult aggregateRuns(String key, List<RunData> runs) {
        // For now, just compute simple statistics
        // In a full implementation, would read each summary.json and aggregate metrics
        
        AggregatedResult result = new AggregatedResult();
        
        String[] parts = key.split("/");
        result.sut = parts[0];
        result.workload = parts[1];
        result.rps = Integer.parseInt(parts[2]);
        result.numRuns = runs.size();
        
        // Would read summary.json files and compute medians/IQR here
        // For now, just track the metadata
        result.timestamps = runs.stream()
            .map(r -> r.timestamp)
            .distinct()
            .collect(Collectors.toList());
        
        return result;
    }
    
    private void writeSummaryCsv(List<AggregatedResult> results) throws Exception {
        Path outputPath = Paths.get(outputFile);
        Files.createDirectories(outputPath.getParent());
        
        StringBuilder csv = new StringBuilder();
        csv.append("sut,workload,rps,num_runs,timestamps\n");
        
        for (AggregatedResult result : results) {
            csv.append(String.format("%s,%s,%d,%d,\"%s\"\n",
                result.sut,
                result.workload,
                result.rps,
                result.numRuns,
                String.join(";", result.timestamps)
            ));
        }
        
        Files.write(outputPath, csv.toString().getBytes());
    }
    
    private static class RunData {
        Path summaryPath;
        String timestamp;
        String sut;
        String workload;
        int rps;
        int runNumber;
        boolean isOverload;
    }
    
    private static class AggregatedResult {
        String sut;
        String workload;
        int rps;
        int numRuns;
        List<String> timestamps;
    }
}
