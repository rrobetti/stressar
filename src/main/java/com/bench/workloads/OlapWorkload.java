package com.bench.workloads;

import com.bench.config.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * W4: Pure OLAP workload.
 * <p>
 * Cycles through 5 analytical queries in round-robin order so every query shape
 * is exercised evenly even at low concurrency.  All queries are designed to force
 * full-table scans or large range scans with aggregation/join/window work,
 * generating real planner and executor CPU rather than hitting index buffer cache.
 * <p>
 * Recommended targetRps: 20–50 (each query can take 100 ms – several seconds
 * depending on data volume).
 */
public class OlapWorkload extends Workload {

    // Q1: daily revenue — full table scan + group-by sort on orders
    static final String DAILY_REVENUE =
        "SELECT DATE_TRUNC('day', created_at), COUNT(*), SUM(total_cents) " +
        "FROM orders " +
        "GROUP BY 1 " +
        "ORDER BY 1";

    // Q2: top customers — hash join between accounts and orders, sort
    static final String TOP_CUSTOMERS =
        "SELECT a.account_id, a.username, COUNT(o.order_id), SUM(o.total_cents) " +
        "FROM accounts a " +
        "JOIN orders o ON a.account_id = o.account_id " +
        "GROUP BY 1, 2 " +
        "ORDER BY 4 DESC " +
        "LIMIT 100";

    // Q3: item performance — full scan of order_lines (largest table), aggregation
    static final String ITEM_PERFORMANCE =
        "SELECT i.item_id, i.name, SUM(ol.qty) AS units_sold, " +
        "       SUM(ol.qty * ol.price_cents) AS revenue " +
        "FROM items i " +
        "JOIN order_lines ol ON i.item_id = ol.item_id " +
        "GROUP BY 1, 2 " +
        "ORDER BY 4 DESC " +
        "LIMIT 50";

    // Q4: order status distribution — STDDEV forces full aggregation over orders
    static final String ORDER_STATUS_DISTRIBUTION =
        "SELECT status, COUNT(*), SUM(total_cents), AVG(total_cents), STDDEV(total_cents) " +
        "FROM orders " +
        "GROUP BY status " +
        "ORDER BY status";

    // Q5: account running totals — window function forces sort/partition of all orders
    static final String ACCOUNT_RUNNING_TOTALS =
        "SELECT order_id, account_id, total_cents, " +
        "       SUM(total_cents) OVER (PARTITION BY account_id ORDER BY created_at) AS running_total " +
        "FROM orders " +
        "ORDER BY account_id, created_at " +
        "LIMIT 1000";

    static final String[] QUERIES = {
        DAILY_REVENUE,
        TOP_CUSTOMERS,
        ITEM_PERFORMANCE,
        ORDER_STATUS_DISTRIBUTION,
        ACCOUNT_RUNNING_TOTALS
    };

    private final AtomicInteger queryIndex = new AtomicInteger(0);

    public OlapWorkload(ConnectionProvider connectionProvider, long seed,
                        long numAccounts, long numItems, boolean useZipf, double zipfAlpha) {
        super(connectionProvider, seed, numAccounts, numItems, useZipf, zipfAlpha);
    }

    @Override
    public void execute() throws SQLException {
        // Mask the sign bit before the modulo so the index stays non-negative even
        // after the AtomicInteger wraps around Integer.MAX_VALUE.
        int idx = (queryIndex.getAndIncrement() & Integer.MAX_VALUE) % QUERIES.length;
        String sql = QUERIES[idx];

        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            switch (idx) {
                case 0: consumeDailyRevenue(rs); break;
                case 1: consumeTopCustomers(rs); break;
                case 2: consumeItemPerformance(rs); break;
                case 3: consumeOrderStatusDistribution(rs); break;
                case 4: consumeAccountRunningTotals(rs); break;
                default: while (rs.next()) { /* drain */ }
            }
        }
    }

    private void consumeDailyRevenue(ResultSet rs) throws SQLException {
        while (rs.next()) {
            rs.getTimestamp(1);
            rs.getLong(2);
            rs.getLong(3);
        }
    }

    private void consumeTopCustomers(ResultSet rs) throws SQLException {
        while (rs.next()) {
            rs.getLong("account_id");
            rs.getString("username");
            rs.getLong(3);
            rs.getLong(4);
        }
    }

    private void consumeItemPerformance(ResultSet rs) throws SQLException {
        while (rs.next()) {
            rs.getLong("item_id");
            rs.getString("name");
            rs.getLong("units_sold");
            rs.getLong("revenue");
        }
    }

    private void consumeOrderStatusDistribution(ResultSet rs) throws SQLException {
        while (rs.next()) {
            rs.getInt("status");
            rs.getLong(2);
            rs.getLong(3);
            rs.getDouble(4);
            rs.getDouble(5);
        }
    }

    private void consumeAccountRunningTotals(ResultSet rs) throws SQLException {
        while (rs.next()) {
            rs.getLong("order_id");
            rs.getLong("account_id");
            rs.getLong("total_cents");
            rs.getLong("running_total");
        }
    }

    @Override
    public String getName() {
        return "W4_OLAP";
    }
}
