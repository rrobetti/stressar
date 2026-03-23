package com.bench.config;

/**
 * SSL/TLS mode for database connections.
 *
 * <p>Maps directly to the PostgreSQL JDBC {@code sslmode} parameter.
 * Choose the strictest mode your infrastructure supports to maximize
 * security while keeping the benchmark representative of production.</p>
 *
 * <ul>
 *   <li>{@link #DISABLE}     – plaintext only (no SSL handshake, lowest overhead)</li>
 *   <li>{@link #ALLOW}       – use plaintext, fall back to SSL if server requires it</li>
 *   <li>{@link #PREFER}      – try SSL first, fall back to plaintext (PostgreSQL JDBC default)</li>
 *   <li>{@link #REQUIRE}     – SSL required; server certificate is NOT verified</li>
 *   <li>{@link #VERIFY_CA}   – SSL required; server certificate is verified against the CA</li>
 *   <li>{@link #VERIFY_FULL} – SSL required; server hostname is also verified (recommended for production)</li>
 * </ul>
 */
public enum SslMode {

    /** No SSL – plaintext connections only. */
    DISABLE,

    /** Use plaintext; upgrade to SSL only when the server demands it. */
    ALLOW,

    /** Try SSL first; fall back to plaintext when the server does not support it. */
    PREFER,

    /** Require SSL; do not verify the server certificate (protects against eavesdropping only). */
    REQUIRE,

    /** Require SSL and verify the server certificate against a trusted CA. */
    VERIFY_CA,

    /** Require SSL, verify the server certificate, and match the server hostname (strongest). */
    VERIFY_FULL;

    /**
     * Returns the lowercase string value used by the PostgreSQL JDBC driver
     * (e.g. {@code "verify-full"}).
     */
    public String toJdbcValue() {
        return name().toLowerCase().replace('_', '-');
    }
}
