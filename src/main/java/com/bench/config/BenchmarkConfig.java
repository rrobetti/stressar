package com.bench.config;

/**
 * Main benchmark configuration.
 */
public class BenchmarkConfig {
    private DatabaseConfig database;
    private ConnectionMode connectionMode = ConnectionMode.HIKARI_DIRECT;
    private WorkloadConfig workload;
    
    // Connection pool settings
    private int poolSize = 20;
    private int connectionTimeout = 30000;
    private int maxLifetime = 1800000;
    
    // Disciplined pooling settings
    private int dbConnectionBudget = 100;
    private int replicas = 1;
    private int maxPoolSizePerReplica = 50;
    
    // Multi-instance settings
    private int instanceId = 0;
    private String outputDir = "results";
    
    // Metrics settings
    private int metricsIntervalSeconds = 1;
    
    // Sweep settings
    private double sweepIncrementPercent = 15.0;
    private int sloP95Ms = 50;
    private double errorRateThreshold = 0.001;
    
    // Data generation settings
    private int numAccounts = 10000;
    private int numItems = 5000;
    private int numOrders = 50000;

    public BenchmarkConfig() {
        this.database = new DatabaseConfig();
        this.workload = new WorkloadConfig();
    }

    public DatabaseConfig getDatabase() {
        return database;
    }

    public void setDatabase(DatabaseConfig database) {
        this.database = database;
    }

    public ConnectionMode getConnectionMode() {
        return connectionMode;
    }

    public void setConnectionMode(ConnectionMode connectionMode) {
        this.connectionMode = connectionMode;
    }

    public WorkloadConfig getWorkload() {
        return workload;
    }

    public void setWorkload(WorkloadConfig workload) {
        this.workload = workload;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getMaxLifetime() {
        return maxLifetime;
    }

    public void setMaxLifetime(int maxLifetime) {
        this.maxLifetime = maxLifetime;
    }

    public int getDbConnectionBudget() {
        return dbConnectionBudget;
    }

    public void setDbConnectionBudget(int dbConnectionBudget) {
        this.dbConnectionBudget = dbConnectionBudget;
    }

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }

    public int getMaxPoolSizePerReplica() {
        return maxPoolSizePerReplica;
    }

    public void setMaxPoolSizePerReplica(int maxPoolSizePerReplica) {
        this.maxPoolSizePerReplica = maxPoolSizePerReplica;
    }

    public int getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(int instanceId) {
        this.instanceId = instanceId;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public int getMetricsIntervalSeconds() {
        return metricsIntervalSeconds;
    }

    public void setMetricsIntervalSeconds(int metricsIntervalSeconds) {
        this.metricsIntervalSeconds = metricsIntervalSeconds;
    }

    public double getSweepIncrementPercent() {
        return sweepIncrementPercent;
    }

    public void setSweepIncrementPercent(double sweepIncrementPercent) {
        this.sweepIncrementPercent = sweepIncrementPercent;
    }

    public int getSloP95Ms() {
        return sloP95Ms;
    }

    public void setSloP95Ms(int sloP95Ms) {
        this.sloP95Ms = sloP95Ms;
    }

    public double getErrorRateThreshold() {
        return errorRateThreshold;
    }

    public void setErrorRateThreshold(double errorRateThreshold) {
        this.errorRateThreshold = errorRateThreshold;
    }

    public int getNumAccounts() {
        return numAccounts;
    }

    public void setNumAccounts(int numAccounts) {
        this.numAccounts = numAccounts;
    }

    public int getNumItems() {
        return numItems;
    }

    public void setNumItems(int numItems) {
        this.numItems = numItems;
    }

    public int getNumOrders() {
        return numOrders;
    }

    public void setNumOrders(int numOrders) {
        this.numOrders = numOrders;
    }

    /**
     * Calculate pool size based on disciplined pooling strategy.
     */
    public int calculateDisciplinedPoolSize() {
        if (replicas <= 0) {
            return poolSize;
        }
        int calculated = dbConnectionBudget / replicas;
        return Math.max(1, Math.min(calculated, maxPoolSizePerReplica));
    }
}
