package com.bench.workloads;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SQL and result-shape helpers shared across workloads (e.g. W1 QueryB and W3 fast path).
 */
public final class WorkloadQueries {

    /** Last 20 orders for an account — same as W1 QueryB and W3 fast path. */
    public static final String LAST_20_ORDERS_BY_ACCOUNT =
        "SELECT order_id, account_id, created_at, status, total_cents " +
        "FROM orders WHERE account_id = ? " +
        "ORDER BY created_at DESC LIMIT 20";

    private WorkloadQueries() {
    }

    public static void consumeLastOrdersRow(ResultSet rs) throws SQLException {
        rs.getLong("order_id");
        rs.getLong("account_id");
        rs.getTimestamp("created_at");
        rs.getInt("status");
        rs.getLong("total_cents");
    }
}
