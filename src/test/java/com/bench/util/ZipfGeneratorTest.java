package com.bench.util;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for ZipfGenerator.
 */
public class ZipfGeneratorTest {
    
    @Test
    public void testDeterministicGeneration() {
        // Same seed should produce same sequence
        ZipfGenerator gen1 = new ZipfGenerator(1000, 1.1, 42L);
        ZipfGenerator gen2 = new ZipfGenerator(1000, 1.1, 42L);
        
        for (int i = 0; i < 100; i++) {
            assertEquals(gen1.next(), gen2.next());
        }
    }
    
    @Test
    public void testRangeConstraints() {
        ZipfGenerator gen = new ZipfGenerator(100, 1.1, 42L);
        
        // Generate many values and verify all are in range
        for (int i = 0; i < 1000; i++) {
            int value = gen.next();
            assertTrue("Value should be >= 1", value >= 1);
            assertTrue("Value should be <= 100", value <= 100);
        }
    }
    
    @Test
    public void testSkewedDistribution() {
        // With Zipf alpha=1.1, lower values should be more frequent
        ZipfGenerator gen = new ZipfGenerator(100, 1.1, 42L);
        
        int[] counts = new int[101];
        int samples = 10000;
        
        for (int i = 0; i < samples; i++) {
            counts[gen.next()]++;
        }
        
        // First value should be more frequent than 50th value
        assertTrue("Value 1 should be more frequent than value 50",
                  counts[1] > counts[50]);
        
        // First value should be more frequent than 100th value
        assertTrue("Value 1 should be more frequent than value 100",
                  counts[1] > counts[100]);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidNumElements() {
        new ZipfGenerator(0, 1.1, 42L);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidAlpha() {
        new ZipfGenerator(100, -1.0, 42L);
    }
}
