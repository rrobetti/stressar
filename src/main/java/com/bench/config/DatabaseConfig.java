package com.bench.config;

import java.util.Properties;

/**
 * Database connection configuration.
 */
public class DatabaseConfig {
    private String jdbcUrl;
    private String username;
    private String password;
    private final Properties properties;

    public DatabaseConfig() {
        this.properties = new Properties();
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Properties getProperties() {
        return properties;
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }
}
