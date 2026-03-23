package com.bench.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection provider for OJP (Open JDBC Pooler).
 * 
 * IMPORTANT: OJP does NOT use client-side connection pooling.
 * The application creates virtual JDBC connection handles that map
 * to a server-side pool managed by OJP.
 * 
 * Pool configuration is passed via OJP JDBC driver properties
 * (e.g., ojp.maxConnections) and applied on the OJP server side.
 */
public class OjpProvider implements ConnectionProvider {
    private static final Logger logger = LoggerFactory.getLogger(OjpProvider.class);
    
    private final DatabaseConfig dbConfig;
    private final OjpConfig ojpConfig;
    private final Properties ojpProperties;
    private final int allocatedMaxConnections;
    
    // Metrics for virtual connections
    private final AtomicLong virtualConnectionsOpened = new AtomicLong(0);
    private final AtomicInteger virtualConnectionsCurrent = new AtomicInteger(0);
    private final AtomicInteger virtualConnectionsMaxConcurrent = new AtomicInteger(0);
    
    private volatile boolean closed = false;
    
    public OjpProvider(DatabaseConfig dbConfig, OjpConfig ojpConfig, int allocatedMaxConnections) {
        this.dbConfig = dbConfig;
        this.ojpConfig = ojpConfig;
        this.allocatedMaxConnections = allocatedMaxConnections;
        
        // Build OJP properties with allocated max connections
        this.ojpProperties = ojpConfig.buildOjpProperties(
            dbConfig.getUsername(), 
            dbConfig.getPassword()
        );
        
        // Override maxConnections with calculated allocation
        ojpProperties.setProperty("ojp.maxConnections", String.valueOf(allocatedMaxConnections));
        
        // Merge PostgreSQL-side SSL properties so that the OJP server uses
        // SSL when connecting to the backend PostgreSQL instance.
        // These are passed as standard JDBC properties; the OJP driver
        // forwards them to the underlying PostgreSQL connection.
        if (dbConfig.getSsl() != null && dbConfig.getSsl().isEnabled()) {
            ojpProperties.putAll(dbConfig.getSsl().buildSslProperties());
        }
        
        logger.info("Initialized OJP provider (NO client-side pooling)");
        logger.info("  Virtual connection mode: {}", ojpConfig.getVirtualConnectionMode());
        logger.info("  Pool sharing: {}", ojpConfig.getPoolSharing());
        logger.info("  Server-side pool maxConnections: {}", allocatedMaxConnections);
        if (dbConfig.getSsl() != null && dbConfig.getSsl().isEnabled()) {
            logger.info("  Backend SSL: {}", dbConfig.getSsl().toLogString());
        }
        logger.info("  OJP properties: {}", ojpConfig.getPropertiesForLogging());
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("OjpProvider is closed");
        }
        
        // Create a new virtual JDBC connection via DriverManager
        // This does NOT create a real DB connection - it's a virtual handle
        // The OJP driver will map this to the server-side pool
        Connection conn = DriverManager.getConnection(dbConfig.getJdbcUrl(), ojpProperties);
        
        // Track virtual connection metrics
        virtualConnectionsOpened.incrementAndGet();
        int current = virtualConnectionsCurrent.incrementAndGet();
        
        // Update max concurrent if needed
        int currentMax = virtualConnectionsMaxConcurrent.get();
        while (current > currentMax) {
            if (virtualConnectionsMaxConcurrent.compareAndSet(currentMax, current)) {
                break;
            }
            currentMax = virtualConnectionsMaxConcurrent.get();
        }
        
        // Wrap connection to track when it's closed
        return new VirtualConnectionWrapper(conn, this);
    }
    
    /**
     * Called when a virtual connection is closed.
     */
    void onConnectionClosed() {
        virtualConnectionsCurrent.decrementAndGet();
    }
    
    @Override
    public DataSource getDataSource() {
        // OJP does not use DataSource - connections are obtained directly
        throw new UnsupportedOperationException(
            "OJP mode does not use DataSource. Use getConnection() directly.");
    }
    
    @Override
    public String getModeName() {
        return "OJP";
    }
    
    @Override
    public int getPoolSize() {
        // Return the server-side pool size, not client-side
        return allocatedMaxConnections;
    }
    
    public long getVirtualConnectionsOpened() {
        return virtualConnectionsOpened.get();
    }
    
    public int getVirtualConnectionsCurrent() {
        return virtualConnectionsCurrent.get();
    }
    
    public int getVirtualConnectionsMaxConcurrent() {
        return virtualConnectionsMaxConcurrent.get();
    }
    
    public OjpConfig getOjpConfig() {
        return ojpConfig;
    }
    
    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        
        closed = true;
        logger.info("Closed OJP provider");
        logger.info("  Virtual connections opened: {}", virtualConnectionsOpened.get());
        logger.info("  Max concurrent virtual connections: {}", virtualConnectionsMaxConcurrent.get());
    }
    
    /**
     * Wrapper to track when virtual connections are closed.
     */
    private static class VirtualConnectionWrapper extends ConnectionWrapper {
        private final OjpProvider provider;
        private boolean closed = false;
        
        public VirtualConnectionWrapper(Connection delegate, OjpProvider provider) {
            super(delegate);
            this.provider = provider;
        }
        
        @Override
        public void close() throws SQLException {
            if (!closed) {
                closed = true;
                provider.onConnectionClosed();
                super.close();
            }
        }
    }
}
