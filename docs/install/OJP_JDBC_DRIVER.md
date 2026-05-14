# Installing OJP JDBC Driver

The OJP JDBC Driver is a Type 3 JDBC driver that routes SQL over gRPC to the OJP Server,
providing transparent server-side connection pooling and — in multi-node deployments — automatic
client-side load balancing. It is installed on the **Load Generator (LG)** machine (and any
application tier nodes if used).

> Ensure the OJP Server is already running on the proxy nodes before continuing —
> see [OJP.md](OJP.md).

**Current release:** `0.4.14-beta` &nbsp;|&nbsp; **Minimum Java:** `11`

**Maven Central coordinates:** `org.openjproxy:ojp-jdbc-driver:0.4.14-beta`

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Add to benchmark tool classpath](#add-to-benchmark-tool-classpath)
- [JDBC URL syntax](#jdbc-url-syntax)
- [Client-side configuration (ojp.properties)](#client-side-configuration-ojpproperties)
- [Verify connectivity](#verify-connectivity)
- [Further reading](#further-reading)

---

## Prerequisites

- **Java 11 or higher** — see [JAVA.md](JAVA.md)
- **OJP Server** running on proxy nodes (gRPC port 1059) — see [OJP.md](OJP.md)
- **PostgreSQL client tools** for connectivity testing (install as needed):

  ```bash
  # Ubuntu / Debian
  sudo apt install postgresql-client

  # RHEL / CentOS / Fedora
  sudo dnf install postgresql
  ```

---

## Add to benchmark tool classpath

The OJP JDBC driver is already declared as a dependency in the benchmark tool's `build.gradle`
(resolved from Maven Central automatically):

```groovy
dependencies {
    implementation 'org.openjproxy:ojp-jdbc-driver:0.4.14-beta'
    // ... other dependencies
}
```

Build (or rebuild) the tool to pull in the driver:

```bash
cd stressar-tool
./gradlew installDist
```

See [GRADLE.md](GRADLE.md) for full build instructions.

---

## JDBC URL syntax

The OJP JDBC driver URL wraps the native database URL by embedding the OJP server address(es)
in square brackets, followed by an underscore and the actual database URL.

**Driver class:** `org.openjproxy.jdbc.Driver`

### Single node

```
jdbc:ojp[<PROXY_IP>:1059]_postgresql://<DB_IP>:5432/benchdb
```

### Multi-node — T4 scenario (three OJP servers, client-side load balancing)

```
jdbc:ojp[<PROXY1_IP>:1059,<PROXY2_IP>:1059,<PROXY3_IP>:1059]_postgresql://<DB_IP>:5432/benchdb
```

The driver distributes new connections across the listed proxy servers automatically using
load-aware (least-connections) selection.

### In the benchmark configuration file (`ojp-mode.yaml`)

```yaml
database:
  jdbcUrl: "jdbc:ojp[<PROXY1_IP>:1059,<PROXY2_IP>:1059,<PROXY3_IP>:1059]_postgresql://<DB_IP>:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: OJP
```

See [`examples/ojp-mode.yaml`](../../examples/ojp-mode.yaml) for a complete benchmark
configuration including pool sharing and workload settings.

---

## Client-side configuration (ojp.properties)

Create an `ojp.properties` file in the application classpath (e.g., `src/main/resources/`) to
configure client-side driver behaviour:

```properties
# Connection pool settings (applied per datasource on the server side)
ojp.connection.pool.maximumPoolSize=20
ojp.connection.pool.minimumIdle=5
ojp.connection.pool.connectionTimeout=15000
ojp.connection.pool.idleTimeout=600000
ojp.connection.pool.maxLifetime=1800000

# Multi-node: load-aware server selection (default: true)
ojp.loadaware.selection.enabled=true

# Multi-node: retry settings
ojp.multinode.retryAttempts=-1
ojp.multinode.retryDelayMs=5000
```

> In the benchmark tool, pool settings are configured in the YAML config file under the `ojp:`
> block and passed as driver properties automatically — see
> [`examples/ojp-mode.yaml`](../../examples/ojp-mode.yaml).

---

## Verify connectivity

From the **Load Generator (LG)** machine, confirm each OJP server's gRPC port is reachable:

```bash
# Check that port 1059 is open on each proxy node
nc -zv <PROXY1_IP> 1059
nc -zv <PROXY2_IP> 1059
nc -zv <PROXY3_IP> 1059
```

Each command should report `Connection to <IP> 1059 port [tcp/*] succeeded!`.

To verify the full path from Load Generator through OJP to PostgreSQL, use `psql` to connect
**directly to PostgreSQL** (bypassing OJP) and confirm the database is reachable:

```bash
psql -h <DB_IP> -p 5432 -U benchuser -d benchdb -c "SELECT 1;"
```

> `psql` is part of the `postgresql-client` package (see [Prerequisites](#prerequisites)).

---

## Further reading

- OJP JDBC driver configuration: <https://github.com/Open-J-Proxy/ojp/blob/main/documents/configuration/ojp-jdbc-configuration.md>
- OJP multinode configuration: <https://github.com/Open-J-Proxy/ojp/blob/main/documents/multinode/README.md>
- OJP artifacts on Maven Central: <https://central.sonatype.com/search?q=org.openjproxy>
- OJP Server setup: [OJP.md](OJP.md)

---

*Back to [OJP.md](OJP.md) | [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
