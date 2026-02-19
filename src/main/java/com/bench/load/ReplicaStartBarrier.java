package com.bench.load;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * File-based barrier for coordinating start time across multiple replicas.
 * 
 * All replicas register themselves and wait for a start signal.
 * Once all expected replicas are registered, the coordinator writes a start time.
 * All replicas wait for the start time and begin execution simultaneously.
 */
public class ReplicaStartBarrier {
    private static final Logger logger = LoggerFactory.getLogger(ReplicaStartBarrier.class);
    
    private final Path barrierDir;
    private final int expectedReplicas;
    private final int instanceId;
    private final long timeoutMs;
    
    /**
     * Create a replica start barrier.
     * 
     * @param barrierDir Directory for barrier files
     * @param expectedReplicas Total number of replicas expected
     * @param instanceId This replica's instance ID
     * @param timeoutMs Maximum time to wait for barrier (milliseconds)
     */
    public ReplicaStartBarrier(String barrierDir, int expectedReplicas, int instanceId, long timeoutMs) {
        this.barrierDir = Paths.get(barrierDir);
        this.expectedReplicas = expectedReplicas;
        this.instanceId = instanceId;
        this.timeoutMs = timeoutMs;
    }
    
    /**
     * Initialize the barrier (called by coordinator).
     * Clears any existing barrier files.
     */
    public void initialize() throws IOException {
        logger.info("Initializing replica start barrier in {}", barrierDir);
        
        // Create barrier directory
        Files.createDirectories(barrierDir);
        
        // Clean up any previous barrier files
        Files.list(barrierDir)
            .filter(p -> p.getFileName().toString().startsWith("replica_") ||
                        p.getFileName().toString().equals("start_time"))
            .forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    logger.warn("Failed to delete old barrier file: {}", p, e);
                }
            });
        
        logger.info("Barrier initialized for {} replicas", expectedReplicas);
    }
    
    /**
     * Register this replica and wait for all replicas to be ready.
     * Returns the coordinated start time (nanoTime).
     */
    public long awaitStart() throws IOException, InterruptedException {
        logger.info("Instance {} registering with barrier", instanceId);
        
        // Register this instance
        Path replicaFile = barrierDir.resolve("replica_" + instanceId);
        Files.writeString(replicaFile, String.valueOf(System.currentTimeMillis()),
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        logger.info("Instance {} registered, waiting for start signal", instanceId);
        
        // Wait for start signal
        Path startFile = barrierDir.resolve("start_time");
        long deadline = System.currentTimeMillis() + timeoutMs;
        
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(startFile)) {
                String content = Files.readString(startFile).trim();
                long startTimeNanos = Long.parseLong(content);
                
                logger.info("Instance {} received start signal: {}", instanceId, startTimeNanos);
                
                // Wait until the start time
                long nowNanos = System.nanoTime();
                if (nowNanos < startTimeNanos) {
                    long waitNanos = startTimeNanos - nowNanos;
                    logger.info("Instance {} waiting {} ms until start", instanceId, waitNanos / 1_000_000);
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                }
                
                logger.info("Instance {} starting execution", instanceId);
                return startTimeNanos;
            }
            
            // Check periodically
            Thread.sleep(100);
        }
        
        throw new IOException("Timeout waiting for start signal after " + timeoutMs + " ms");
    }
    
    /**
     * Check if all replicas are registered.
     * Called by coordinator before writing start signal.
     */
    public boolean areAllReplicasRegistered() throws IOException {
        long registeredCount = Files.list(barrierDir)
            .filter(p -> p.getFileName().toString().startsWith("replica_"))
            .count();
        
        logger.debug("Registered replicas: {} / {}", registeredCount, expectedReplicas);
        return registeredCount >= expectedReplicas;
    }
    
    /**
     * Write the start signal (called by coordinator).
     * All replicas will start at startTimeNanos (System.nanoTime() basis).
     */
    public void signalStart(long startTimeNanos) throws IOException {
        Path startFile = barrierDir.resolve("start_time");
        Files.writeString(startFile, String.valueOf(startTimeNanos),
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Start signal written: {}", startTimeNanos);
    }
    
    /**
     * Wait for all replicas to register, then signal start.
     * Called by coordinator.
     * 
     * @param delayMs Additional delay after all replicas register (milliseconds)
     */
    public void coordinateStart(long delayMs) throws IOException, InterruptedException {
        logger.info("Coordinator waiting for {} replicas to register", expectedReplicas);
        
        long deadline = System.currentTimeMillis() + timeoutMs;
        
        while (System.currentTimeMillis() < deadline) {
            if (areAllReplicasRegistered()) {
                logger.info("All {} replicas registered", expectedReplicas);
                
                // Schedule start time in the future
                long startTimeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
                signalStart(startTimeNanos);
                
                logger.info("Start coordinated for {} ms from now", delayMs);
                return;
            }
            
            Thread.sleep(500);
        }
        
        throw new IOException("Timeout waiting for all replicas to register");
    }
    
    /**
     * Clean up barrier files (called after run completes).
     */
    public void cleanup() {
        try {
            Files.list(barrierDir)
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        logger.warn("Failed to delete barrier file: {}", p, e);
                    }
                });
            Files.deleteIfExists(barrierDir);
            logger.info("Barrier cleanup complete");
        } catch (IOException e) {
            logger.warn("Failed to clean up barrier directory", e);
        }
    }
}
