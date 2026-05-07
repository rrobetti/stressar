package com.bench.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Connection provider for PgBouncer.
 * Uses minimal local pooling (1-2 connections) as PgBouncer handles connection pooling.
 */
public class PgbouncerProvider implements ConnectionProvider {
    private static final Logger logger = LoggerFactory.getLogger(PgbouncerProvider.class);
    private final HikariDataSource dataSource;
    private final int poolSize;

    public PgbouncerProvider(DatabaseConfig dbConfig, int poolSize) {
        this.poolSize = poolSize;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getJdbcUrl());  // Should point to PgBouncer endpoint
        config.setUsername(dbConfig.getUsername());
        config.setPassword(dbConfig.getPassword());
        
        // Client-side pool: PgBouncer holds the real backend pool; keep local
        // connections equal to the configured poolSize (default 2).
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.max(1, poolSize / 2));
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // autoCommit=true (JDBC default): read workloads need no transaction wrapper
        // and write workloads (ReadWriteWorkload) explicitly call setAutoCommit(false)
        // on each connection before use.  Leaving the pool in autoCommit=false causes
        // HikariCP to issue a silent ROLLBACK when a connection is returned after a
        // read-only request, inflating pg_stat_database.xact_rollback.
        config.setAutoCommit(true);

        // prepareThreshold=0: disable PostgreSQL server-side prepared statements.
        // In pgBouncer transaction pooling mode each transaction can be routed to a
        // different backend connection.  Server-side prepared statements are
        // session-scoped, so a statement prepared on backend B1 does not exist on
        // backend B2 — causing "prepared statement does not exist" errors that abort
        // the transaction and increment xact_rollback.
        config.addDataSourceProperty("prepareThreshold", "0");

        // Add any additional properties from config
        dbConfig.getProperties().forEach((key, value) -> 
            config.addDataSourceProperty(key.toString(), value)
        );
        
        this.dataSource = new HikariDataSource(config);
        
        logger.info("Initialized PgBouncer provider with pool size: {}", poolSize);
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    @Override
    public DataSource getDataSource() {
        return dataSource;
    }
    
    @Override
    public String getModeName() {
        return "PGBOUNCER";
    }
    
    @Override
    public int getPoolSize() {
        return poolSize;
    }
    
    @Override
    public void close() throws Exception {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Closing PgBouncer connection pool");
            dataSource.close();
        }
    }
}
