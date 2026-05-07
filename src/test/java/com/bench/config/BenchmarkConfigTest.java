package com.bench.config;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for disciplined pooling calculation.
 */
public class BenchmarkConfigTest {
    
    @Test
    public void testDisciplinedPoolSizeBasic() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setDbConnectionBudget(100);
        config.setReplicas(10);
        config.setMaxPoolSizePerReplica(50);
        
        int poolSize = config.calculateDisciplinedPoolSize();
        assertEquals("100 / 10 = 10", 10, poolSize);
    }
    
    @Test
    public void testDisciplinedPoolSizeMinimum() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setDbConnectionBudget(10);
        config.setReplicas(100);
        config.setMaxPoolSizePerReplica(50);
        
        int poolSize = config.calculateDisciplinedPoolSize();
        assertEquals("Should be clamped to minimum 1", 1, poolSize);
    }
    
    @Test
    public void testDisciplinedPoolSizeMaximum() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setDbConnectionBudget(1000);
        config.setReplicas(10);
        config.setMaxPoolSizePerReplica(50);
        
        int poolSize = config.calculateDisciplinedPoolSize();
        assertEquals("Should be clamped to max 50", 50, poolSize);
    }
    
    @Test
    public void testDisciplinedPoolSizeRounding() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setDbConnectionBudget(100);
        config.setReplicas(16);
        config.setMaxPoolSizePerReplica(50);
        
        int poolSize = config.calculateDisciplinedPoolSize();
        assertEquals("100 / 16 = 7 (ceiling)", 7, poolSize);
    }
    
    @Test
    public void testDisciplinedPoolSizeExactDivision() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setDbConnectionBudget(100);
        config.setReplicas(4);
        config.setMaxPoolSizePerReplica(50);
        
        int poolSize = config.calculateDisciplinedPoolSize();
        assertEquals("100 / 4 = 25", 25, poolSize);
    }
    
    @Test
    public void testDisciplinedPoolSizeZeroReplicas() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setDbConnectionBudget(100);
        config.setReplicas(0);
        config.setPoolSize(20);
        config.setMaxPoolSizePerReplica(50);
        
        int poolSize = config.calculateDisciplinedPoolSize();
        assertEquals("Should return default poolSize when replicas=0", 20, poolSize);
    }
}
