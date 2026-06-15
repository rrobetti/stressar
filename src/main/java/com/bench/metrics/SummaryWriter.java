package com.bench.metrics;

import com.bench.workloads.WorkloadClass;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writer for summary metrics to JSON file.
 */
public class SummaryWriter {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    /**
     * Write summary metrics to JSON file.
     */
    public void writeSummary(String filename, SummaryData summary) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            gson.toJson(summary, writer);
        }
    }
    
    /**
     * Create summary data from metrics snapshot.
     */
    public static SummaryData createSummary(MetricsSnapshot snapshot, BenchmarkRunInfo runInfo) {
        SummaryData summary = new SummaryData();
        
        // Run information
        summary.runInfo = runInfo;
        
        // Throughput metrics
        double elapsedSeconds = (snapshot.getTimestampMs() - snapshot.getStartTimeMs()) / 1000.0;
        summary.attemptedRps = elapsedSeconds > 0.0
            ? (double) snapshot.getAttemptedRequests() / elapsedSeconds
            : 0.0;
        summary.successfulThroughputRps = snapshot.getAchievedThroughput();
        summary.errorThroughputRps = snapshot.getErrorThroughput();
        summary.totalThroughputRps = snapshot.getTotalThroughput();
        summary.achievedThroughputRps = summary.successfulThroughputRps;
        summary.errorRate = snapshot.getErrorRate();
        summary.totalRequests = snapshot.getCompletedRequests() + snapshot.getErrors();
        summary.successfulRequests = snapshot.getCompletedRequests();
        summary.failedRequests = snapshot.getErrors();
        
        // Latency metrics
        summary.latencyMs = new LatencyMetrics();
        summary.latencyMs.p50 = snapshot.getP50();
        summary.latencyMs.p95 = snapshot.getP95();
        summary.latencyMs.p99 = snapshot.getP99();
        summary.latencyMs.p999 = snapshot.getP999();
        summary.latencyMs.max = snapshot.getMax();
        summary.latencyMs.meanSuccessful = snapshot.getMean();
        summary.latencyMs.meanFailed = snapshot.getMeanFailed();
        summary.latencyMs.meanTotal = snapshot.getMeanTotal();
        // Backward compatibility: existing consumers may still read latencyMs.mean.
        summary.latencyMs.mean = summary.latencyMs.meanTotal;
        
        // Error breakdown
        summary.errorsByType = snapshot.getErrorsByType();
        summary.firstErrorMessageByType = snapshot.getFirstErrorMessageByType();
        
        // System metrics
        if (snapshot.getAppCpuMedian() != null) {
            summary.appCpuMedian = snapshot.getAppCpuMedian();
        }
        if (snapshot.getAppRssMedian() != null) {
            summary.appRssMedian = snapshot.getAppRssMedian();
        }
        if (snapshot.getGcPauseMsTotal() != null) {
            summary.gcPauseMsTotal = snapshot.getGcPauseMsTotal();
        }
        if (snapshot.getDbActiveConnectionsMedian() != null) {
            summary.dbActiveConnectionsMedian = snapshot.getDbActiveConnectionsMedian();
        }
        if (snapshot.getQueueDepthMax() != null) {
            summary.queueDepthMax = snapshot.getQueueDepthMax();
        }

        // Per-class metrics section (backward-compatible new section)
        summary.workloadClassMetrics = buildWorkloadClassMetrics(snapshot);
        
        return summary;
    }

    /**
     * Build the {@code workloadClassMetrics} map containing TOTAL, OLTP, and OLAP entries.
     * TOTAL is derived from the top-level snapshot so it always matches the existing fields.
     */
    private static Map<String, WorkloadClassData> buildWorkloadClassMetrics(MetricsSnapshot snapshot) {
        Map<String, WorkloadClassData> result = new LinkedHashMap<>();

        // TOTAL — derived from the top-level snapshot (always present, matches existing fields)
        result.put("TOTAL", buildClassData(snapshot));

        // OLTP and OLAP — derived from per-class sub-snapshots
        for (WorkloadClass wc : new WorkloadClass[]{WorkloadClass.OLTP, WorkloadClass.OLAP}) {
            MetricsSnapshot cs = snapshot.getClassSnapshot(wc);
            result.put(wc.name(), cs != null ? buildClassData(cs) : new WorkloadClassData());
        }

        return result;
    }

    private static WorkloadClassData buildClassData(MetricsSnapshot s) {
        WorkloadClassData d = new WorkloadClassData();
        d.attemptedRequests = s.getAttemptedRequests();
        d.successfulRequests = s.getCompletedRequests();
        d.failedRequests = s.getErrors();
        d.completedRequests = s.getCompletedRequests() + s.getErrors();
        double elapsed = (s.getTimestampMs() - s.getStartTimeMs()) / 1000.0;
        if (elapsed > 0) {
            d.successfulThroughput = s.getAchievedThroughput();
            d.errorThroughput = s.getErrorThroughput();
            d.totalThroughput = s.getTotalThroughput();
        }
        d.errorRate = s.getErrorRate();
        d.latency = new WorkloadClassLatency();
        d.latency.p50 = s.getP50();
        d.latency.p95 = s.getP95();
        d.latency.p99 = s.getP99();
        d.latency.p999 = s.getP999();
        d.latency.max = s.getMax();
        d.latency.meanSuccessful = s.getMean();
        d.latency.meanFailed = s.getMeanFailed();
        d.latency.meanTotal = s.getMeanTotal();
        d.errorsByType = s.getErrorsByType();
        d.firstErrorMessageByType = s.getFirstErrorMessageByType();
        return d;
    }
    
    public static class SummaryData {
        public BenchmarkRunInfo runInfo;
        public double attemptedRps;
        public double achievedThroughputRps;
        public double successfulThroughputRps;
        public double errorThroughputRps;
        public double totalThroughputRps;
        public double errorRate;
        public long totalRequests;
        public long successfulRequests;
        public long failedRequests;
        public LatencyMetrics latencyMs;
        public Map<String, Long> errorsByType = new HashMap<>();
        public Map<String, String> firstErrorMessageByType = new HashMap<>();
        
        // Optional system metrics
        public Double appCpuMedian;
        public Double appRssMedian;
        public Long gcPauseMsTotal;
        public Integer dbActiveConnectionsMedian;
        public Integer queueDepthMax;

        /** Per-class metrics: keys are "TOTAL", "OLTP", "OLAP". New backward-compatible section. */
        public Map<String, WorkloadClassData> workloadClassMetrics;
    }
    
    public static class LatencyMetrics {
        public double p50;
        public double p95;
        public double p99;
        public double p999;
        public double max;
        public double mean;
        public double meanSuccessful;
        public double meanFailed;
        public double meanTotal;
    }
    
    public static class BenchmarkRunInfo {
        public String sut;
        public String workload;
        public String loadMode;
        public int targetRps;
        public int concurrency;
        public int poolSize;
        public int instanceId;
        public int totalInstances;
        public int configuredDbConnectionBudget;
        public int configuredReplicas;
        public long seed;
        public int durationSeconds;
        public String timestamp;
        
        // Replica barrier coordination
        public Long barrierStartEpochMillis;
        
        // Open-loop specific metrics
        public Long openLoopAttemptedOps;
        public Long openLoopMissedOpportunities;
        public Double openLoopSchedulingDelayMs;
        
        // OJP-specific fields
        public String clientPooling;  // "none" for OJP, "hikari" for others
        public String ojpVirtualConnectionMode;
        public String ojpPoolSharing;
        public java.util.Map<String, String> ojpPropertiesUsed;
        public Long clientVirtualConnectionsOpenedTotal;
        public Integer clientVirtualConnectionsMaxConcurrent;
    }

    /** Per-class metrics entry inside {@code workloadClassMetrics}. */
    public static class WorkloadClassData {
        public long attemptedRequests;
        public long successfulRequests;
        public long failedRequests;
        public long completedRequests;
        public double successfulThroughput;
        public double errorThroughput;
        public double totalThroughput;
        public double errorRate;
        public WorkloadClassLatency latency = new WorkloadClassLatency();
        public Map<String, Long> errorsByType = new HashMap<>();
        public Map<String, String> firstErrorMessageByType = new HashMap<>();
    }

    /** Latency sub-object inside {@link WorkloadClassData}. */
    public static class WorkloadClassLatency {
        public double p50;
        public double p95;
        public double p99;
        public double p999;
        public double max;
        public double meanSuccessful;
        public double meanFailed;
        public double meanTotal;
    }
}
