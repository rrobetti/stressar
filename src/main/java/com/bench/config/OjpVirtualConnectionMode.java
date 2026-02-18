package com.bench.config;

/**
 * Virtual connection mode for OJP.
 * OJP does not use client-side pooling; instead, the application
 * creates virtual JDBC connection handles that map to a server-side pool.
 */
public enum OjpVirtualConnectionMode {
    /**
     * Each worker thread holds one virtual JDBC Connection for the entire run.
     * Default and recommended for most scenarios.
     */
    PER_WORKER,
    
    /**
     * Open and close a virtual JDBC Connection per operation.
     * Useful for testing connection churn scenarios.
     */
    PER_OPERATION
}
