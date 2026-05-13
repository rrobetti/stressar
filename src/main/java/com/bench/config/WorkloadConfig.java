package com.bench.config;

/**
 * Workload configuration.
 */
public class WorkloadConfig {
    private WorkloadType type;
    private int targetRps;  // For open-loop mode
    private int concurrency; // For closed-loop mode
    private boolean openLoop = true;
    private int warmupSeconds = 300;
    private int durationSeconds = 600;
    private int cooldownSeconds = 120;
    private int repeatCount = 5;
    private long seed = 42L;
    
    // Workload-specific parameters
    private double queryAPercent = 0.30;  // For W1
    private double writePercent = 0.20;   // For mixed workloads
    private double slowQueryPercent = 0.10; // For W3
    
    // Parameter distributions
    private boolean useZipf = false;
    private double zipfAlpha = 1.1;
    
    public enum WorkloadType {
        W1_READ_ONLY,
        W2_READ_WRITE,
        W2_MIXED,
        W2_WRITE_ONLY,
        W3_SLOW_QUERY
    }

    public WorkloadType getType() {
        return type;
    }

    public void setType(WorkloadType type) {
        this.type = type;
    }

    public int getTargetRps() {
        return targetRps;
    }

    public void setTargetRps(int targetRps) {
        this.targetRps = targetRps;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public boolean isOpenLoop() {
        return openLoop;
    }

    public void setOpenLoop(boolean openLoop) {
        this.openLoop = openLoop;
    }

    public int getWarmupSeconds() {
        return warmupSeconds;
    }

    public void setWarmupSeconds(int warmupSeconds) {
        this.warmupSeconds = warmupSeconds;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public int getRepeatCount() {
        return repeatCount;
    }

    public void setRepeatCount(int repeatCount) {
        this.repeatCount = repeatCount;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public double getQueryAPercent() {
        return queryAPercent;
    }

    public void setQueryAPercent(double queryAPercent) {
        this.queryAPercent = queryAPercent;
    }

    public double getWritePercent() {
        return writePercent;
    }

    public void setWritePercent(double writePercent) {
        this.writePercent = writePercent;
    }

    public double getSlowQueryPercent() {
        return slowQueryPercent;
    }

    public void setSlowQueryPercent(double slowQueryPercent) {
        this.slowQueryPercent = slowQueryPercent;
    }

    public boolean isUseZipf() {
        return useZipf;
    }

    public void setUseZipf(boolean useZipf) {
        this.useZipf = useZipf;
    }

    public double getZipfAlpha() {
        return zipfAlpha;
    }

    public void setZipfAlpha(double zipfAlpha) {
        this.zipfAlpha = zipfAlpha;
    }
}
