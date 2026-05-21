package com.bench.metrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MetricsCollectorTest {

    @Test
    public void testTracksSuccessfulFailedAndTotalLatencyMeans() {
        MetricsCollector collector = new MetricsCollector();

        collector.recordSuccess(10_000_000L);  // 10ms
        collector.recordSuccess(30_000_000L);  // 30ms
        collector.recordError("RuntimeException", "boom", 20_000_000L);  // 20ms

        MetricsSnapshot snapshot = collector.getSnapshot();

        assertEquals(2L, snapshot.getCompletedRequests());
        assertEquals(1L, snapshot.getErrors());
        assertEquals(20.0, snapshot.getMean(), 0.5);
        assertEquals(20.0, snapshot.getMeanFailed(), 0.5);
        assertEquals(20.0, snapshot.getMeanTotal(), 0.5);
    }
}
