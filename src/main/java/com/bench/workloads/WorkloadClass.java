package com.bench.workloads;

/**
 * Classifies a workload operation for per-class metric tracking.
 * <p>
 * TOTAL is always recorded for every operation. OLTP and OLAP are recorded
 * based on the actual operation executed (e.g. for W5_HTAP, the delegate
 * workload determines the class).
 */
public enum WorkloadClass {
    TOTAL,
    OLTP,
    OLAP
}
