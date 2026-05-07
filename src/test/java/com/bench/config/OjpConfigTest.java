package com.bench.config;

import org.junit.Test;
import static org.junit.Assert.*;

public class OjpConfigTest {
    
    @Test
    public void testOjpAllocationShared() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setConnectionMode(ConnectionMode.OJP);
        config.setDbConnectionBudget(100);
        config.setReplicas(10);
        
        OjpConfig ojpConfig = config.getOjpConfig();
        ojpConfig.setPoolSharing(OjpPoolSharing.SHARED);
        
        // SHARED mode: all replicas share one pool = full budget
        int allocation = config.calculateOjpAllocation();
        assertEquals(100, allocation);
    }
    
    @Test
    public void testOjpAllocationPerInstance() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setConnectionMode(ConnectionMode.OJP);
        config.setDbConnectionBudget(100);
        config.setReplicas(10);
        
        OjpConfig ojpConfig = config.getOjpConfig();
        ojpConfig.setPoolSharing(OjpPoolSharing.PER_INSTANCE);
        
        // PER_INSTANCE mode: budget divided among replicas
        int allocation = config.calculateOjpAllocation();
        assertEquals(10, allocation);
    }
    
    @Test
    public void testOjpAllocationPerInstanceRounding() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setConnectionMode(ConnectionMode.OJP);
        config.setDbConnectionBudget(100);
        config.setReplicas(16);
        
        OjpConfig ojpConfig = config.getOjpConfig();
        ojpConfig.setPoolSharing(OjpPoolSharing.PER_INSTANCE);
        
        // PER_INSTANCE mode: 100 / 16 = 6.25 -> ceil to 7
        int allocation = config.calculateOjpAllocation();
        assertEquals(7, allocation);
    }
    
    @Test
    public void testOjpAllocationPerInstanceMinimum() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setConnectionMode(ConnectionMode.OJP);
        config.setDbConnectionBudget(10);
        config.setReplicas(100);
        
        OjpConfig ojpConfig = config.getOjpConfig();
        ojpConfig.setPoolSharing(OjpPoolSharing.PER_INSTANCE);
        
        // PER_INSTANCE mode: 10 / 100 = 0.1 -> clamped to 1
        int allocation = config.calculateOjpAllocation();
        assertEquals(1, allocation);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testOjpValidationRejectsPoolSize() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setConnectionMode(ConnectionMode.OJP);
        config.setPoolSize(50);  // Should not be allowed for OJP
        
        config.validateOjpConfig();
    }
    
    @Test
    public void testOjpPropertiesBuilding() {
        OjpConfig ojpConfig = new OjpConfig();
        ojpConfig.setMinConnections(5);
        ojpConfig.setMaxConnections(100);
        ojpConfig.setConnectionTimeoutMs(30000);
        ojpConfig.setPoolKey("test-pool");
        
        java.util.Properties props = ojpConfig.buildOjpProperties("user", "pass");
        
        assertEquals("user", props.getProperty("user"));
        assertEquals("pass", props.getProperty("password"));
        assertEquals("5", props.getProperty("ojp.minConnections"));
        assertEquals("100", props.getProperty("ojp.maxConnections"));
        assertEquals("30000", props.getProperty("ojp.connectionTimeoutMs"));
        assertEquals("test-pool", props.getProperty("ojp.poolKey"));
    }
    
    @Test
    public void testOjpPropertiesForLogging() {
        OjpConfig ojpConfig = new OjpConfig();
        ojpConfig.setMinConnections(5);
        ojpConfig.setMaxConnections(100);
        ojpConfig.setPoolKey("test-pool");
        ojpConfig.addProperty("customProp", "customValue");
        
        java.util.Map<String, String> logProps = ojpConfig.getPropertiesForLogging();
        
        // Password should not be in logging properties
        assertFalse(logProps.containsKey("password"));
        
        // OJP properties should be present
        assertEquals("5", logProps.get("ojp.minConnections"));
        assertEquals("100", logProps.get("ojp.maxConnections"));
        assertEquals("test-pool", logProps.get("ojp.poolKey"));
        assertEquals("customValue", logProps.get("customProp"));
    }
}
