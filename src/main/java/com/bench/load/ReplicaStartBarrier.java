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
 * <p>
 * All replicas register themselves and wait for a start signal.
 * Once all expected replicas are registered, the coordinator writes a start time.
 * All replicas wait for the start time and begin execution simultaneously.
 */
public class ReplicaStartBarrier {
    private static final Logger logger = LoggerFactory.getLogger(ReplicaStartBarrier.class);
    private static final String REPLICA_FILE_PREFIX = "replica_";
    private static final String START_TIME = "start_time";
    
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
        try (var stream = Files.list(barrierDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(REPLICA_FILE_PREFIX) ||
                               p.getFileName().toString().equals(START_TIME))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        logger.warn("Failed to delete old barrier file: {}", p, e);
                    }
                });
        }
        
        logger.info("Barrier initialized for {} replicas", expectedReplicas);
    }
    
    /**
     * Register this replica and wait for all replicas to be ready.
     * Returns the coordinated start time (epoch milliseconds).
     */
    public long awaitStart() throws IOException, InterruptedException {
        logger.info("Instance {} registering with barrier", instanceId);
        
        // Register this instance
        Path replicaFile = barrierDir.resolve(REPLICA_FILE_PREFIX + instanceId);
        Files.writeString(replicaFile, String.valueOf(System.currentTimeMillis()),
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        logger.info("Instance {} registered, waiting for start signal", instanceId);
        
        // Wait for start signal
        Path startFile = barrierDir.resolve(START_TIME);
        long deadline = System.currentTimeMillis() + timeoutMs;
        
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(startFile)) {
                String content = Files.readString(startFile).trim();
                long startEpochMillis = Long.parseLong(content);
                
                logger.info("Instance {} received start signal: epoch {} ms", instanceId, startEpochMillis);
                
                // Wait until the start time
                long nowMillis = System.currentTimeMillis();
                if (nowMillis < startEpochMillis) {
                    long waitMillis = startEpochMillis - nowMillis;
                    logger.info("Instance {} waiting {} ms until start", instanceId, waitMillis);
                    Thread.sleep(waitMillis);
                }
                
                logger.info("Instance {} starting execution", instanceId);
                return startEpochMillis;
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
        long registeredCount;
        try (var stream = Files.list(barrierDir)) {
            registeredCount = stream
                .filter(p -> p.getFileName().toString().startsWith(REPLICA_FILE_PREFIX))
                .count();
        }
        
        logger.debug("Registered replicas: {} / {}", registeredCount, expectedReplicas);
        return registeredCount >= expectedReplicas;
    }
    
    /**
     * Write the start signal (called by coordinator).
     * All replicas will start at startEpochMillis (System.currentTimeMillis() basis).
     */
    public void signalStart(long startEpochMillis) throws IOException {
        Path startFile = barrierDir.resolve(START_TIME);
        Files.writeString(startFile, String.valueOf(startEpochMillis),
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Start signal written: epoch {} ms", startEpochMillis);
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
                
                // Schedule start time in the future (epoch-based)
                long startEpochMillis = System.currentTimeMillis() + delayMs;
                signalStart(startEpochMillis);
                
                logger.info("Start coordinated for {} ms from now (epoch: {})", delayMs, startEpochMillis);
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
            try (var stream = Files.list(barrierDir)) {
                stream.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        logger.warn("Failed to delete barrier file: {}", p, e);
                    }
                });
            }
            Files.deleteIfExists(barrierDir);
            logger.info("Barrier cleanup complete");
        } catch (IOException e) {
            logger.warn("Failed to clean up barrier directory", e);
        }
    }
}
