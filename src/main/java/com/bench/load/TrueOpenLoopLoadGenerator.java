package com.bench.load;

import com.bench.metrics.MetricsCollector;
import com.bench.workloads.Workload;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicLong;

/**
 * True open-loop load generator using absolute time-based scheduling.
 * <p>
 * Unlike scheduleAtFixedRate which queues tasks and causes catch-up bursts,
 * this implementation maintains absolute timestamps for each operation and
 * tracks when the system falls behind schedule.
 * <p>
 * Key characteristics:
 * - Single dispatcher thread schedules operations based on absolute time
 * - nextSendTimeNanos += intervalNanos for each operation
 * - If late (now > nextSendTime), records scheduling delay and proceeds immediately
 * - Worker pool executes operations asynchronously
 * - Tracks attempted vs achieved RPS and missed opportunities
 */
public class TrueOpenLoopLoadGenerator extends LoadGenerator {
    private final int targetRps;
    private final int numWorkers;
    private ExecutorService workers;
    private Thread dispatcherThread;
    
    // Metrics for open-loop correctness
    private final AtomicLong schedulingDelaysNanos = new AtomicLong(0);
    private final AtomicLong missedOpportunities = new AtomicLong(0);
    private final AtomicLong attemptedOps = new AtomicLong(0);
    
    /**
     * Create a true open-loop load generator.
     * @param workload The workload to execute
     * @param metrics Metrics collector
     * @param targetRps Target requests per second
     */
    public TrueOpenLoopLoadGenerator(Workload workload, MetricsCollector metrics, int targetRps) {
        super(workload, metrics);
        this.targetRps = targetRps;
        // Use enough workers to handle the target rate
        this.numWorkers = Math.max(4, Math.min(targetRps / 5, 200));
    }
    
    @Override
    public void start() {
        if (running.getAndSet(true)) {
            logger.warn("Load generator already running");
            return;
        }
        
        stopping.set(false);
        workers = Executors.newFixedThreadPool(numWorkers);
        
        logger.info("Starting true open-loop load generator: target={} rps, workers={}", 
                   targetRps, numWorkers);
        
        // Start dispatcher thread
        dispatcherThread = new Thread(this::dispatchLoop, "open-loop-dispatcher");
        dispatcherThread.setDaemon(false);
        dispatcherThread.start();
    }
    
    /**
     * Main dispatch loop - maintains absolute time schedule.
     */
    private void dispatchLoop() {
        long intervalNanos = 1_000_000_000L / targetRps;
        long nextSendTimeNanos = System.nanoTime();
        
        logger.info("Dispatcher starting: intervalNanos={}", intervalNanos);
        
        while (!stopping.get()) {
            long nowNanos = System.nanoTime();
            
            // Check if we're late
            if (nowNanos > nextSendTimeNanos) {
                long delayNanos = nowNanos - nextSendTimeNanos;
                schedulingDelaysNanos.addAndGet(delayNanos);
                
                // If we're more than one interval late, we've missed opportunities
                long missedIntervals = delayNanos / intervalNanos;
                if (missedIntervals > 0) {
                    missedOpportunities.addAndGet(missedIntervals);
                    logger.debug("Missed {} operation opportunities (system overloaded)", missedIntervals);
                }
                
                // Proceed immediately without catch-up burst
                nextSendTimeNanos = nowNanos + intervalNanos;
            } else {
                // Wait until nextSendTimeNanos
                long waitNanos = nextSendTimeNanos - nowNanos;
                if (waitNanos > 10_000) {
                    // For longer waits, use parkNanos (more efficient)
                    LockSupport.parkNanos(waitNanos);
                } else {
                    // For very short waits, busy-wait for precision
                    while (System.nanoTime() < nextSendTimeNanos && !stopping.get()) {
                        Thread.onSpinWait();
                    }
                }
                
                // Advance to next scheduled time
                nextSendTimeNanos += intervalNanos;
            }
            
            // Submit operation to worker pool (if not stopping)
            if (!stopping.get()) {
                attemptedOps.incrementAndGet();
                workers.submit(() -> {
                    try {
                        executeWorkload();
                    } catch (Exception e) {
                        logger.error("Error executing workload", e);
                    }
                });
            }
        }
        
        logger.info("Dispatcher stopped. Total attempted ops: {}, missed opportunities: {}, " +
                   "total scheduling delay: {} ms", 
                   attemptedOps.get(), missedOpportunities.get(), 
                   schedulingDelaysNanos.get() / 1_000_000);
    }
    
    @Override
    public void stop() throws InterruptedException {
        if (!running.get()) {
            return;
        }
        
        logger.info("Stopping true open-loop load generator");
        stopping.set(true);
        
        // Stop dispatcher thread
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
            dispatcherThread.join(5000);
        }
        
        // Shutdown worker pool
        if (workers != null) {
            workers.shutdown();
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        }
        
        running.set(false);
        logger.info("True open-loop load generator stopped");
    }
    
    /**
     * Get total attempted operations.
     */
    public long getAttemptedOps() {
        return attemptedOps.get();
    }
    
    /**
     * Get total missed opportunities (when system fell behind).
     */
    public long getMissedOpportunities() {
        return missedOpportunities.get();
    }
    
    /**
     * Get total scheduling delay in nanoseconds.
     */
    public long getSchedulingDelaysNanos() {
        return schedulingDelaysNanos.get();
    }
}
