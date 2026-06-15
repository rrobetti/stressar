package com.bench.metrics;

import com.bench.workloads.WorkloadClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class MetricsCollectorTest {

    @Test
    public void testTracksSuccessfulFailedAndTotalLatencyMeans() {
        MetricsCollector collector = new MetricsCollector();

        collector.recordSuccess(10_000_000L);  // 10ms
        collector.recordSuccess(30_000_000L);  // 30ms
        collector.recordError("RuntimeException", "boom", 50_000_000L);  // 50ms

        MetricsSnapshot snapshot = collector.getSnapshot();

        assertEquals(2L, snapshot.getCompletedRequests());
        assertEquals(1L, snapshot.getErrors());
        assertEquals(20.0, snapshot.getMean(), 0.5);
        assertEquals(50.0, snapshot.getMeanFailed(), 0.5);
        assertEquals(30.0, snapshot.getMeanTotal(), 0.5);
    }

    @Test
    public void testOltpSuccessIncrementsOnlyTotalAndOltp() {
        MetricsCollector collector = new MetricsCollector();
        collector.recordSuccess(WorkloadClass.OLTP, 10_000_000L);

        MetricsSnapshot snapshot = collector.getSnapshot();

        // TOTAL
        assertEquals(1L, snapshot.getCompletedRequests());
        assertEquals(0L, snapshot.getErrors());

        // OLTP class
        MetricsSnapshot oltpSnap = snapshot.getClassSnapshot(WorkloadClass.OLTP);
        assertNotNull(oltpSnap);
        assertEquals(1L, oltpSnap.getCompletedRequests());
        assertEquals(1L, oltpSnap.getAttemptedRequests());
        assertEquals(0L, oltpSnap.getErrors());

        // OLAP class — nothing recorded
        MetricsSnapshot olapSnap = snapshot.getClassSnapshot(WorkloadClass.OLAP);
        assertNotNull(olapSnap);
        assertEquals(0L, olapSnap.getCompletedRequests());
        assertEquals(0L, olapSnap.getAttemptedRequests());
    }

    @Test
    public void testOlapSuccessIncrementsOnlyTotalAndOlap() {
        MetricsCollector collector = new MetricsCollector();
        collector.recordSuccess(WorkloadClass.OLAP, 20_000_000L);

        MetricsSnapshot snapshot = collector.getSnapshot();

        // TOTAL
        assertEquals(1L, snapshot.getCompletedRequests());

        // OLAP class
        MetricsSnapshot olapSnap = snapshot.getClassSnapshot(WorkloadClass.OLAP);
        assertNotNull(olapSnap);
        assertEquals(1L, olapSnap.getCompletedRequests());

        // OLTP class — nothing recorded
        MetricsSnapshot oltpSnap = snapshot.getClassSnapshot(WorkloadClass.OLTP);
        assertNotNull(oltpSnap);
        assertEquals(0L, oltpSnap.getCompletedRequests());
    }

    @Test
    public void testErrorsAreSeparatedByWorkloadClass() {
        MetricsCollector collector = new MetricsCollector();
        collector.recordError(WorkloadClass.OLTP, "TimeoutException", "timeout", 5_000_000L);
        collector.recordError(WorkloadClass.OLAP, "SQLException", "sql error", 15_000_000L);

        MetricsSnapshot snapshot = collector.getSnapshot();

        // TOTAL should have both errors
        assertEquals(2L, snapshot.getErrors());

        MetricsSnapshot oltpSnap = snapshot.getClassSnapshot(WorkloadClass.OLTP);
        assertEquals(1L, oltpSnap.getErrors());
        assertEquals(1L, (long) oltpSnap.getErrorsByType().get("TimeoutException"));
        assertNull(oltpSnap.getErrorsByType().get("SQLException"));

        MetricsSnapshot olapSnap = snapshot.getClassSnapshot(WorkloadClass.OLAP);
        assertEquals(1L, olapSnap.getErrors());
        assertEquals(1L, (long) olapSnap.getErrorsByType().get("SQLException"));
        assertNull(olapSnap.getErrorsByType().get("TimeoutException"));
    }

    @Test
    public void testLatencyHistogramsAreSeparatedByClass() {
        MetricsCollector collector = new MetricsCollector();
        // Record 10 ms OLTP, 100 ms OLAP
        collector.recordSuccess(WorkloadClass.OLTP, 10_000_000L);
        collector.recordSuccess(WorkloadClass.OLAP, 100_000_000L);

        MetricsSnapshot snapshot = collector.getSnapshot();

        MetricsSnapshot oltpSnap = snapshot.getClassSnapshot(WorkloadClass.OLTP);
        MetricsSnapshot olapSnap = snapshot.getClassSnapshot(WorkloadClass.OLAP);

        // OLTP p99 should be ~10 ms; OLAP p99 should be ~100 ms
        assertTrue("OLTP p99 should be ~10 ms", oltpSnap.getP99() < 20.0);
        assertTrue("OLAP p99 should be ~100 ms", olapSnap.getP99() > 50.0);
    }

    @Test
    public void testTotalCountEqualsOltpPlusOlapForHtapRuns() {
        MetricsCollector collector = new MetricsCollector();
        // Simulate 3 OLTP + 2 OLAP successes
        for (int i = 0; i < 3; i++) {
            collector.recordAttempt();
            collector.recordSuccess(WorkloadClass.OLTP, 10_000_000L);
        }
        for (int i = 0; i < 2; i++) {
            collector.recordAttempt();
            collector.recordSuccess(WorkloadClass.OLAP, 100_000_000L);
        }

        MetricsSnapshot snapshot = collector.getSnapshot();
        MetricsSnapshot oltpSnap = snapshot.getClassSnapshot(WorkloadClass.OLTP);
        MetricsSnapshot olapSnap = snapshot.getClassSnapshot(WorkloadClass.OLAP);

        assertEquals(5L, snapshot.getCompletedRequests());
        assertEquals(3L, oltpSnap.getCompletedRequests());
        assertEquals(2L, olapSnap.getCompletedRequests());
        assertEquals(snapshot.getCompletedRequests(),
            oltpSnap.getCompletedRequests() + olapSnap.getCompletedRequests());
    }
}
