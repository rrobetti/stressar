package com.bench.metrics;

import com.bench.workloads.WorkloadClass;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects metrics for a benchmark run.
 * Thread-safe for concurrent updates.
 * <p>
 * Metrics are tracked at two levels:
 * <ol>
 *   <li><b>TOTAL</b> — the existing global histogram/counter set, unchanged.
 *       Every operation is recorded here regardless of its class.</li>
 *   <li><b>Per-class (OLTP / OLAP)</b> — separate counters and histograms
 *       populated when the caller supplies a {@link WorkloadClass}. Used by
 *       W5_HTAP to separate mixed and analytical traffic.</li>
 * </ol>
 */
public class MetricsCollector {
    private final LatencyRecorder latencyRecorder;
    private final AtomicLong attemptedRequests;
    private final AtomicLong completedRequests;
    private final AtomicLong errors;
    private final AtomicLong failedLatencyNanos;
    private final AtomicLong totalLatencyNanos;
    private final Map<String, AtomicLong> errorsByType;
    /** Captures the first error message seen for each error type, for diagnostics. */
    private final Map<String, String> firstErrorMessageByType;

    /** Per-class metric buckets (OLTP and OLAP; TOTAL is the global set above). */
    private final EnumMap<WorkloadClass, ClassMetrics> classMetrics;

    private final long startTimeMs;
    
    public MetricsCollector() {
        this.latencyRecorder = new LatencyRecorder(600000, 3); // Track up to 600s (10 min) latencies
        this.attemptedRequests = new AtomicLong(0);
        this.completedRequests = new AtomicLong(0);
        this.errors = new AtomicLong(0);
        this.failedLatencyNanos = new AtomicLong(0);
        this.totalLatencyNanos = new AtomicLong(0);
        this.errorsByType = new HashMap<>();
        this.firstErrorMessageByType = new ConcurrentHashMap<>();
        this.startTimeMs = System.currentTimeMillis();

        this.classMetrics = new EnumMap<>(WorkloadClass.class);
        this.classMetrics.put(WorkloadClass.OLTP, new ClassMetrics());
        this.classMetrics.put(WorkloadClass.OLAP, new ClassMetrics());
    }
    
    /**
     * Record a request attempt (TOTAL only; class is not yet known).
     */
    public void recordAttempt() {
        attemptedRequests.incrementAndGet();
    }
    
    /**
     * Record a completed request with latency (TOTAL only).
     * @param latencyNanos Latency in nanoseconds
     */
    public void recordSuccess(long latencyNanos) {
        completedRequests.incrementAndGet();
        latencyRecorder.recordNanos(latencyNanos);
        totalLatencyNanos.addAndGet(latencyNanos);
    }

    /**
     * Record a completed request with latency for the given workload class.
     * Always increments TOTAL metrics; also increments the specified class bucket.
     * The class-level attempted counter is incremented here because the class is
     * only known after execution.
     *
     * @param wc           Workload class of this operation (OLTP or OLAP)
     * @param latencyNanos Latency in nanoseconds
     */
    public void recordSuccess(WorkloadClass wc, long latencyNanos) {
        recordSuccess(latencyNanos);
        if (wc != null && wc != WorkloadClass.TOTAL) {
            ClassMetrics cm = classMetrics.get(wc);
            cm.attemptedRequests.incrementAndGet();
            cm.completedRequests.incrementAndGet();
            cm.latencyRecorder.recordNanos(latencyNanos);
            cm.totalLatencyNanos.addAndGet(latencyNanos);
        }
    }
    
    /**
     * Record an error.
     * @param errorType Type of error (e.g., "timeout", "sql_exception", "rejected")
     */
    public void recordError(String errorType) {
        recordError(errorType, null);
    }

    /**
     * Record an error with a diagnostic message.
     * The first message seen for each error type is retained for inclusion in the summary.
     * @param errorType Type of error (e.g., "timeout", "sql_exception", "rejected")
     * @param message   Error message from the exception, or null
     */
    public void recordError(String errorType, String message) {
        recordError(errorType, message, -1);
    }

    /**
     * Record an error with a diagnostic message and optional latency (TOTAL only).
     * @param errorType Type of error (e.g., "timeout", "sql_exception", "rejected")
     * @param message   Error message from the exception, or null
     * @param latencyNanos Elapsed request latency in nanoseconds, or -1 if unavailable
     */
    public void recordError(String errorType, String message, long latencyNanos) {
        errors.incrementAndGet();
        errorsByType.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
        if (message != null && !message.isBlank()) {
            firstErrorMessageByType.putIfAbsent(errorType, message);
        }
        if (latencyNanos >= 0) {
            failedLatencyNanos.addAndGet(latencyNanos);
            totalLatencyNanos.addAndGet(latencyNanos);
        }
    }

    /**
     * Record an error for the given workload class.
     * Always increments TOTAL metrics; also increments the specified class bucket.
     *
     * @param wc           Workload class of this operation (OLTP or OLAP)
     * @param errorType    Type of error
     * @param message      Error message, or null
     * @param latencyNanos Elapsed request latency in nanoseconds, or -1 if unavailable
     */
    public void recordError(WorkloadClass wc, String errorType, String message, long latencyNanos) {
        recordError(errorType, message, latencyNanos);
        if (wc != null && wc != WorkloadClass.TOTAL) {
            ClassMetrics cm = classMetrics.get(wc);
            cm.attemptedRequests.incrementAndGet();
            cm.errors.incrementAndGet();
            cm.errorsByType.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
            if (message != null && !message.isBlank()) {
                cm.firstErrorMessageByType.putIfAbsent(errorType, message);
            }
            if (latencyNanos >= 0) {
                cm.failedLatencyNanos.addAndGet(latencyNanos);
                cm.totalLatencyNanos.addAndGet(latencyNanos);
            }
        }
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

        // First error messages per type
        snapshot.setFirstErrorMessageByType(new HashMap<>(firstErrorMessageByType));
        
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
        long errorCount = errors.get();
        if (errorCount > 0) {
            snapshot.setMeanFailed((failedLatencyNanos.get() / 1_000_000.0) / errorCount);
        }
        long totalCount = completedRequests.get() + errorCount;
        if (totalCount > 0) {
            snapshot.setMeanTotal((totalLatencyNanos.get() / 1_000_000.0) / totalCount);
        }

        // Per-class snapshots
        for (WorkloadClass wc : new WorkloadClass[]{WorkloadClass.OLTP, WorkloadClass.OLAP}) {
            ClassMetrics cm = classMetrics.get(wc);
            MetricsSnapshot cs = buildClassSnapshot(cm, startTimeMs);
            snapshot.setClassSnapshot(wc, cs);
        }

        return snapshot;
    }

    private static MetricsSnapshot buildClassSnapshot(ClassMetrics cm, long startTimeMs) {
        MetricsSnapshot s = new MetricsSnapshot();
        s.setStartTimeMs(startTimeMs);
        s.setTimestampMs(System.currentTimeMillis());
        s.setAttemptedRequests(cm.attemptedRequests.get());
        s.setCompletedRequests(cm.completedRequests.get());
        s.setErrors(cm.errors.get());

        Map<String, Long> eb = new HashMap<>();
        cm.errorsByType.forEach((k, v) -> eb.put(k, v.get()));
        s.setErrorsByType(eb);
        s.setFirstErrorMessageByType(new HashMap<>(cm.firstErrorMessageByType));

        long count = cm.latencyRecorder.getCount();
        if (count > 0) {
            s.setP50(cm.latencyRecorder.getPercentile(50.0));
            s.setP95(cm.latencyRecorder.getPercentile(95.0));
            s.setP99(cm.latencyRecorder.getPercentile(99.0));
            s.setP999(cm.latencyRecorder.getPercentile(99.9));
            s.setMax(cm.latencyRecorder.getMax());
            s.setMean(cm.latencyRecorder.getMean());
        }
        long errorCount = cm.errors.get();
        if (errorCount > 0) {
            s.setMeanFailed((cm.failedLatencyNanos.get() / 1_000_000.0) / errorCount);
        }
        long totalCount = cm.completedRequests.get() + errorCount;
        if (totalCount > 0) {
            s.setMeanTotal((cm.totalLatencyNanos.get() / 1_000_000.0) / totalCount);
        }
        return s;
    }
    
    /**
     * Reset all metrics.
     */
    public void reset() {
        attemptedRequests.set(0);
        completedRequests.set(0);
        errors.set(0);
        failedLatencyNanos.set(0);
        totalLatencyNanos.set(0);
        errorsByType.clear();
        firstErrorMessageByType.clear();
        latencyRecorder.reset();
        for (ClassMetrics cm : classMetrics.values()) {
            cm.reset();
        }
    }
    
    /**
     * Get the latency recorder for advanced operations.
     */
    public LatencyRecorder getLatencyRecorder() {
        return latencyRecorder;
    }

    /**
     * Get the latency recorder for a specific workload class.
     *
     * @param wc OLTP or OLAP; returns {@code null} for TOTAL or unknown values
     */
    public LatencyRecorder getLatencyRecorder(WorkloadClass wc) {
        if (wc == null || wc == WorkloadClass.TOTAL) {
            return null;
        }
        ClassMetrics cm = classMetrics.get(wc);
        return cm != null ? cm.latencyRecorder : null;
    }
    
    public long getStartTimeMs() {
        return startTimeMs;
    }

    // -------------------------------------------------------------------------
    // Inner type: per-class metric bucket
    // -------------------------------------------------------------------------

    /** Holds all metric counters / histograms for a single {@link WorkloadClass}. */
    static final class ClassMetrics {
        final LatencyRecorder latencyRecorder = new LatencyRecorder(600000, 3);
        final AtomicLong attemptedRequests = new AtomicLong(0);
        final AtomicLong completedRequests = new AtomicLong(0);
        final AtomicLong errors = new AtomicLong(0);
        final AtomicLong failedLatencyNanos = new AtomicLong(0);
        final AtomicLong totalLatencyNanos = new AtomicLong(0);
        final ConcurrentHashMap<String, AtomicLong> errorsByType = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> firstErrorMessageByType = new ConcurrentHashMap<>();

        void reset() {
            attemptedRequests.set(0);
            completedRequests.set(0);
            errors.set(0);
            failedLatencyNanos.set(0);
            totalLatencyNanos.set(0);
            errorsByType.clear();
            firstErrorMessageByType.clear();
            latencyRecorder.reset();
        }
    }
}
