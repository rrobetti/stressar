package com.bench.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Configuration for OJP (Open JDBC Pooler) mode.
 * OJP does NOT use client-side connection pooling libraries.
 * Instead, it uses virtual JDBC connections that map to a server-side pool
 * configured via OJP JDBC driver properties.
 */
public class OjpConfig {
    private OjpVirtualConnectionMode virtualConnectionMode = OjpVirtualConnectionMode.PER_WORKER;
    private OjpPoolSharing poolSharing = OjpPoolSharing.SHARED;
    
    // Server-side pool configuration (passed as OJP properties)
    private Integer minConnections;
    private Integer maxConnections;
    private Integer connectionTimeoutMs;
    private Integer idleTimeoutMs;
    private Integer maxLifetimeMs;
    private Integer queueLimit;
    private Integer slowQueryThresholdMs;
    
    // Pool identity for isolation
    private String poolKey;
    
    // Additional OJP-specific properties
    private Map<String, String> additionalProperties = new HashMap<>();
    
    public OjpVirtualConnectionMode getVirtualConnectionMode() {
        return virtualConnectionMode;
    }
    
    public void setVirtualConnectionMode(OjpVirtualConnectionMode virtualConnectionMode) {
        this.virtualConnectionMode = virtualConnectionMode;
    }
    
    public OjpPoolSharing getPoolSharing() {
        return poolSharing;
    }
    
    public void setPoolSharing(OjpPoolSharing poolSharing) {
        this.poolSharing = poolSharing;
    }
    
    public Integer getMinConnections() {
        return minConnections;
    }
    
    public void setMinConnections(Integer minConnections) {
        this.minConnections = minConnections;
    }
    
    public Integer getMaxConnections() {
        return maxConnections;
    }
    
    public void setMaxConnections(Integer maxConnections) {
        this.maxConnections = maxConnections;
    }
    
    public Integer getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }
    
    public void setConnectionTimeoutMs(Integer connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }
    
    public Integer getIdleTimeoutMs() {
        return idleTimeoutMs;
    }
    
    public void setIdleTimeoutMs(Integer idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }
    
    public Integer getMaxLifetimeMs() {
        return maxLifetimeMs;
    }
    
    public void setMaxLifetimeMs(Integer maxLifetimeMs) {
        this.maxLifetimeMs = maxLifetimeMs;
    }
    
    public Integer getQueueLimit() {
        return queueLimit;
    }
    
    public void setQueueLimit(Integer queueLimit) {
        this.queueLimit = queueLimit;
    }
    
    public Integer getSlowQueryThresholdMs() {
        return slowQueryThresholdMs;
    }
    
    public void setSlowQueryThresholdMs(Integer slowQueryThresholdMs) {
        this.slowQueryThresholdMs = slowQueryThresholdMs;
    }
    
    public String getPoolKey() {
        return poolKey;
    }
    
    public void setPoolKey(String poolKey) {
        this.poolKey = poolKey;
    }
    
    public Map<String, String> getAdditionalProperties() {
        return additionalProperties;
    }
    
    public void setAdditionalProperties(Map<String, String> additionalProperties) {
        this.additionalProperties = additionalProperties;
    }
    
    public void addProperty(String key, String value) {
        this.additionalProperties.put(key, value);
    }
    
    /**
     * Build Properties object for OJP JDBC driver.
     * Includes all configured OJP-specific properties with "ojp." prefix.
     */
    public Properties buildOjpProperties(String username, String password) {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        
        // Add OJP-specific properties
        if (minConnections != null) {
            props.setProperty("ojp.minConnections", String.valueOf(minConnections));
        }
        if (maxConnections != null) {
            props.setProperty("ojp.maxConnections", String.valueOf(maxConnections));
        }
        if (connectionTimeoutMs != null) {
            props.setProperty("ojp.connectionTimeoutMs", String.valueOf(connectionTimeoutMs));
        }
        if (idleTimeoutMs != null) {
            props.setProperty("ojp.idleTimeoutMs", String.valueOf(idleTimeoutMs));
        }
        if (maxLifetimeMs != null) {
            props.setProperty("ojp.maxLifetimeMs", String.valueOf(maxLifetimeMs));
        }
        if (queueLimit != null) {
            props.setProperty("ojp.queueLimit", String.valueOf(queueLimit));
        }
        if (slowQueryThresholdMs != null) {
            props.setProperty("ojp.slowQueryThresholdMs", String.valueOf(slowQueryThresholdMs));
        }
        if (poolKey != null) {
            props.setProperty("ojp.poolKey", poolKey);
        }
        
        // Add any additional properties
        additionalProperties.forEach((key, value) -> {
            // Ensure properties have ojp. prefix
            String fullKey = key.startsWith("ojp.") ? key : "ojp." + key;
            props.setProperty(fullKey, value);
        });
        
        return props;
    }
    
    /**
     * Get properties for logging (with password redacted).
     */
    public Map<String, String> getPropertiesForLogging() {
        Map<String, String> props = new HashMap<>();
        if (minConnections != null) props.put("ojp.minConnections", String.valueOf(minConnections));
        if (maxConnections != null) props.put("ojp.maxConnections", String.valueOf(maxConnections));
        if (connectionTimeoutMs != null) props.put("ojp.connectionTimeoutMs", String.valueOf(connectionTimeoutMs));
        if (idleTimeoutMs != null) props.put("ojp.idleTimeoutMs", String.valueOf(idleTimeoutMs));
        if (maxLifetimeMs != null) props.put("ojp.maxLifetimeMs", String.valueOf(maxLifetimeMs));
        if (queueLimit != null) props.put("ojp.queueLimit", String.valueOf(queueLimit));
        if (slowQueryThresholdMs != null) props.put("ojp.slowQueryThresholdMs", String.valueOf(slowQueryThresholdMs));
        if (poolKey != null) props.put("ojp.poolKey", poolKey);
        props.putAll(additionalProperties);
        return props;
    }
}
