package com.bench.workloads;

import com.bench.config.ConnectionProvider;
import com.bench.util.RandomGenerator;
import com.bench.util.ZipfGenerator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Abstract base for workload implementations.
 */
public abstract class Workload {

    @FunctionalInterface
    protected interface ResultSetRowConsumer {
        void accept(ResultSet rs) throws SQLException;
    }
    protected final ConnectionProvider connectionProvider;
    protected final RandomGenerator random;
    protected final ZipfGenerator zipfGenerator;
    protected final boolean useZipf;
    
    // Dataset bounds
    protected long numAccounts;
    protected long numItems;
    
    public Workload(ConnectionProvider connectionProvider, long seed, 
                    long numAccounts, long numItems, boolean useZipf, double zipfAlpha) {
        this.connectionProvider = connectionProvider;
        this.random = new RandomGenerator(seed);
        this.useZipf = useZipf;
        this.numAccounts = numAccounts;
        this.numItems = numItems;
        
        if (useZipf) {
            this.zipfGenerator = new ZipfGenerator((int) numAccounts, zipfAlpha, seed);
        } else {
            this.zipfGenerator = null;
        }
    }
    
    /**
     * Execute one workload operation.
     * @throws SQLException on database errors
     */
    public abstract void execute() throws SQLException;
    
    /**
     * Get workload name.
     */
    public abstract String getName();
    
    /**
     * Generate an account ID based on distribution.
     */
    protected long generateAccountId() {
        if (useZipf && zipfGenerator != null) {
            return zipfGenerator.nextLong();
        } else {
            return random.nextLong(1, numAccounts);
        }
    }
    
    /**
     * Generate an item ID uniformly.
     */
    protected long generateItemId() {
        return random.nextLong(1, numItems);
    }
    
    /**
     * Generate a quantity (1-4).
     */
    protected int generateQuantity() {
        return random.nextInt(1, 4);
    }

    protected void executeSingleLongParamQuery(
        String sql, long param, ResultSetRowConsumer rowConsumer) throws SQLException {
        try (Connection conn = connectionProvider.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, param);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rowConsumer.accept(rs);
                }
            }
        }
    }
}
