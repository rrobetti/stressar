package com.bench.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Connection provider for OJP gateway.
 * Uses minimal local pooling (1-2 connections) as OJP handles connection pooling.
 */
public class OjpProvider implements ConnectionProvider {
    private static final Logger logger = LoggerFactory.getLogger(OjpProvider.class);
    private static final int MINIMAL_POOL_SIZE = 2;
    
    private final HikariDataSource dataSource;
    
    public OjpProvider(DatabaseConfig dbConfig) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getJdbcUrl());  // Should point to OJP endpoint
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
        config.setCachePrepStmts(true);
        config.setPrepStmtCacheSize(250);
        config.setPrepStmtCacheSqlLimit(2048);
        config.setUseServerPrepStmts(true);
        
        // Add any additional properties
        dbConfig.getProperties().forEach((key, value) -> 
            config.addDataSourceProperty(key.toString(), value)
        );
        
        this.dataSource = new HikariDataSource(config);
        
        logger.info("Initialized OJP provider with minimal pool size: {}", MINIMAL_POOL_SIZE);
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
        return "OJP";
    }
    
    @Override
    public int getPoolSize() {
        return MINIMAL_POOL_SIZE;
    }
    
    @Override
    public void close() throws Exception {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Closing OJP connection pool");
            dataSource.close();
        }
    }
}
