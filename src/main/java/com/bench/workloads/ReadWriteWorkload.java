package com.bench.workloads;

import com.bench.config.ConnectionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * W2: Read-write OLTP transaction workload.
 * Inserts an order with K lines (K uniform 1..5), then updates the total.
 */
public class ReadWriteWorkload extends Workload {
    
    private static final String INSERT_ORDER =
        "INSERT INTO orders(account_id, created_at, status, total_cents) " +
        "VALUES (?, ?, 0, 0) RETURNING order_id";
    
    private static final String INSERT_ORDER_LINE =
        "INSERT INTO order_lines(order_id, line_no, item_id, qty, price_cents) " +
        "SELECT ?, ?, ?, ?, price_cents FROM items WHERE item_id = ?";
    
    private static final String UPDATE_ORDER_TOTAL =
        "UPDATE orders SET total_cents = (" +
        "  SELECT COALESCE(SUM(qty * price_cents), 0) " +
        "  FROM order_lines WHERE order_id = ?" +
        "), status = 1 WHERE order_id = ?";
    
    public ReadWriteWorkload(ConnectionProvider connectionProvider, long seed,
                            long numAccounts, long numItems, boolean useZipf, 
                            double zipfAlpha) {
        super(connectionProvider, seed, numAccounts, numItems, useZipf, zipfAlpha);
    }
    
    @Override
    public void execute() throws SQLException {
        long accountId = generateAccountId();
        int numLines = random.nextInt(1, 5);
        long startNanos = System.nanoTime();
        
        try (Connection conn = connectionProvider.getConnection()) {
            conn.setAutoCommit(false);
            
            try {
                // Insert order
                long orderId;
                try (PreparedStatement stmt = conn.prepareStatement(INSERT_ORDER)) {
                    stmt.setLong(1, accountId);
                    stmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                    
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            orderId = rs.getLong(1);
                        } else {
                            throw new SQLException("Failed to get order_id");
                        }
                    }
                }
                
                // Insert order lines
                for (int lineNo = 1; lineNo <= numLines; lineNo++) {
                    long itemId = generateItemId();
                    int qty = generateQuantity();
                    
                    try (PreparedStatement stmt = conn.prepareStatement(INSERT_ORDER_LINE)) {
                        stmt.setLong(1, orderId);
                        stmt.setInt(2, lineNo);
                        stmt.setLong(3, itemId);
                        stmt.setInt(4, qty);
                        stmt.setLong(5, itemId);  // For price lookup
                        stmt.executeUpdate();
                    }
                }
                
                // Update order total
                try (PreparedStatement stmt = conn.prepareStatement(UPDATE_ORDER_TOTAL)) {
                    stmt.setLong(1, orderId);
                    stmt.setLong(2, orderId);
                    stmt.executeUpdate();
                }
                
                conn.commit();
                
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        if (queryLatencyRecorder != null) {
            queryLatencyRecorder.record("write_transaction", System.nanoTime() - startNanos);
        }
    }
    
    @Override
    public String getName() {
        return "W2_READ_WRITE";
    }
}
