package com.bench.config;

import com.bench.util.PreparedStatementCache;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Wrapper for a JDBC Connection that includes a PreparedStatement cache.
 * Enables efficient statement reuse within the connection lifecycle.
 */
public class ConnectionWithCache extends ConnectionWrapper {
    private final PreparedStatementCache cache;
    
    public ConnectionWithCache(Connection delegate) {
        super(delegate);
        this.cache = new PreparedStatementCache(delegate);
    }
    
    /**
     * Get a cached PreparedStatement for the given SQL.
     * This method should be used instead of connection.prepareStatement()
     * for repeated queries to benefit from caching.
     */
    public PreparedStatement getCachedPreparedStatement(String sql) throws SQLException {
        return cache.getPreparedStatement(sql);
    }
    
    /**
     * Get the PreparedStatement cache for this connection.
     */
    public PreparedStatementCache getCache() {
        return cache;
    }
    
    @Override
    public void close() throws SQLException {
        try {
            cache.closeAll();
        } finally {
            super.close();
        }
    }
}
