package com.bench.metrics;

import org.junit.Test;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests for QueryLatencyRecorder.
 */
public class QueryLatencyRecorderTest {

    @Test
    public void testRecordAndMean() {
        QueryLatencyRecorder recorder = new QueryLatencyRecorder();

        // Record 3 values for query_a: 10ms, 20ms, 30ms -> mean 20ms
        recorder.record("query_a", TimeUnit.MILLISECONDS.toNanos(10));
        recorder.record("query_a", TimeUnit.MILLISECONDS.toNanos(20));
        recorder.record("query_a", TimeUnit.MILLISECONDS.toNanos(30));

        Map<String, Double> means = recorder.getMeanLatenciesByQuery();
        assertTrue("query_a should be present", means.containsKey("query_a"));
        assertEquals("Mean should be ~20ms", 20.0, means.get("query_a"), 1.0);
    }

    @Test
    public void testMultipleQueryTypes() {
        QueryLatencyRecorder recorder = new QueryLatencyRecorder();

        recorder.record("query_a", TimeUnit.MILLISECONDS.toNanos(5));
        recorder.record("query_b", TimeUnit.MILLISECONDS.toNanos(15));
        recorder.record("write_transaction", TimeUnit.MILLISECONDS.toNanos(50));

        Map<String, Double> means = recorder.getMeanLatenciesByQuery();
        assertEquals(3, means.size());
        assertEquals(5.0, means.get("query_a"), 1.0);
        assertEquals(15.0, means.get("query_b"), 1.0);
        assertEquals(50.0, means.get("write_transaction"), 2.0);
    }

    @Test
    public void testCountByQuery() {
        QueryLatencyRecorder recorder = new QueryLatencyRecorder();

        recorder.record("query_a", TimeUnit.MILLISECONDS.toNanos(10));
        recorder.record("query_a", TimeUnit.MILLISECONDS.toNanos(20));
        recorder.record("query_b", TimeUnit.MILLISECONDS.toNanos(5));

        Map<String, Long> counts = recorder.getCountByQuery();
        assertEquals(2L, (long) counts.get("query_a"));
        assertEquals(1L, (long) counts.get("query_b"));
    }

    @Test
    public void testReset() {
        QueryLatencyRecorder recorder = new QueryLatencyRecorder();

        recorder.record("query_a", TimeUnit.MILLISECONDS.toNanos(10));
        recorder.reset();

        Map<String, Double> means = recorder.getMeanLatenciesByQuery();
        assertTrue("After reset, no means should be reported", means.isEmpty());

        Map<String, Long> counts = recorder.getCountByQuery();
        if (!counts.isEmpty()) {
            counts.values().forEach(c -> assertEquals("Count should be 0 after reset", 0L, (long) c));
        }
    }

    @Test
    public void testEmptyRecorder() {
        QueryLatencyRecorder recorder = new QueryLatencyRecorder();

        Map<String, Double> means = recorder.getMeanLatenciesByQuery();
        assertTrue("New recorder should return empty means", means.isEmpty());

        Map<String, Long> counts = recorder.getCountByQuery();
        assertTrue("New recorder should return empty counts", counts.isEmpty());
    }
}
