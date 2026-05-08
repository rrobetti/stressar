# Installing OJP Server

OJP Server (Open JDBC Proxy) is a gRPC-based connection pool server used in the **T4 scenario**
as an alternative to the pgBouncer + HAProxy stack. It is installed on the **PROXY** nodes.

> The OJP JDBC Driver (installed on the **Load Generator**) is covered separately in
> [OJP_JDBC_DRIVER.md](OJP_JDBC_DRIVER.md).

Both artefacts are published to **Maven Central** — no source checkout or build tools are needed.

**Current release:** `0.4.10-beta` &nbsp;|&nbsp; **Server gRPC port:** `1059`

| Artifact | Maven Central URL |
|---|---|
| OJP Server (shaded) | <https://repo1.maven.org/maven2/org/openjproxy/ojp-server/0.4.10-beta/ojp-server-0.4.10-beta-shaded.jar> |
| OJP JDBC Driver | <https://repo1.maven.org/maven2/org/openjproxy/ojp-jdbc-driver/0.4.10-beta/ojp-jdbc-driver-0.4.10-beta.jar> |

---

## Table of Contents

- [Prerequisites](#prerequisites)
- [Download OJP server JAR](#download-ojp-server-jar)
- [Download PostgreSQL native JDBC driver](#download-postgresql-native-jdbc-driver)
- [Start OJP server](#start-ojp-server)
- [Verify installation](#verify-installation)
- [Further reading](#further-reading)

---

## Prerequisites

- **Java 24 or higher** (OJP Server requirement) — see [JAVA.md](JAVA.md)
- **PostgreSQL 12+** must be running and accessible from each proxy node — see [POSTGRESQL.md](POSTGRESQL.md)

> The OJP JDBC Driver (load-generator side) requires **Java 11 or higher** — see
> [OJP_JDBC_DRIVER.md](OJP_JDBC_DRIVER.md).

---

## Download OJP server JAR

Run the following commands on each of **PROXY-1**, **PROXY-2**, and **PROXY-3**.

The server is distributed as a **shaded** (self-contained, ~20 MB) JAR that bundles all
server-side dependencies except the database drivers (downloaded separately below).

```bash
# Create the OJP directories
sudo mkdir -p /opt/ojp/bin /opt/ojp/ojp-libs

# Download the self-contained server JAR
sudo curl -L \
  https://repo1.maven.org/maven2/org/openjproxy/ojp-server/0.4.10-beta/ojp-server-0.4.10-beta-shaded.jar \
  -o /opt/ojp/bin/ojp-server-0.4.10-beta-shaded.jar
```

---

## Download PostgreSQL native JDBC driver

OJP Server **does not bundle database drivers**. Place the driver JAR(s) in an `ojp-libs`
directory before starting the server.

### Option A — use the OJP helper script (downloads H2, PostgreSQL, MySQL, MariaDB)

```bash
curl -LO https://raw.githubusercontent.com/Open-J-Proxy/ojp/main/ojp-server/download-drivers.sh
sudo bash download-drivers.sh /opt/ojp/ojp-libs
```

### Option B — download the PostgreSQL driver only

```bash
sudo curl -L \
  https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar \
  -o /opt/ojp/ojp-libs/postgresql-42.7.8.jar
```

Verify the driver is in place:

```bash
ls -lh /opt/ojp/ojp-libs/
```

---

## Start OJP server

OJP Server is configured entirely through **JVM system properties** — there is no separate
configuration file. The most important properties are:

| JVM property | Default | Description |
|---|---|---|
| `ojp.server.port` | `1059` | gRPC server port |
| `ojp.libs.path` | `./ojp-libs` | Directory containing the JDBC driver JARs |
| `ojp.server.threadPoolSize` | `200` | gRPC thread pool size |
| `user.timezone` | — | **Always set to `UTC`** for correct date/time handling |

Repeat on **PROXY-1**, **PROXY-2**, and **PROXY-3**.

### Foreground (testing / debugging)

```bash
java -Duser.timezone=UTC \
     -Dojp.libs.path=/opt/ojp/ojp-libs \
     -jar /opt/ojp/bin/ojp-server-0.4.10-beta-shaded.jar
```

### Background (benchmark runs)

```bash
nohup java -Duser.timezone=UTC \
     -Dojp.libs.path=/opt/ojp/ojp-libs \
     -jar /opt/ojp/bin/ojp-server-0.4.10-beta-shaded.jar \
     > /var/log/ojp-server.log 2>&1 &
```

The server binds to port **1059** (gRPC). You should see output similar to:

```
[main] INFO  GrpcServer - Starting OJP gRPC Server on port 1059
[main] INFO  GrpcServer - OJP gRPC Server started successfully and awaiting termination
```

---

## Verify installation

```bash
# Confirm the server is listening on port 1059 (gRPC)
ss -tlnp | grep 1059
```

Expected output includes a line showing a process listening on `0.0.0.0:1059`.

Repeat on all three proxy nodes before proceeding to the JDBC driver setup.

---

## Further reading

- OJP project: <https://github.com/Open-J-Proxy/ojp>
- OJP Server configuration reference: <https://github.com/Open-J-Proxy/ojp/blob/main/documents/configuration/ojp-server-configuration.md>
- OJP Runnable JAR guide: <https://github.com/Open-J-Proxy/ojp/blob/main/documents/runnable-jar/README.md>
- OJP JDBC Driver setup: [OJP_JDBC_DRIVER.md](OJP_JDBC_DRIVER.md)
- PostgreSQL driver comparison: [RATIONALE.md](../RATIONALE.md)

---

*Back to [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
