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
        
        // SHARED mode: the pool gets the full budget (bench_replica_count not used)
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
        
        // PER_INSTANCE mode: each OJP server gets the full budget as maxConnections.
        // bench_replica_count (replicas=10) is load-gen JVMs, not OJP servers,
        // so it must not reduce the per-instance pool size.
        int allocation = config.calculateOjpAllocation();
        assertEquals(100, allocation);
    }
    
    @Test
    public void testOjpAllocationPerInstanceWithHighReplicaCount() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setConnectionMode(ConnectionMode.OJP);
        config.setDbConnectionBudget(48);
        config.setReplicas(16);   // 16 load-gen JVMs
        
        OjpConfig ojpConfig = config.getOjpConfig();
        ojpConfig.setPoolSharing(OjpPoolSharing.PER_INSTANCE);
        
        // Each OJP server instance should get 48 connections (the full budget),
        // NOT ceil(48/16)=3. bench_replica_count is irrelevant to OJP pool sizing.
        int allocation = config.calculateOjpAllocation();
        assertEquals(48, allocation);
    }
    
    @Test
    public void testOjpAllocationMinimum() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setConnectionMode(ConnectionMode.OJP);
        config.setDbConnectionBudget(0);
        config.setReplicas(10);
        
        OjpConfig ojpConfig = config.getOjpConfig();
        ojpConfig.setPoolSharing(OjpPoolSharing.PER_INSTANCE);
        
        // Budget of 0 is clamped to 1
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
