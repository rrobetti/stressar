package com.bench.metrics;

import java.util.HashMap;
import java.util.Map;

/**
 * Snapshot of metrics at a point in time.
 */
public class MetricsSnapshot {
    private long startTimeMs;
    private long timestampMs;
    private long attemptedRequests;
    private long completedRequests;
    private long errors;
    private Map<String, Long> errorsByType = new HashMap<>();
    
    // Latency percentiles in milliseconds
    private double p50;
    private double p95;
    private double p99;
    private double p999;
    private double max;
    private double mean;
    
    // Optional system metrics
    private Double appCpuMedian;
    private Double appRssMedian;
    private Long gcPauseMsTotal;
    private Integer dbActiveConnectionsMedian;
    private Integer queueDepthMax;
    
    public double getAchievedThroughput() {
        if (timestampMs <= startTimeMs) {
            return 0.0;
        }
        double elapsedSeconds = (timestampMs - startTimeMs) / 1000.0;
        return completedRequests / elapsedSeconds;
    }
    
    public double getErrorRate() {
        long total = completedRequests + errors;
        if (total == 0) {
            return 0.0;
        }
        return (double) errors / total;
    }

    // Getters and setters
    public long getStartTimeMs() {
        return startTimeMs;
    }

    public void setStartTimeMs(long startTimeMs) {
        this.startTimeMs = startTimeMs;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public long getAttemptedRequests() {
        return attemptedRequests;
    }

    public void setAttemptedRequests(long attemptedRequests) {
        this.attemptedRequests = attemptedRequests;
    }

    public long getCompletedRequests() {
        return completedRequests;
    }

    public void setCompletedRequests(long completedRequests) {
        this.completedRequests = completedRequests;
    }

    public long getErrors() {
        return errors;
    }

    public void setErrors(long errors) {
        this.errors = errors;
    }

    public Map<String, Long> getErrorsByType() {
        return errorsByType;
    }

    public void setErrorsByType(Map<String, Long> errorsByType) {
        this.errorsByType = errorsByType;
    }

    public double getP50() {
        return p50;
    }

    public void setP50(double p50) {
        this.p50 = p50;
    }

    public double getP95() {
        return p95;
    }

    public void setP95(double p95) {
        this.p95 = p95;
    }

    public double getP99() {
        return p99;
    }

    public void setP99(double p99) {
        this.p99 = p99;
    }

    public double getP999() {
        return p999;
    }

    public void setP999(double p999) {
        this.p999 = p999;
    }

    public double getMax() {
        return max;
    }

    public void setMax(double max) {
        this.max = max;
    }

    public double getMean() {
        return mean;
    }

    public void setMean(double mean) {
        this.mean = mean;
    }

    public Double getAppCpuMedian() {
        return appCpuMedian;
    }

    public void setAppCpuMedian(Double appCpuMedian) {
        this.appCpuMedian = appCpuMedian;
    }

    public Double getAppRssMedian() {
        return appRssMedian;
    }

    public void setAppRssMedian(Double appRssMedian) {
        this.appRssMedian = appRssMedian;
    }

    public Long getGcPauseMsTotal() {
        return gcPauseMsTotal;
    }

    public void setGcPauseMsTotal(Long gcPauseMsTotal) {
        this.gcPauseMsTotal = gcPauseMsTotal;
    }

    public Integer getDbActiveConnectionsMedian() {
        return dbActiveConnectionsMedian;
    }

    public void setDbActiveConnectionsMedian(Integer dbActiveConnectionsMedian) {
        this.dbActiveConnectionsMedian = dbActiveConnectionsMedian;
    }

    public Integer getQueueDepthMax() {
        return queueDepthMax;
    }

    public void setQueueDepthMax(Integer queueDepthMax) {
        this.queueDepthMax = queueDepthMax;
    }
}
