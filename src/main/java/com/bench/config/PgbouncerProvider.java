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
 * Uses a configurable local client pool as PgBouncer handles backend connection pooling.
 */
public class PgbouncerProvider implements ConnectionProvider {
    private static final Logger logger = LoggerFactory.getLogger(PgbouncerProvider.class);
    private final int poolSize;
    
    private final HikariDataSource dataSource;
    
    public PgbouncerProvider(DatabaseConfig dbConfig, int configuredPoolSize) {
        this.poolSize = Math.max(1, configuredPoolSize);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getJdbcUrl());  // Should point to PgBouncer endpoint
        config.setUsername(dbConfig.getUsername());
        config.setPassword(dbConfig.getPassword());
        
        // Minimal pool configuration
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.max(1, poolSize / 2));
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        // Performance settings
        config.setAutoCommit(false);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        
        // Add any additional properties
        dbConfig.getProperties().forEach((key, value) -> 
            config.addDataSourceProperty(key.toString(), value)
        );
        
        this.dataSource = new HikariDataSource(config);
        
        logger.info("Initialized PgBouncer provider with local pool size: {}", poolSize);
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
