package com.bench.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe prepared statement cache for a single connection.
 * Reuses PreparedStatement objects across multiple executions to avoid
 * repeated parsing and planning overhead.
 */
public class PreparedStatementCache {
    private final Connection connection;
    private final Map<String, PreparedStatement> cache;
    private long hits = 0;
    private long misses = 0;
    
    public PreparedStatementCache(Connection connection) {
        this.connection = connection;
        this.cache = new HashMap<>();
    }
    
    /**
     * Get or create a PreparedStatement for the given SQL.
     * The statement is cached and reused across calls.
     */
    public PreparedStatement getPreparedStatement(String sql) throws SQLException {
        PreparedStatement stmt = cache.get(sql);
        if (stmt != null && !stmt.isClosed()) {
            hits++;
            return stmt;
        }
        
        // Create new statement
        misses++;
        stmt = connection.prepareStatement(sql);
        cache.put(sql, stmt);
        return stmt;
    }
    
    /**
     * Close all cached prepared statements.
     * Should be called when the connection is being closed.
     */
    public void closeAll() {
        for (PreparedStatement stmt : cache.values()) {
            try {
                if (stmt != null && !stmt.isClosed()) {
                    stmt.close();
                }
            } catch (SQLException e) {
                // Log but don't throw on cleanup
            }
        }
        cache.clear();
    }
    
    /**
     * Get cache hit count.
     */
    public long getHits() {
        return hits;
    }
    
    /**
     * Get cache miss count (statements created).
     */
    public long getMisses() {
        return misses;
    }
    
    /**
     * Get cache size.
     */
    public int size() {
        return cache.size();
    }
}
