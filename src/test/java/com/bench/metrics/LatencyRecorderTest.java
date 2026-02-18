package com.bench.metrics;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for LatencyRecorder.
 */
public class LatencyRecorderTest {
    
    @Test
    public void testBasicRecording() {
        LatencyRecorder recorder = new LatencyRecorder(60000, 3);
        
        // Record some values
        recorder.record(10.0);
        recorder.record(20.0);
        recorder.record(30.0);
        
        assertEquals("Count should be 3", 3, recorder.getCount());
        assertEquals("Mean should be ~20ms", 20.0, recorder.getMean(), 1.0);
    }
    
    @Test
    public void testPercentiles() {
        LatencyRecorder recorder = new LatencyRecorder(60000, 3);
        
        // Record values from 1 to 100
        for (int i = 1; i <= 100; i++) {
            recorder.record(i);
        }
        
        double p50 = recorder.getPercentile(50.0);
        assertTrue("p50 should be around 50", p50 >= 45 && p50 <= 55);
        
        double p95 = recorder.getPercentile(95.0);
        assertTrue("p95 should be around 95", p95 >= 90 && p95 <= 100);
        
        double p99 = recorder.getPercentile(99.0);
        assertTrue("p99 should be around 99", p99 >= 95 && p99 <= 100);
    }
    
    @Test
    public void testMax() {
        LatencyRecorder recorder = new LatencyRecorder(60000, 3);
        
        recorder.record(5.0);
        recorder.record(100.0);
        recorder.record(50.0);
        
        assertEquals("Max should be 100", 100.0, recorder.getMax(), 1.0);
    }
    
    @Test
    public void testReset() {
        LatencyRecorder recorder = new LatencyRecorder(60000, 3);
        
        recorder.record(10.0);
        recorder.record(20.0);
        assertEquals("Count should be 2", 2, recorder.getCount());
        
        recorder.reset();
        assertEquals("Count should be 0 after reset", 0, recorder.getCount());
    }
    
    @Test
    public void testNanosRecording() {
        LatencyRecorder recorder = new LatencyRecorder(60000, 3);
        
        // Record 10ms in nanoseconds
        recorder.recordNanos(10_000_000L);
        
        double mean = recorder.getMean();
        assertEquals("Mean should be ~10ms", 10.0, mean, 1.0);
    }
    
    @Test
    public void testHighValueClamping() {
        LatencyRecorder recorder = new LatencyRecorder(1000, 3);  // Max 1 second
        
        // Record value exceeding max
        recorder.record(5000.0);  // 5 seconds
        
        // Should be clamped to max trackable value
        double max = recorder.getMax();
        assertTrue("Max should be clamped to ~1000ms", max <= 1100);
    }
}
