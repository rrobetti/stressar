package com.bench.workloads;

import com.bench.config.ConnectionProvider;
import com.bench.metrics.QueryLatencyRecorder;

import java.sql.SQLException;

/**
 * Mixed workload: Combines read-only and read-write operations.
 * Configurable mix (e.g., 80% read / 20% write).
 */
public class MixedWorkload extends Workload {
    private final ReadOnlyWorkload readWorkload;
    private final ReadWriteWorkload writeWorkload;
    private final double writePercent;
    
    public MixedWorkload(ConnectionProvider connectionProvider, long seed,
                        long numAccounts, long numItems, boolean useZipf,
                        double zipfAlpha, double queryAPercent, double writePercent) {
        super(connectionProvider, seed, numAccounts, numItems, useZipf, zipfAlpha);
        this.writePercent = writePercent;
        this.readWorkload = new ReadOnlyWorkload(connectionProvider, seed, 
            numAccounts, numItems, useZipf, zipfAlpha, queryAPercent);
        this.writeWorkload = new ReadWriteWorkload(connectionProvider, seed + 1,
            numAccounts, numItems, useZipf, zipfAlpha);
    }
    
    @Override
    public void execute() throws SQLException {
        if (random.nextDouble() < writePercent) {
            writeWorkload.execute();
        } else {
            readWorkload.execute();
        }
    }

    @Override
    public void setQueryLatencyRecorder(QueryLatencyRecorder recorder) {
        super.setQueryLatencyRecorder(recorder);
        readWorkload.setQueryLatencyRecorder(recorder);
        writeWorkload.setQueryLatencyRecorder(recorder);
    }
    
    @Override
    public String getName() {
        return "W2_MIXED";
    }
}
