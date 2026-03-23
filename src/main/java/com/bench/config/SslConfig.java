package com.bench.config;

import java.util.Properties;

/**
 * SSL/TLS configuration for database connections.
 *
 * <p>When {@link #enabled} is {@code true} the properties returned by
 * {@link #buildSslProperties()} are merged into every connection request so
 * that both HikariCP (HIKARI_DIRECT / HIKARI_DISCIPLINED / PGBOUNCER) and the
 * OJP JDBC driver (OJP mode) use identical SSL settings.</p>
 *
 * <p><strong>Quick-start:</strong></p>
 * <pre>
 * ssl:
 *   enabled: true
 *   mode: REQUIRE
 * </pre>
 *
 * <p><strong>mTLS (mutual TLS) with certificate files:</strong></p>
 * <pre>
 * ssl:
 *   enabled: true
 *   mode: VERIFY_FULL
 *   rootCertPath: /etc/ssl/certs/ca.pem
 *   certPath:     /etc/ssl/certs/client.pem
 *   keyPath:      /etc/ssl/private/client.key
 * </pre>
 *
 * <p>See {@link SslMode} for a full description of each mode.</p>
 */
public class SslConfig {

    /** Whether SSL/TLS is enabled. Default is {@code false}. */
    private boolean enabled = false;

    /**
     * SSL enforcement/verification level.
     * Defaults to {@link SslMode#REQUIRE} when SSL is enabled.
     */
    private SslMode mode = SslMode.REQUIRE;

    /**
     * Path to the PEM-encoded client certificate file (for mutual TLS).
     * Optional – leave {@code null} when server-only authentication is sufficient.
     */
    private String certPath;

    /**
     * Path to the PEM-encoded client private key file (for mutual TLS).
     * Optional – leave {@code null} when server-only authentication is sufficient.
     */
    private String keyPath;

    /**
     * Path to the PEM-encoded root (CA) certificate used to verify the server.
     * Required when {@link SslMode#VERIFY_CA} or {@link SslMode#VERIFY_FULL} is used.
     */
    private String rootCertPath;

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SslMode getMode() {
        return mode;
    }

    public void setMode(SslMode mode) {
        this.mode = mode;
    }

    public String getCertPath() {
        return certPath;
    }

    public void setCertPath(String certPath) {
        this.certPath = certPath;
    }

    public String getKeyPath() {
        return keyPath;
    }

    public void setKeyPath(String keyPath) {
        this.keyPath = keyPath;
    }

    public String getRootCertPath() {
        return rootCertPath;
    }

    public void setRootCertPath(String rootCertPath) {
        this.rootCertPath = rootCertPath;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the {@link Properties} map that enables SSL on a PostgreSQL JDBC
     * connection.  Returns an empty map when SSL is disabled.
     *
     * <p>The properties are compatible with both the standard PostgreSQL JDBC
     * driver (used by HikariCP and PgBouncer providers) and the OJP JDBC driver
     * (which forwards them to the underlying PostgreSQL connection).</p>
     *
     * @return non-null {@link Properties} object (may be empty)
     */
    public Properties buildSslProperties() {
        Properties props = new Properties();

        if (!enabled) {
            return props;
        }

        props.setProperty("ssl", "true");
        props.setProperty("sslmode", mode.toJdbcValue());

        if (certPath != null && !certPath.isEmpty()) {
            props.setProperty("sslcert", certPath);
        }
        if (keyPath != null && !keyPath.isEmpty()) {
            props.setProperty("sslkey", keyPath);
        }
        if (rootCertPath != null && !rootCertPath.isEmpty()) {
            props.setProperty("sslrootcert", rootCertPath);
        }

        return props;
    }

    /**
     * Returns a human-readable summary of the SSL configuration (no secrets).
     *
     * @return description string suitable for logging
     */
    public String toLogString() {
        if (!enabled) {
            return "SSL disabled";
        }
        StringBuilder sb = new StringBuilder("SSL enabled, mode=").append(mode);
        if (rootCertPath != null) sb.append(", rootCert=").append(rootCertPath);
        if (certPath != null)     sb.append(", cert=").append(certPath);
        if (keyPath != null)      sb.append(", key=").append(keyPath);
        return sb.toString();
    }
}
