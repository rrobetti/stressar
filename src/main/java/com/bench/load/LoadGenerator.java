package com.bench.load;

import com.bench.metrics.MetricsCollector;
import com.bench.workloads.Workload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base for load generators.
 */
public abstract class LoadGenerator {
    protected static final Logger logger = LoggerFactory.getLogger(LoadGenerator.class);

    /** Number of initial SQL/timeout errors to log at WARN before switching to DEBUG. */
    private static final long WARN_ERROR_LIMIT = 3;

    protected final Workload workload;
    protected final MetricsCollector metrics;
    protected final MetricsCollector intervalMetrics;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicBoolean stopping = new AtomicBoolean(false);

    // Counters used to suppress repetitive WARN logging after the first few errors
    private final AtomicLong sqlErrorWarnCount = new AtomicLong(0);
    private final AtomicLong timeoutWarnCount = new AtomicLong(0);
    
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
            
        } catch (java.sql.SQLTimeoutException e) {
            metrics.recordError("timeout");
            intervalMetrics.recordError("timeout");
            long n = timeoutWarnCount.incrementAndGet();
            if (n <= WARN_ERROR_LIMIT) {
                logger.warn("Request timeout (occurrence {}): {}", n, e.getMessage());
            } else {
                logger.debug("Request timeout: {}", e.getMessage());
            }
            
        } catch (java.sql.SQLException e) {
            metrics.recordError("sql_exception");
            intervalMetrics.recordError("sql_exception");
            long n = sqlErrorWarnCount.incrementAndGet();
            if (n <= WARN_ERROR_LIMIT) {
                logger.warn("SQL exception (occurrence {}): {}", n, e.getMessage());
            } else {
                logger.debug("SQL exception: {}", e.getMessage());
            }
            
        } catch (Exception e) {
            metrics.recordError("other");
            intervalMetrics.recordError("other");
            logger.error("Unexpected error executing workload", e);
        }
    }
    
    public boolean isRunning() {
        return running.get();
    }
}
