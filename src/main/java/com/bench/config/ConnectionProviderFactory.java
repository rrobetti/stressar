package com.bench.config;

/**
 * Factory for creating ConnectionProvider instances based on configuration.
 */
public class ConnectionProviderFactory {
    
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
                return new OjpProvider(dbConfig);
                
            case PGBOUNCER:
                return new PgbouncerProvider(dbConfig);
                
            default:
                throw new IllegalArgumentException("Unknown connection mode: " + mode);
        }
    }
}
