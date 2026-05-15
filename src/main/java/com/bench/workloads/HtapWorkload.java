package com.bench.workloads;

import com.bench.config.ConnectionProvider;

import java.sql.SQLException;

/**
 * W5: Hybrid OLAP/OLTP (HTAP) workload.
 * <p>
 * Simulates the realistic scenario where normal OLTP transactions and analytical
 * reads compete for the same connection pool and database CPU.  The split is
 * controlled by {@code olapPercent}:
 * <ul>
 *   <li>0.10 (10 %) — light analytics, typical for apps with live dashboards</li>
 *   <li>0.30 (30 %) — heavier analytics, representative of BI tools</li>
 *   <li>0.50 (50 %) — equal mix, worst-case stress test for pool isolation</li>
 * </ul>
 *
 * The OLTP side is handled by {@link MixedWorkload} (configurable queryAPercent +
 * writePercent).  The OLAP side is handled by {@link OlapWorkload} (5 analytical
 * queries in round-robin).
 */
public class HtapWorkload extends Workload {

    private final OlapWorkload olapWorkload;
    private final MixedWorkload mixedWorkload;
    private final double olapPercent;

    public HtapWorkload(ConnectionProvider connectionProvider, long seed,
                        long numAccounts, long numItems, boolean useZipf, double zipfAlpha,
                        double queryAPercent, double writePercent, double olapPercent) {
        super(connectionProvider, seed, numAccounts, numItems, useZipf, zipfAlpha);
        this.olapPercent = olapPercent;
        this.olapWorkload = new OlapWorkload(connectionProvider, seed + 2,
            numAccounts, numItems, useZipf, zipfAlpha);
        this.mixedWorkload = new MixedWorkload(connectionProvider, seed + 3,
            numAccounts, numItems, useZipf, zipfAlpha, queryAPercent, writePercent);
    }

    @Override
    public void execute() throws SQLException {
        if (random.nextDouble() < olapPercent) {
            olapWorkload.execute();
        } else {
            mixedWorkload.execute();
        }
    }

    @Override
    public String getName() {
        return "W5_HTAP";
    }
}
