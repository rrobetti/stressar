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
    private static final int MINIMAL_POOL_SIZE = 2;
    
    private final HikariDataSource dataSource;
    
    public PgbouncerProvider(DatabaseConfig dbConfig) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getJdbcUrl());  // Should point to PgBouncer endpoint
        config.setUsername(dbConfig.getUsername());
        config.setPassword(dbConfig.getPassword());
        
        // Minimal pool configuration
        config.setMaximumPoolSize(MINIMAL_POOL_SIZE);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        // Performance settings
        config.setAutoCommit(false);
        // prepareThreshold=0 disables server-side prepared statements.
        // Server-side prepared statements are session-scoped in PostgreSQL; in
        // pgBouncer transaction pooling mode each transaction may be routed to a
        // different backend server connection, so a statement prepared on one
        // server does not exist on another. Leaving the PostgreSQL JDBC default
        // (prepareThreshold=5) causes "prepared statement does not exist" errors
        // after the 5th execution of every unique SQL, producing transaction
        // rollbacks throughout the benchmark run.
        config.addDataSourceProperty("prepareThreshold", "0");
        
        // Add any additional properties
        dbConfig.getProperties().forEach((key, value) -> 
            config.addDataSourceProperty(key.toString(), value)
        );
        
        this.dataSource = new HikariDataSource(config);
        
        logger.info("Initialized PgBouncer provider with minimal pool size: {}", MINIMAL_POOL_SIZE);
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
        return MINIMAL_POOL_SIZE;
    }
    
    @Override
    public void close() throws Exception {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Closing PgBouncer connection pool");
            dataSource.close();
        }
    }
}
