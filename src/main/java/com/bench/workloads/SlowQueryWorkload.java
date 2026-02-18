package com.bench.workloads;

import com.bench.config.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * W3: Slow-query mix workload.
 * Mix: 99% fast path (same as W1 QueryB) / 1% slow path (heavy join/aggregate).
 */
public class SlowQueryWorkload extends Workload {
    private final double slowQueryPercent;
    
    // Fast path: same as W1 QueryB
    private static final String FAST_QUERY =
        "SELECT order_id, account_id, created_at, status, total_cents " +
        "FROM orders WHERE account_id = ? " +
        "ORDER BY created_at DESC LIMIT 20";
    
    // Slow path: heavy join/aggregate query
    private static final String SLOW_QUERY =
        "SELECT o.order_id, o.account_id, o.created_at, o.status, " +
        "       SUM(ol.qty * ol.price_cents) AS computed_total " +
        "FROM orders o " +
        "JOIN order_lines ol ON o.order_id = ol.order_id " +
        "WHERE o.created_at > (CURRENT_TIMESTAMP - INTERVAL '90 days') " +
        "GROUP BY o.order_id, o.account_id, o.created_at, o.status " +
        "ORDER BY o.created_at DESC LIMIT 500";
    
    public SlowQueryWorkload(ConnectionProvider connectionProvider, long seed,
                            long numAccounts, long numItems, boolean useZipf,
                            double zipfAlpha, double slowQueryPercent) {
        super(connectionProvider, seed, numAccounts, numItems, useZipf, zipfAlpha);
        this.slowQueryPercent = slowQueryPercent;
    }
    
    @Override
    public void execute() throws SQLException {
        if (random.nextDouble() < slowQueryPercent) {
            executeSlowQuery();
        } else {
            executeFastQuery();
        }
    }
    
    private void executeFastQuery() throws SQLException {
        long accountId = generateAccountId();
        
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FAST_QUERY)) {
            
            stmt.setLong(1, accountId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Consume results
                    rs.getLong("order_id");
                    rs.getLong("account_id");
                    rs.getTimestamp("created_at");
                    rs.getInt("status");
                    rs.getLong("total_cents");
                }
            }
        }
    }
    
    private void executeSlowQuery() throws SQLException {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SLOW_QUERY)) {
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Consume results
                    rs.getLong("order_id");
                    rs.getLong("account_id");
                    rs.getTimestamp("created_at");
                    rs.getInt("status");
                    rs.getLong("computed_total");
                }
            }
        }
    }
    
    @Override
    public String getName() {
        return "W3_SLOW_QUERY";
    }
}
