package com.bench.workloads;

import com.bench.config.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * W3: Slow-query mix workload.
 * Combines the W2_MIXED query distribution with an additional slow analytical path.
 * Mix: {@code slowQueryPercent} slow path (heavy join/aggregate) /
 *      {@code (1 - slowQueryPercent)} W2_MIXED operations (read/write OLTP).
 * At the default 10% slow-query rate and typical RPS, several connections are
 * occupied concurrently by slow queries, keeping the pool under measurable
 * pressure throughout the benchmark.
 */
public class SlowQueryWorkload extends Workload {
    private final double slowQueryPercent;
    private final MixedWorkload mixedWorkload;

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
                            double zipfAlpha, double slowQueryPercent,
                            double queryAPercent, double writePercent) {
        super(connectionProvider, seed, numAccounts, numItems, useZipf, zipfAlpha);
        this.slowQueryPercent = slowQueryPercent;
        this.mixedWorkload = new MixedWorkload(connectionProvider, seed + 1,
            numAccounts, numItems, useZipf, zipfAlpha, queryAPercent, writePercent);
    }
    
    @Override
    public void execute() throws SQLException {
        if (random.nextDouble() < slowQueryPercent) {
            executeSlowQuery();
        } else {
            mixedWorkload.execute();
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
