package com.bench.config;

/**
 * Connection mode configuration.
 */
public enum ConnectionMode {
    HIKARI_DIRECT,      // Direct JDBC with HikariCP (per-process pool)
    HIKARI_DISCIPLINED, // Direct JDBC with disciplined pooling (budget/replicas)
    OJP,                // JDBC to OJP endpoint (minimal local pool)
    PGBOUNCER           // JDBC to PgBouncer (minimal local pool)
}
