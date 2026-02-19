package com.bench.load;

import com.bench.metrics.MetricsCollector;
import com.bench.workloads.Workload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base for load generators.
 */
public abstract class LoadGenerator {
    protected static final Logger logger = LoggerFactory.getLogger(LoadGenerator.class);
    
    protected final Workload workload;
    protected final MetricsCollector metrics;
    protected final MetricsCollector intervalMetrics;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicBoolean stopping = new AtomicBoolean(false);
    
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
            logger.debug("Request timeout: {}", e.getMessage());
            
        } catch (java.sql.SQLException e) {
            metrics.recordError("sql_exception");
            intervalMetrics.recordError("sql_exception");
            logger.debug("SQL exception: {}", e.getMessage());
            
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
