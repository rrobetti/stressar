package com.bench.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * HikariCP connection provider with configurable pool size.
 */
public class HikariProvider implements ConnectionProvider {
    private static final Logger logger = LoggerFactory.getLogger(HikariProvider.class);
    
    private final HikariDataSource dataSource;
    private final int poolSize;
    private final String modeName;
    
    public HikariProvider(DatabaseConfig dbConfig, int poolSize, boolean isDisciplined) {
        this.poolSize = poolSize;
        this.modeName = isDisciplined ? "HIKARI_DISCIPLINED" : "HIKARI_DIRECT";

        HikariConfig config = getHikariConfig(dbConfig, poolSize);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        
        // Add any additional properties
        dbConfig.getProperties().forEach((key, value) -> 
            config.addDataSourceProperty(key.toString(), value)
        );
        
        this.dataSource = new HikariDataSource(config);
        
        logger.info("Initialized {} with pool size: {}", modeName, poolSize);
    }

    private static @NonNull HikariConfig getHikariConfig(DatabaseConfig dbConfig, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbConfig.getJdbcUrl());
        config.setUsername(dbConfig.getUsername());
        config.setPassword(dbConfig.getPassword());

        // Pool configuration
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
        return config;
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
        return modeName;
    }
    
    @Override
    public int getPoolSize() {
        return poolSize;
    }
    
    @Override
    public void close() throws Exception {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Closing {} connection pool", modeName);
            dataSource.close();
        }
    }
}
