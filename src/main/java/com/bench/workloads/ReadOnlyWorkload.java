package com.bench.workloads;

import com.bench.config.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * W1: Read-only OLTP workload.
 * Mix: 30% QueryA (SELECT account by ID) / 70% QueryB (last 20 orders for account)
 */
public class ReadOnlyWorkload extends Workload {
    private final double queryAPercent;
    
    private static final String QUERY_A = 
        "SELECT account_id, username, email, full_name, balance_cents, status " +
        "FROM accounts WHERE account_id = ?";

    public ReadOnlyWorkload(ConnectionProvider connectionProvider, long seed,
                           long numAccounts, long numItems, boolean useZipf, 
                           double zipfAlpha, double queryAPercent) {
        super(connectionProvider, seed, numAccounts, numItems, useZipf, zipfAlpha);
        this.queryAPercent = queryAPercent;
    }
    
    @Override
    public void execute() throws SQLException {
        if (random.nextDouble() < queryAPercent) {
            executeQueryA();
        } else {
            executeQueryB();
        }
    }
    
    private void executeQueryA() throws SQLException {
        long accountId = generateAccountId();
        long startNanos = System.nanoTime();
        
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(QUERY_A)) {
            
            stmt.setLong(1, accountId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Consume results
                    rs.getLong("account_id");
                    rs.getString("username");
                    rs.getString("email");
                }
            }
        }
        if (queryLatencyRecorder != null) {
            queryLatencyRecorder.record("query_a", System.nanoTime() - startNanos);
        }
    }
    
    private void executeQueryB() throws SQLException {
        long startNanos = System.nanoTime();
        executeSingleLongParamQuery(
            WorkloadQueries.LAST_20_ORDERS_BY_ACCOUNT,
            generateAccountId(),
            WorkloadQueries::consumeLastOrdersRow);
        if (queryLatencyRecorder != null) {
            queryLatencyRecorder.record("query_b", System.nanoTime() - startNanos);
        }
    }
    
    @Override
    public String getName() {
        return "W1_READ_ONLY";
    }
}
