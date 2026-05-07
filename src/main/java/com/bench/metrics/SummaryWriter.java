package com.bench.metrics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
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
        summary.attemptedRps = (double) snapshot.getAttemptedRequests() / 
            ((snapshot.getTimestampMs() - snapshot.getStartTimeMs()) / 1000.0);
        summary.achievedThroughputRps = snapshot.getAchievedThroughput();
        summary.errorRate = snapshot.getErrorRate();
        summary.totalRequests = snapshot.getCompletedRequests() + snapshot.getErrors();
        summary.failedRequests = snapshot.getErrors();
        
        // Latency metrics
        summary.latencyMs = new LatencyMetrics();
        summary.latencyMs.p50 = snapshot.getP50();
        summary.latencyMs.p95 = snapshot.getP95();
        summary.latencyMs.p99 = snapshot.getP99();
        summary.latencyMs.p999 = snapshot.getP999();
        summary.latencyMs.max = snapshot.getMax();
        summary.latencyMs.mean = snapshot.getMean();
        
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
        
        return summary;
    }
    
    public static class SummaryData {
        public BenchmarkRunInfo runInfo;
        public double attemptedRps;
        public double achievedThroughputRps;
        public double errorRate;
        public long totalRequests;
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
    }
    
    public static class LatencyMetrics {
        public double p50;
        public double p95;
        public double p99;
        public double p999;
        public double max;
        public double mean;
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
}
