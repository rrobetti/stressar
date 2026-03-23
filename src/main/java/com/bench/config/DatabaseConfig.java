package com.bench.config;

import java.util.Properties;

/**
 * Database connection configuration.
 */
public class DatabaseConfig {
    private String jdbcUrl;
    private String username;
    private String password;
    private Properties properties;

    /** Optional SSL/TLS settings applied to every connection. */
    private SslConfig ssl;

    public DatabaseConfig() {
        this.properties = new Properties();
        this.ssl = new SslConfig();
    }

    public DatabaseConfig(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.properties = new Properties();
        this.ssl = new SslConfig();
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

    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public SslConfig getSsl() {
        return ssl;
    }

    public void setSsl(SslConfig ssl) {
        this.ssl = ssl;
    }

    /**
     * Returns a merged {@link Properties} object that combines
     * {@link #getProperties()} with any SSL properties derived from
     * {@link #getSsl()}.  SSL properties take precedence.
     *
     * @return merged properties (never {@code null})
     */
    public Properties getMergedProperties() {
        Properties merged = new Properties();
        merged.putAll(properties);
        if (ssl != null) {
            merged.putAll(ssl.buildSslProperties());
        }
        return merged;
    }
}
