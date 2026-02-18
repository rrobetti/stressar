package com.bench.config;

/**
 * Pool sharing strategy for OJP server-side pools.
 */
public enum OjpPoolSharing {
    /**
     * All replicas share a single OJP server-side pool.
     * The pool size is set to the full DB connection budget.
     */
    SHARED,
    
    /**
     * Each replica gets its own OJP server-side pool.
     * The pool size is divided among replicas (budget / K).
     */
    PER_INSTANCE
}
