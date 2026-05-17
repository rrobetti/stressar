package com.bench.load;

import com.bench.metrics.MetricsCollector;
import com.bench.workloads.Workload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base for load generators.
 */
public abstract class LoadGenerator {
    protected static final Logger logger = LoggerFactory.getLogger(LoadGenerator.class);

    /** Number of initial errors per exception type to log at WARN before switching to DEBUG. */
    private static final long WARN_ERROR_LIMIT = 3;

    protected final Workload workload;
    protected final MetricsCollector metrics;
    protected final MetricsCollector intervalMetrics;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicBoolean stopping = new AtomicBoolean(false);

    // Counter used to suppress repetitive WARN logging after the first few errors
    private final ConcurrentHashMap<String, AtomicLong> errorWarnCounts = new ConcurrentHashMap<>();
    
    public LoadGenerator(Workload workload, MetricsCollector metrics, MetricsCollector intervalMetrics) {
        this.workload = workload;
        this.metrics = metrics;
        this.intervalMetrics = intervalMetrics;
    }
    
    /**
     * Start the load generator.
     */
    public abstract void start();
    
    /**
     * Stop the load generator gracefully.
     */
    public abstract void stop() throws InterruptedException;
    
    /**
     * Execute a single workload iteration and record metrics.
     * Records into both cumulative and interval metrics collectors.
     */
    protected void executeWorkload() {
        metrics.recordAttempt();
        intervalMetrics.recordAttempt();
        long startNanos = System.nanoTime();
        
        try {
            workload.execute();
            long latencyNanos = System.nanoTime() - startNanos;
            metrics.recordSuccess(latencyNanos);
            intervalMetrics.recordSuccess(latencyNanos);
            
        } catch (Exception e) {
            String errorType = e.getClass().getSimpleName();
            // Anonymous/local classes can have empty simple names; fall back to FQCN.
            if (errorType.isEmpty()) {
                errorType = e.getClass().getName();
            }
            String errorMessage = (e.getMessage() != null) ? e.getMessage() : "";

            metrics.recordError(errorType, errorMessage);
            intervalMetrics.recordError(errorType, errorMessage);

            long n = errorWarnCounts.computeIfAbsent(errorType, ignored -> new AtomicLong(0))
                .incrementAndGet();
            if (n <= WARN_ERROR_LIMIT) {
                logger.warn("Workload exception type={} (occurrence {}): {}", errorType, n, errorMessage);
                logger.debug("Workload exception stack trace type={} (occurrence {})", errorType, n, e);
            } else {
                logger.debug("Workload exception type={}: {}", errorType, errorMessage);
            }
        }
    }
    
    public boolean isRunning() {
        return running.get();
    }
}
