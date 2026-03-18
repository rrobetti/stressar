# Installing OJP (Open JDBC Pooler)

OJP (Open JDBC Pooler) is a server-side PostgreSQL connection pooler with a built-in client-side
load-balancing JDBC driver. It is used in the **T4 scenario** of this benchmark as an alternative
to pgBouncer + HAProxy.

OJP's key advantage is that its JDBC driver supports a **multi-host URL**, which eliminates the
need for an external load balancer (HAProxy). The driver distributes new connections across
multiple OJP server instances automatically.

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Clone and build OJP server](#clone-and-build-ojp-server)
- [Clone and build OJP JDBC driver](#clone-and-build-ojp-jdbc-driver)
- [Verify installation](#verify-installation)
- [Configuration for benchmarking](#configuration-for-benchmarking)
- [Start OJP server](#start-ojp-server)
- [Add OJP JDBC driver to the benchmark tool](#add-ojp-jdbc-driver-to-the-benchmark-tool)
- [Verify connectivity](#verify-connectivity)
- [Benchmark JDBC URL syntax](#benchmark-jdbc-url-syntax)

---

## Prerequisites

- **Java 11+** — see [JAVA.md](JAVA.md)
- **Gradle 7+** or Maven 3.8+ — see [GRADLE.md](GRADLE.md)
- **PostgreSQL 12+** must be running and accessible from the OJP host — see [POSTGRESQL.md](POSTGRESQL.md)

---

## Clone and build OJP server

> **Note:** OJP is a research prototype. Consult the project's own README for the authoritative
> build and deployment instructions. The steps below are illustrative and may need to be adapted.

```bash
# Clone the OJP server repository
git clone https://github.com/your-org/ojp-server.git
cd ojp-server

# Build the server
./gradlew installDist

# The server binary will be under build/install/ojp-server/bin/ojp-server
```

---

## Clone and build OJP JDBC driver

```bash
# Clone the OJP JDBC driver repository
git clone https://github.com/your-org/ojp-jdbc-driver.git
cd ojp-jdbc-driver

# Build and publish to local Maven repository
./gradlew publishToMavenLocal

# Alternatively, produce a standalone JAR
./gradlew jar
# The JAR will be at build/libs/ojp-jdbc-driver-*.jar
```

---

## Verify installation

```bash
# Start one OJP server instance (see Configuration section below for the config file)
ojp-server --config /etc/ojp/ojp.yaml

# Confirm it is listening on port 5432
ss -tlnp | grep 5432
```

---

## Configuration for benchmarking

Each OJP server instance is configured via a YAML file. Apply the following configuration
identically on **PROXY-1**, **PROXY-2**, and **PROXY-3**.

### `/etc/ojp/ojp.yaml`

```yaml
server:
  listen_address: "0.0.0.0"
  listen_port: 5432

backend:
  host: "<DB_IP>"
  port: 5432
  database: benchdb
  username: benchuser
  password: benchpass

pool:
  max_connections: 100        # Backend connections per instance; 3 × 100 = 300 total
  min_connections: 10
  connection_timeout_ms: 5000

logging:
  level: info
```

Replace `<DB_IP>` with the IP address of the PostgreSQL server.

**Key settings:**

| Setting | Value | Explanation |
|---|---|---|
| `listen_port` | `5432` | OJP presents itself as a PostgreSQL server on the standard port |
| `max_connections` | `100` | Backend connections maintained per OJP instance |
| `3 × 100 = 300` | — | Total backend connections across three instances, matching the pgBouncer T3 scenario for a fair comparison |

---

## Start OJP server

```bash
# Start the server in the foreground (for debugging)
ojp-server --config /etc/ojp/ojp.yaml

# Start as a systemd service (if a unit file is provided)
sudo systemctl start ojp-server
sudo systemctl enable ojp-server

# Verify it is accepting connections
pg_isready -h 127.0.0.1 -p 5432
```

---

## Add OJP JDBC driver to the benchmark tool

The OJP JDBC driver JAR must be on the classpath when the benchmark tool runs in OJP mode.

Place the driver JAR in the `lib/` directory of the benchmark tool before building:

```bash
cp /path/to/ojp-jdbc-driver-*.jar \
   /path/to/ojp-performance-tester-tool/lib/

cd ojp-performance-tester-tool
./gradlew installDist
```

Or add it as a `files()` dependency in `build.gradle`:

```groovy
dependencies {
    implementation files('lib/ojp-jdbc-driver-1.0.0.jar')
    // ... other dependencies
}
```

---

## Verify connectivity

From the **Load Generator (LG)** machine, verify that each OJP instance is reachable via the
standard PostgreSQL client:

```bash
psql -h <PROXY1_IP> -p 5432 -U benchuser -d benchdb -c "SELECT 1;"
psql -h <PROXY2_IP> -p 5432 -U benchuser -d benchdb -c "SELECT 1;"
psql -h <PROXY3_IP> -p 5432 -U benchuser -d benchdb -c "SELECT 1;"
```

Each command should return `1` without error.

---

## Benchmark JDBC URL syntax

When using the OJP JDBC driver the connection URL lists all three proxy hosts separated by
commas. The driver distributes connections across them automatically:

```
jdbc:ojp://<PROXY1_IP>:5432,<PROXY2_IP>:5432,<PROXY3_IP>:5432/benchdb
```

In the benchmark configuration file (`ojp-mode.yaml`):

```yaml
database:
  jdbcUrl: "jdbc:ojp://<PROXY1_IP>:5432,<PROXY2_IP>:5432,<PROXY3_IP>:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: OJP
poolSize: 20
```

Consult the OJP JDBC driver documentation for additional URL parameters and load-balancing
configuration properties.

---

## Further reading

- OJP project repository: consult the official OJP project page for the latest documentation
- OJP JDBC driver: consult the driver repository for URL syntax and driver properties
- PostgreSQL JDBC driver comparison: [RATIONALE.md](../RATIONALE.md)

---

*Back to [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
