package com.bench.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating ConnectionProvider instances based on configuration.
 */
public class ConnectionProviderFactory {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionProviderFactory.class);
    
    public static ConnectionProvider createProvider(BenchmarkConfig config) {
        DatabaseConfig dbConfig = config.getDatabase();
        ConnectionMode mode = config.getConnectionMode();
        
        switch (mode) {
            case HIKARI_DIRECT:
                return new HikariProvider(dbConfig, config.getPoolSize(), false);
                
            case HIKARI_DISCIPLINED:
                int disciplinedPoolSize = config.calculateDisciplinedPoolSize();
                return new HikariProvider(dbConfig, disciplinedPoolSize, true);
                
            case OJP:
                // Validate OJP configuration
                config.validateOjpConfig();
                
                // Calculate server-side pool allocation
                int ojpAllocation = config.calculateOjpAllocation();
                
                // Set pool key for PER_INSTANCE mode
                OjpConfig ojpConfig = config.getOjpConfig();
                if (ojpConfig.getPoolSharing() == OjpPoolSharing.PER_INSTANCE) {
                    String poolKey = "instance_" + config.getInstanceId();
                    ojpConfig.setPoolKey(poolKey);
                    logger.info("OJP PER_INSTANCE mode: setting poolKey={}", poolKey);
                }
                
                return new OjpProvider(dbConfig, ojpConfig, ojpAllocation);
                
            case PGBOUNCER:
                return new PgbouncerProvider(dbConfig, config.getPoolSize());
                
            default:
                throw new IllegalArgumentException("Unknown connection mode: " + mode);
        }
    }
}
