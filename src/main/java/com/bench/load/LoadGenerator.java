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
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicBoolean stopping = new AtomicBoolean(false);
    
    public LoadGenerator(Workload workload, MetricsCollector metrics) {
        this.workload = workload;
        this.metrics = metrics;
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
     */
    protected void executeWorkload() {
        metrics.recordAttempt();
        long startNanos = System.nanoTime();
        
        try {
            workload.execute();
            long latencyNanos = System.nanoTime() - startNanos;
            metrics.recordSuccess(latencyNanos);
            
        } catch (java.sql.SQLTimeoutException e) {
            metrics.recordError("timeout");
            logger.debug("Request timeout: {}", e.getMessage());
            
        } catch (java.sql.SQLException e) {
            metrics.recordError("sql_exception");
            logger.debug("SQL exception: {}", e.getMessage());
            
        } catch (Exception e) {
            metrics.recordError("other");
            logger.error("Unexpected error executing workload", e);
        }
    }
    
    public boolean isRunning() {
        return running.get();
    }
}
