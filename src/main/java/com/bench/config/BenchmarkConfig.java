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
    
    // OJP-specific configuration
    private OjpConfig ojpConfig;
    
    // Prepared statement caching
    private boolean enablePreparedStatementCache = true;

    public BenchmarkConfig() {
        this.database = new DatabaseConfig();
        this.workload = new WorkloadConfig();
        this.ojpConfig = new OjpConfig();
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

    public OjpConfig getOjpConfig() {
        return ojpConfig;
    }
    
    public void setOjpConfig(OjpConfig ojpConfig) {
        this.ojpConfig = ojpConfig;
    }
    
    /**
     * Calculate pool size based on disciplined pooling strategy.
     */
    public int calculateDisciplinedPoolSize() {
        if (replicas <= 0) {
            return poolSize;
        }
        int calculated = (int) Math.ceil((double) dbConnectionBudget / replicas);
        return Math.max(1, Math.min(calculated, maxPoolSizePerReplica));
    }
    
    /**
     * Calculate OJP server-side pool allocation.
     * <p>
     * {@code bench_db_connection_budget} is the per-OJP-server connection budget.
     * Each OJP server instance gets the full budget as its {@code maxConnections},
     * regardless of how many load-generator JVM replicas ({@code bench_replica_count})
     * are running.  The load-generator replicas use virtual (logical) JDBC connections
     * to OJP and do not consume real backend connections themselves, so dividing the
     * budget by {@code bench_replica_count} would under-provision the OJP pool.
     * <p>
     * In SHARED mode (one pool shared across OJP instances) the same budget applies
     * to the single shared pool.
     *
     * @return The maxConnections value to set for the OJP server-side pool
     */
    public int calculateOjpAllocation() {
        // Both SHARED and PER_INSTANCE use the full budget per OJP server instance.
        // bench_replica_count is the number of load-generator JVMs, not OJP servers,
        // so it must not be used to divide the connection budget.
        return Math.max(1, dbConnectionBudget);
    }
    
    /**
     * Validate configuration for OJP mode.
     * Ensures no client-side pooling is configured when using OJP.
     */
    public void validateOjpConfig() {
        if (connectionMode != ConnectionMode.OJP) {
            return;
        }
        
        // OJP must not use client-side pooling
        if (poolSize > 0 && poolSize != 20) {  // 20 is the default, might be set unintentionally
            throw new IllegalArgumentException(
                "OJP mode must not configure client-side poolSize. " +
                "Use ojpConfig.maxConnections for server-side pool configuration.");
        }
        
        // Check for hikari-specific properties in database config
        database.getProperties().forEach((key, value) -> {
            String keyStr = key.toString().toLowerCase();
            if (keyStr.contains("hikari") || keyStr.contains("pool")) {
                throw new IllegalArgumentException(
                    "OJP mode must not use client-side pooling properties: " + key);
            }
        });
    }
    
    public boolean isEnablePreparedStatementCache() {
        return enablePreparedStatementCache;
    }
    
    public void setEnablePreparedStatementCache(boolean enablePreparedStatementCache) {
        this.enablePreparedStatementCache = enablePreparedStatementCache;
    }
}
