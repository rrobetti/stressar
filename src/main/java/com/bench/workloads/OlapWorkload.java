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
 * Cycles through 6 analytical queries in round-robin order so every query shape
 * is exercised evenly even at low concurrency.  Queries stay computationally
 * meaningful (aggregation/join/window work) but include realistic time windows so
 * production-style indexes are used and concurrency remains practical.
 * <p>
 * Recommended targetRps: 30–80 (query latency still depends on data volume and
 * sizing, but avoids pathological full-table behavior).
 */
public class OlapWorkload extends Workload {

    // Q1: daily revenue over recent history — range scan on orders(created_at).
    // Window halved (180→90 days) to roughly halve data scanned.
    static final String DAILY_REVENUE =
        "SELECT DATE_TRUNC('day', created_at), COUNT(*), SUM(total_cents) " +
        "FROM orders " +
        "WHERE created_at >= NOW() - INTERVAL '90 days' " +
        "GROUP BY 1 " +
        "ORDER BY 1";

    // Q2: top customers over recent activity — filtered aggregate + join + sort.
    // Window halved (90→45 days) to roughly halve data scanned.
    static final String TOP_CUSTOMERS =
        "WITH recent_orders AS ( " +
        "  SELECT account_id, total_cents " +
        "  FROM orders " +
        "  WHERE created_at >= NOW() - INTERVAL '45 days' " +
        ") " +
        "SELECT a.account_id, a.username, COUNT(*), SUM(ro.total_cents) " +
        "FROM recent_orders ro " +
        "JOIN accounts a ON a.account_id = ro.account_id " +
        "GROUP BY 1, 2 " +
        "ORDER BY 4 DESC " +
        "LIMIT 100";

    // Q3: item performance for recent orders — range-filtered join to order_lines.
    // Window halved (90→45 days) to roughly halve data scanned.
    static final String ITEM_PERFORMANCE =
        "WITH recent_orders AS ( " +
        "  SELECT order_id " +
        "  FROM orders " +
        "  WHERE created_at >= NOW() - INTERVAL '45 days' " +
        ") " +
        "SELECT i.item_id, i.name, SUM(ol.qty) AS units_sold, " +
        "       SUM(ol.qty * ol.price_cents) AS revenue " +
        "FROM recent_orders ro " +
        "JOIN order_lines ol ON ol.order_id = ro.order_id " +
        "JOIN items i ON i.item_id = ol.item_id " +
        "GROUP BY 1, 2 " +
        "ORDER BY 4 DESC " +
        "LIMIT 50";

    // Q4: order status distribution over recent history — filtered aggregate.
    // Window halved (180→90 days) to roughly halve data scanned.
    static final String ORDER_STATUS_DISTRIBUTION =
        "SELECT status, COUNT(*), SUM(total_cents), AVG(total_cents), STDDEV(total_cents) " +
        "FROM orders " +
        "WHERE created_at >= NOW() - INTERVAL '90 days' " +
        "GROUP BY status " +
        "ORDER BY status";

    // Q5: account running totals on recent slice — still exercises window/sort.
    // Window halved (30→15 days) to roughly halve data scanned.
    static final String ACCOUNT_RUNNING_TOTALS =
        "SELECT order_id, account_id, total_cents, " +
        "       SUM(total_cents) OVER (PARTITION BY account_id ORDER BY created_at) AS running_total " +
        "FROM orders " +
        "WHERE created_at >= NOW() - INTERVAL '15 days' " +
        "ORDER BY account_id, created_at " +
        "LIMIT 1000";

    // Q6: account email-domain scan query requested for OLAP mix.
    // Capped with a LIMIT so the full-scan cost is bounded (~half the prior work
    // on a representative dataset where '.com' addresses dominate).
    static final String ACCOUNT_EMAIL_COM =
        "SELECT * FROM accounts WHERE email LIKE '%@%.com' LIMIT 5000";

    static final String[] QUERIES = {
        DAILY_REVENUE,
        TOP_CUSTOMERS,
        ITEM_PERFORMANCE,
        ORDER_STATUS_DISTRIBUTION,
        ACCOUNT_RUNNING_TOTALS,
        ACCOUNT_EMAIL_COM
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
                case 5: consumeAccountEmailCom(rs); break;
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

    private void consumeAccountEmailCom(ResultSet rs) throws SQLException {
        while (rs.next()) {
            // drain
        }
    }

    @Override
    public String getName() {
        return "W4_OLAP";
    }
}
