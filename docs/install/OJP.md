# Installing OJP (Open JDBC Pooler)

OJP (Open JDBC Pooler) is a server-side PostgreSQL connection pooler with a built-in client-side
load-balancing JDBC driver. It is used in the **T4 scenario** of this benchmark as an alternative
to pgBouncer + HAProxy.

OJP's key advantage is that its JDBC driver supports a **multi-host URL**, which eliminates the
need for an external load balancer (HAProxy). The driver distributes new connections across
multiple OJP server instances automatically.

Both the OJP server and the OJP JDBC driver are published to **Maven Central** and can be
downloaded as ready-to-run JARs — no build step is required.

**Current release:** `0.4.0-beta`

| Artifact | Maven Central URL |
|---|---|
| OJP Server | <https://repo1.maven.org/maven2/org/openjproxy/ojp-server/0.4.0-beta/ojp-server-0.4.0-beta.jar> |
| OJP JDBC Driver | <https://repo1.maven.org/maven2/org/openjproxy/ojp-jdbc-driver/0.4.0-beta/ojp-jdbc-driver-0.4.0-beta.jar> |

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Download OJP server JAR](#download-ojp-server-jar)
- [Download OJP JDBC driver JAR](#download-ojp-jdbc-driver-jar)
- [Verify installation](#verify-installation)
- [Configuration for benchmarking](#configuration-for-benchmarking)
- [Start OJP server](#start-ojp-server)
- [Add OJP JDBC driver to the benchmark tool](#add-ojp-jdbc-driver-to-the-benchmark-tool)
- [Verify connectivity](#verify-connectivity)
- [Benchmark JDBC URL syntax](#benchmark-jdbc-url-syntax)

---

## Prerequisites

- **Java 11+** — see [JAVA.md](JAVA.md)
- **PostgreSQL 12+** must be running and accessible from the OJP host — see [POSTGRESQL.md](POSTGRESQL.md)

No build tools are required; the JARs are downloaded directly from Maven Central.

---

## Download OJP server JAR

Run the following commands on each of **PROXY-1**, **PROXY-2**, and **PROXY-3**:

```bash
# Create a directory for OJP
sudo mkdir -p /opt/ojp/bin

# Download the OJP server JAR
sudo curl -L \
  https://repo1.maven.org/maven2/org/openjproxy/ojp-server/0.4.0-beta/ojp-server-0.4.0-beta.jar \
  -o /opt/ojp/bin/ojp-server.jar

# Make it executable via a wrapper script
sudo tee /usr/local/bin/ojp-server > /dev/null <<'EOF'
#!/bin/sh
exec java $JAVA_OPTS -jar /opt/ojp/bin/ojp-server.jar "$@"
EOF
sudo chmod +x /usr/local/bin/ojp-server
```

---

## Download OJP JDBC driver JAR

Run the following command on the **Load Generator (LG)** machine (and APP if used):

```bash
# Create a lib directory inside the benchmark tool checkout
mkdir -p /path/to/ojp-performance-tester-tool/lib

# Download the OJP JDBC driver JAR
curl -L \
  https://repo1.maven.org/maven2/org/openjproxy/ojp-jdbc-driver/0.4.0-beta/ojp-jdbc-driver-0.4.0-beta.jar \
  -o /path/to/ojp-performance-tester-tool/lib/ojp-jdbc-driver-0.4.0-beta.jar
```

---

## Verify installation

```bash
# Confirm the server JAR is present and executable
ojp-server --version

# Confirm the JDBC driver JAR is present
ls -lh /path/to/ojp-performance-tester-tool/lib/ojp-jdbc-driver-0.4.0-beta.jar
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
# Create config directory
sudo mkdir -p /etc/ojp

# Edit /etc/ojp/ojp.yaml (see Configuration section above)

# Start the server in the foreground (for debugging)
ojp-server --config /etc/ojp/ojp.yaml

# Or run directly with the JAR
java -jar /opt/ojp/bin/ojp-server.jar --config /etc/ojp/ojp.yaml

# Verify it is accepting connections
pg_isready -h 127.0.0.1 -p 5432
```

---

## Add OJP JDBC driver to the benchmark tool

The OJP JDBC driver JAR (downloaded above) must be on the classpath when the benchmark tool
runs in OJP mode.

Add it as a `files()` dependency in `build.gradle` before building the tool:

```groovy
dependencies {
    implementation files('lib/ojp-jdbc-driver-0.4.0-beta.jar')
    // ... other dependencies
}
```

Then rebuild:

```bash
cd ojp-performance-tester-tool
./gradlew installDist
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

- OJP artifacts on Maven Central: <https://central.sonatype.com/search?q=org.openjproxy>
- OJP JDBC driver: consult the driver repository for URL syntax and driver properties
- PostgreSQL JDBC driver comparison: [RATIONALE.md](../RATIONALE.md)

---

*Back to [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
