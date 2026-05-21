package com.bench.metrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MetricsSnapshotTest {

    @Test
    public void testThroughputSplitCalculations() {
        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setStartTimeMs(0);
        snapshot.setTimestampMs(2_000);
        snapshot.setCompletedRequests(6);
        snapshot.setErrors(2);

        assertEquals(3.0, snapshot.getAchievedThroughput(), 0.0001);
        assertEquals(1.0, snapshot.getErrorThroughput(), 0.0001);
        assertEquals(4.0, snapshot.getTotalThroughput(), 0.0001);
    }
}
