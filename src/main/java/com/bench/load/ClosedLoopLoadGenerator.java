package com.bench.load;

import com.bench.metrics.MetricsCollector;
import com.bench.workloads.Workload;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Closed-loop load generator with fixed concurrency.
 * Each worker issues the next request immediately after receiving a response.
 */
public class ClosedLoopLoadGenerator extends LoadGenerator {
    private final int concurrency;
    private ExecutorService executor;
    private final List<Thread> workers = new ArrayList<>();
    
    /**
     * Create a closed-loop load generator.
     * @param workload The workload to execute
     * @param metrics Cumulative metrics collector
     * @param intervalMetrics Interval metrics collector
     * @param concurrency Number of concurrent workers
     */
    public ClosedLoopLoadGenerator(Workload workload, MetricsCollector metrics, 
                                   MetricsCollector intervalMetrics, int concurrency) {
        super(workload, metrics, intervalMetrics);
        this.concurrency = concurrency;
    }
    
    @Override
    public void start() {
        if (running.getAndSet(true)) {
            logger.warn("Load generator already running");
            return;
        }
        
        stopping.set(false);
        executor = Executors.newFixedThreadPool(concurrency);
        
        logger.info("Starting closed-loop load generator: concurrency={}", concurrency);
        
        // Start worker threads
        for (int i = 0; i < concurrency; i++) {
            final int workerId = i;
            executor.submit(() -> workerLoop(workerId));
        }
    }
    
    private void workerLoop(int workerId) {
        logger.debug("Worker {} started", workerId);
        
        while (!stopping.get() && running.get()) {
            try {
                executeWorkload();
            } catch (Exception e) {
                logger.error("Error in worker {}", workerId, e);
            }
        }
        
        logger.debug("Worker {} stopped", workerId);
    }
    
    @Override
    public void stop() throws InterruptedException {
        if (!running.get()) {
            return;
        }
        
        logger.info("Stopping closed-loop load generator");
        stopping.set(true);
        
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
        
        running.set(false);
        logger.info("Closed-loop load generator stopped");
    }
}
