package com.bench.metrics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects metrics for a benchmark run.
 * Thread-safe for concurrent updates.
 */
public class MetricsCollector {
    private final LatencyRecorder latencyRecorder;
    private final AtomicLong attemptedRequests;
    private final AtomicLong completedRequests;
    private final AtomicLong errors;
    private final Map<String, AtomicLong> errorsByType;
    
    private final long startTimeMs;
    
    public MetricsCollector() {
        this.latencyRecorder = new LatencyRecorder(60000, 3); // Track up to 60s latencies
        this.attemptedRequests = new AtomicLong(0);
        this.completedRequests = new AtomicLong(0);
        this.errors = new AtomicLong(0);
        this.errorsByType = new HashMap<>();
        this.startTimeMs = System.currentTimeMillis();
    }
    
    /**
     * Record a request attempt.
     */
    public void recordAttempt() {
        attemptedRequests.incrementAndGet();
    }
    
    /**
     * Record a completed request with latency.
     * @param latencyNanos Latency in nanoseconds
     */
    public void recordSuccess(long latencyNanos) {
        completedRequests.incrementAndGet();
        latencyRecorder.recordNanos(latencyNanos);
    }
    
    /**
     * Record an error.
     * @param errorType Type of error (e.g., "timeout", "sql_exception", "rejected")
     */
    public void recordError(String errorType) {
        errors.incrementAndGet();
        errorsByType.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * Get snapshot of current metrics.
     */
    public MetricsSnapshot getSnapshot() {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setStartTimeMs(startTimeMs);
        snapshot.setTimestampMs(System.currentTimeMillis());
        snapshot.setAttemptedRequests(attemptedRequests.get());
        snapshot.setCompletedRequests(completedRequests.get());
        snapshot.setErrors(errors.get());
        
        // Copy error breakdown
        Map<String, Long> errorBreakdown = new HashMap<>();
        errorsByType.forEach((key, value) -> errorBreakdown.put(key, value.get()));
        snapshot.setErrorsByType(errorBreakdown);
        
        // Latency percentiles
        long count = latencyRecorder.getCount();
        if (count > 0) {
            snapshot.setP50(latencyRecorder.getPercentile(50.0));
            snapshot.setP95(latencyRecorder.getPercentile(95.0));
            snapshot.setP99(latencyRecorder.getPercentile(99.0));
            snapshot.setP999(latencyRecorder.getPercentile(99.9));
            snapshot.setMax(latencyRecorder.getMax());
            snapshot.setMean(latencyRecorder.getMean());
        }
        
        return snapshot;
    }
    
    /**
     * Reset all metrics.
     */
    public void reset() {
        attemptedRequests.set(0);
        completedRequests.set(0);
        errors.set(0);
        errorsByType.clear();
        latencyRecorder.reset();
    }
    
    /**
     * Get the latency recorder for advanced operations.
     */
    public LatencyRecorder getLatencyRecorder() {
        return latencyRecorder;
    }
    
    public long getStartTimeMs() {
        return startTimeMs;
    }
}
