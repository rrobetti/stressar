package com.bench.load;

import com.bench.metrics.MetricsCollector;
import com.bench.workloads.Workload;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Open-loop load generator using scheduled executor.
 * Generates requests at a target arrival rate independent of response times.
 * This avoids closed-loop bias where slow responses reduce offered load.
 */
public class OpenLoopLoadGenerator extends LoadGenerator {
    private final int targetRps;
    private final int numThreads;
    private ScheduledExecutorService scheduler;
    private final AtomicLong nextTaskTime = new AtomicLong(0);
    
    /**
     * Create an open-loop load generator.
     * @param workload The workload to execute
     * @param metrics Metrics collector
     * @param targetRps Target requests per second
     */
    public OpenLoopLoadGenerator(Workload workload, MetricsCollector metrics, int targetRps) {
        super(workload, metrics);
        this.targetRps = targetRps;
        // Use enough threads to handle the target rate with some headroom
        this.numThreads = Math.max(4, Math.min(targetRps / 10, 200));
    }
    
    @Override
    public void start() {
        if (running.getAndSet(true)) {
            logger.warn("Load generator already running");
            return;
        }
        
        stopping.set(false);
        scheduler = Executors.newScheduledThreadPool(numThreads);
        
        logger.info("Starting open-loop load generator: target={} rps, threads={}", 
                   targetRps, numThreads);
        
        // Calculate interval between requests in nanoseconds
        long intervalNanos = 1_000_000_000L / targetRps;
        nextTaskTime.set(System.nanoTime());
        
        // Schedule tasks at the target rate
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            scheduler.scheduleAtFixedRate(() -> {
                if (stopping.get()) {
                    return;
                }
                
                // Execute workload
                try {
                    executeWorkload();
                } catch (Exception e) {
                    logger.error("Error in thread {}", threadId, e);
                }
                
            }, 0, intervalNanos * numThreads, TimeUnit.NANOSECONDS);
        }
    }
    
    @Override
    public void stop() throws InterruptedException {
        if (!running.get()) {
            return;
        }
        
        logger.info("Stopping open-loop load generator");
        stopping.set(true);
        
        if (scheduler != null) {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        }
        
        running.set(false);
        logger.info("Open-loop load generator stopped");
    }
}
