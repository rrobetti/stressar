package com.bench.workloads;

import com.bench.config.BenchmarkConfig;
import com.bench.config.ConnectionProvider;
import com.bench.config.WorkloadConfig;

/**
 * Factory for creating workload instances.
 */
public class WorkloadFactory {
    
    public static Workload createWorkload(ConnectionProvider connectionProvider, 
                                         BenchmarkConfig config) {
        WorkloadConfig workloadConfig = config.getWorkload();
        long seed = workloadConfig.getSeed();
        long numAccounts = config.getNumAccounts();
        long numItems = config.getNumItems();
        boolean useZipf = workloadConfig.isUseZipf();
        double zipfAlpha = workloadConfig.getZipfAlpha();
        
        WorkloadConfig.WorkloadType type = workloadConfig.getType();
        
        switch (type) {
            case W1_READ_ONLY:
                return new ReadOnlyWorkload(connectionProvider, seed, 
                    numAccounts, numItems, useZipf, zipfAlpha, 
                    workloadConfig.getQueryAPercent());
                    
            case W2_READ_WRITE:
                return new ReadWriteWorkload(connectionProvider, seed,
                    numAccounts, numItems, useZipf, zipfAlpha);
                    
            case W2_MIXED:
                return new MixedWorkload(connectionProvider, seed,
                    numAccounts, numItems, useZipf, zipfAlpha,
                    workloadConfig.getQueryAPercent(),
                    workloadConfig.getWritePercent());
                    
            case W2_WRITE_ONLY:
                return new ReadWriteWorkload(connectionProvider, seed,
                    numAccounts, numItems, useZipf, zipfAlpha);
                    
            case W3_SLOW_QUERY:
                return new SlowQueryWorkload(connectionProvider, seed,
                    numAccounts, numItems, useZipf, zipfAlpha,
                    workloadConfig.getSlowQueryPercent());
                    
            case W4_OLAP:
                return new OlapWorkload(connectionProvider, seed,
                    numAccounts, numItems, useZipf, zipfAlpha);
                    
            case W5_HTAP:
                return new HtapWorkload(connectionProvider, seed,
                    numAccounts, numItems, useZipf, zipfAlpha,
                    workloadConfig.getQueryAPercent(),
                    workloadConfig.getWritePercent(),
                    workloadConfig.getOlapPercent());
                    
            default:
                throw new IllegalArgumentException("Unknown workload type: " + type);
        }
    }
}
