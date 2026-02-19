package com.bench.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Abstraction for providing database connections.
 * Different implementations support different connection strategies.
 */
public interface ConnectionProvider extends AutoCloseable {
    /**
     * Get a connection from the pool.
     */
    Connection getConnection() throws SQLException;
    
    /**
     * Get the underlying DataSource.
     */
    DataSource getDataSource();
    
    /**
     * Get the connection mode name.
     */
    String getModeName();
    
    /**
     * Get the configured pool size.
     */
    int getPoolSize();
    
    /**
     * Close the connection provider and release resources.
     */
    @Override
    void close() throws Exception;
}
