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
    /**
     * Hard upper bound on auto-sized worker threads, to protect the bench JVM from
     * pathological sizing (e.g. high RPS × long timeout) without silently capping
     * an explicit override.
     */
    static final int AUTO_SIZE_CAP = 2000;

    /**
     * Lower bound on auto-sized worker threads.
     */
    static final int AUTO_SIZE_MIN = 4;

    /**
     * Multiplicative headroom applied to the Little's-Law floor, so that brief
     * latency spikes do not immediately backlog the dispatcher.
     */
    static final double AUTO_SIZE_HEADROOM = 1.10;

    private final int targetRps;
    private final int numWorkers;
    private final long shutdownAwaitMs;
    private ExecutorService workers;
    private Thread dispatcherThread;
    
    // Metrics for open-loop correctness
    private final AtomicLong schedulingDelaysNanos = new AtomicLong(0);
    private final AtomicLong missedOpportunities = new AtomicLong(0);
    private final AtomicLong attemptedOps = new AtomicLong(0);
    
    /**
     * Create a true open-loop load generator with an explicit worker-pool size and
     * shutdown drain budget. Callers (see {@link #autoSizeWorkers(int, int, int)})
     * are expected to derive {@code numWorkers} from Little's Law over the
     * connection-acquisition timeout, so that the load generator can absorb the
     * worst latency the SUT is permitted to produce without silently queuing.
     *
     * @param workload The workload to execute
     * @param metrics Cumulative metrics collector
     * @param intervalMetrics Interval metrics collector
     * @param targetRps Target requests per second
     * @param numWorkers Worker pool size (must be &ge; 1)
     * @param shutdownAwaitMs Time to wait for in-flight ops to drain on stop, in ms
     */
    public TrueOpenLoopLoadGenerator(Workload workload, MetricsCollector metrics,
                                     MetricsCollector intervalMetrics, int targetRps,
                                     int numWorkers, long shutdownAwaitMs) {
        super(workload, metrics, intervalMetrics);
        if (numWorkers < 1) {
            throw new IllegalArgumentException("numWorkers must be >= 1, got " + numWorkers);
        }
        if (shutdownAwaitMs < 0) {
            throw new IllegalArgumentException("shutdownAwaitMs must be >= 0, got " + shutdownAwaitMs);
        }
        this.targetRps = targetRps;
        this.numWorkers = numWorkers;
        this.shutdownAwaitMs = shutdownAwaitMs;
    }

    /**
     * Backward-compatible constructor that auto-sizes the worker pool from
     * {@code targetRps} alone, assuming the default 30 s connection-acquisition
     * timeout and a 35 s shutdown drain budget.
     */
    public TrueOpenLoopLoadGenerator(Workload workload, MetricsCollector metrics,
                                     MetricsCollector intervalMetrics, int targetRps) {
        this(workload, metrics, intervalMetrics, targetRps,
                autoSizeWorkers(targetRps, 30_000, 0),
                30_000L + 5_000L);
    }

    /**
     * Compute the worker-pool size from Little's Law: at steady state, the
     * maximum number of in-flight requests the SUT is allowed to hold is
     * {@code targetRps × (connectionTimeoutMs/1000)}, because the connection
     * acquisition timeout is the contract for "no request may take longer than
     * this before failing". A small multiplicative headroom is applied to
     * tolerate brief latency spikes without immediately backlogging the
     * dispatcher, and the result is clamped to a sane range to protect the
     * bench JVM. An explicit {@code overrideMaxConcurrency} (&gt; 0) bypasses
     * the formula and the upper clamp (it is still floored at {@link #AUTO_SIZE_MIN}).
     *
     * @param targetRps Target requests per second
     * @param connectionTimeoutMs Connection-acquisition timeout in milliseconds
     *                            (the per-request latency ceiling)
     * @param overrideMaxConcurrency Optional explicit override; &le; 0 means auto
     * @return Number of worker threads to allocate
     */
    public static int autoSizeWorkers(int targetRps, int connectionTimeoutMs,
                                       int overrideMaxConcurrency) {
        if (overrideMaxConcurrency > 0) {
            return Math.max(AUTO_SIZE_MIN, overrideMaxConcurrency);
        }
        if (targetRps <= 0 || connectionTimeoutMs <= 0) {
            return AUTO_SIZE_MIN;
        }
        // Little's Law: in-flight = arrival_rate × max_residence_time
        double floor = (double) targetRps * (connectionTimeoutMs / 1000.0);
        long sized = (long) Math.ceil(floor * AUTO_SIZE_HEADROOM);
        if (sized < AUTO_SIZE_MIN) {
            return AUTO_SIZE_MIN;
        }
        if (sized > AUTO_SIZE_CAP) {
            return AUTO_SIZE_CAP;
        }
        return (int) sized;
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

            submitOperationToPool();
        }
        
        logger.info("Dispatcher stopped. Total attempted ops: {}, missed opportunities: {}, " +
                   "total scheduling delay: {} ms", 
                   attemptedOps.get(), missedOpportunities.get(), 
                   schedulingDelaysNanos.get() / 1_000_000);
    }

    private void submitOperationToPool() {
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
            if (!workers.awaitTermination(shutdownAwaitMs, TimeUnit.MILLISECONDS)) {
                logger.warn("Worker pool did not drain within {} ms; forcing shutdown", shutdownAwaitMs);
                workers.shutdownNow();
            }
        }
        
        running.set(false);
        logger.info("True open-loop load generator stopped");
    }
    
    /**
     * Reset the open-loop diagnostic counters (attempted ops, missed opportunities,
     * scheduling delay) so that post-warmup reporting reflects only the steady-state
     * window.  Call this immediately after the warmup period ends and before the
     * steady-state measurement begins.
     */
    public void resetAtWarmupEnd() {
        attemptedOps.set(0);
        missedOpportunities.set(0);
        schedulingDelaysNanos.set(0);
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
