package com.bench.config;

import com.zaxxer.hikari.HikariConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConnectionTimeoutConfigTest {

    @Test
    public void hikariProviderConfigUsesConfiguredConnectionTimeout() {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setJdbcUrl("jdbc:postgresql://localhost:5432/benchdb");
        dbConfig.setUsername("benchuser");
        dbConfig.setPassword("benchpass");

        HikariConfig config = HikariProvider.getHikariConfig(dbConfig, 7, 12345);

        assertEquals(12345, config.getConnectionTimeout());
    }

    @Test
    public void pgbouncerProviderConfigUsesConfiguredConnectionTimeout() {
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setJdbcUrl("jdbc:postgresql://localhost:6432/benchdb");
        dbConfig.setUsername("benchuser");
        dbConfig.setPassword("benchpass");

        HikariConfig config = PgbouncerProvider.getHikariConfig(dbConfig, 9, 23456);

        assertEquals(23456, config.getConnectionTimeout());
        assertEquals("org.postgresql.Driver", config.getDriverClassName());
    }
}
