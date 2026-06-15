package com.bench.workloads;

/**
 * Returned by {@link Workload#execute()} to carry the classification of the
 * operation that was just executed. This lets the load generator route metrics
 * to the correct per-class bucket without requiring shared mutable state.
 * <p>
 * Thread safety: instances are immutable and freely shareable.
 */
public final class WorkloadExecutionResult {

    private static final WorkloadExecutionResult OLTP_INSTANCE = new WorkloadExecutionResult(WorkloadClass.OLTP);
    private static final WorkloadExecutionResult OLAP_INSTANCE = new WorkloadExecutionResult(WorkloadClass.OLAP);

    private final WorkloadClass workloadClass;

    private WorkloadExecutionResult(WorkloadClass workloadClass) {
        this.workloadClass = workloadClass;
    }

    /** Returns a result representing an OLTP operation. */
    public static WorkloadExecutionResult oltp() {
        return OLTP_INSTANCE;
    }

    /** Returns a result representing an OLAP operation. */
    public static WorkloadExecutionResult olap() {
        return OLAP_INSTANCE;
    }

    /** The workload class of the completed operation. Never {@link WorkloadClass#TOTAL}. */
    public WorkloadClass getWorkloadClass() {
        return workloadClass;
    }
}
