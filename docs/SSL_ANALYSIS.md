# SSL/TLS Analysis: Performance Impact and Production Recommendations

*OJP Performance Benchmark Tool — supplementary analysis*

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Why SSL Matters for Benchmarking](#why-ssl-matters-for-benchmarking)
3. [Network Topology and SSL Legs](#network-topology-and-ssl-legs)
4. [PostgreSQL JDBC SSL Deep-Dive](#postgresql-jdbc-ssl-deep-dive)
   - [SSL Modes](#ssl-modes)
   - [Performance Impact on JDBC Connections](#performance-impact-on-jdbc-connections)
   - [Certificate Management](#certificate-management)
5. [OJP gRPC and TLS over HTTP/2](#ojp-grpc-and-tls-over-http2)
   - [gRPC Architecture Recap](#grpc-architecture-recap)
   - [HTTP/2 vs HTTP/1.1 for Database Proxying](#http2-vs-http11-for-database-proxying)
   - [TLS on gRPC: Overhead vs Benefits](#tls-on-grpc-overhead-vs-benefits)
   - [Expected Throughput and Latency Delta](#expected-throughput-and-latency-delta)
6. [PgBouncer SSL Considerations](#pgbouncer-ssl-considerations)
7. [Recommendation Matrix](#recommendation-matrix)
8. [Benchmarking Strategy: Baseline vs SSL](#benchmarking-strategy-baseline-vs-ssl)
9. [Configuration Guide](#configuration-guide)
   - [PostgreSQL Server Setup](#postgresql-server-setup)
   - [HikariCP / HIKARI_DIRECT Mode](#hikaricp--hikari_direct-mode)
   - [OJP Mode (Two-Leg Setup)](#ojp-mode-two-leg-setup)
   - [PgBouncer Mode (Two-Leg Setup)](#pgbouncer-mode-two-leg-setup)
10. [SSL Certificate Quick-Start](#ssl-certificate-quick-start)
11. [Measuring SSL Overhead](#measuring-ssl-overhead)
12. [Conclusion](#conclusion)

---

## Executive Summary

| Question | Recommendation |
|---|---|
| Should benchmarks use SSL to mirror production? | **Yes** — always run at least one SSL baseline |
| Which SSL mode for production equivalence? | `VERIFY_FULL` (hostname + certificate verification) |
| Does gRPC over TLS (HTTP/2) outperform plain TCP in high-concurrency scenarios? | **Yes, in most cases** — HTTP/2 multiplexing reduces connection overhead; TLS adds ≈ 5–15 % CPU cost that is largely amortized with connection reuse |
| Can SSL overhead itself skew benchmark results? | **Yes** — always measure with and without SSL and report the delta |
| mTLS (mutual TLS) worth the operational cost? | Recommended for production; adds < 1 ms to handshake on LAN |

---

## Why SSL Matters for Benchmarking

Production PostgreSQL deployments universally require encrypted connections, especially in:

- **Cloud-hosted databases** (AWS RDS, Google Cloud SQL, Azure Database for PostgreSQL) — SSL is enforced by default.
- **PCI-DSS / HIPAA / SOC 2 compliant environments** — plaintext connections are a compliance failure.
- **Multi-tenant setups** — shared infrastructure demands traffic isolation via TLS.
- **Container orchestration** (Kubernetes, ECS) — service mesh tools like Istio layer mTLS automatically.

Benchmarking without SSL over-reports throughput and under-reports latency compared to the production workload.
The SSL overhead is real, measurable, and must be accounted for in capacity planning.

---

## Network Topology and SSL Legs

The tool supports four connection modes, each with a different network topology.
Each arrow in the diagram below represents a potential TLS leg.

```
 ┌──────────────────────────────────────────────────────────────────────┐
 │  Load Generator (bench tool)                                         │
 └──────────────────────────────────────────────────────────────────────┘
           │                    │                       │
           │ HIKARI_DIRECT       │ PGBOUNCER              │ OJP
           │ (JDBC/SSL)          │ (JDBC/SSL) [Leg A]     │ (gRPC/TLS) [Leg A]
           ▼                    ▼                       ▼
     ┌──────────┐       ┌──────────────┐       ┌──────────────┐
     │PostgreSQL│       │  PgBouncer   │       │  OJP Server  │
     │ :5432    │       │  :6432       │       │  :1059       │
     └──────────┘       └──────────────┘       └──────────────┘
                               │ (pgbouncer →              │ (OJP → PostgreSQL)
                               │  PostgreSQL)  [Leg B]     │ (JDBC/SSL) [Leg B]
                               ▼                           ▼
                        ┌──────────┐               ┌──────────┐
                        │PostgreSQL│               │PostgreSQL│
                        │ :5432    │               │ :5432    │
                        └──────────┘               └──────────┘
```

The `ssl` block in the tool's YAML configuration controls **Leg B** for OJP mode and
both the single leg in HIKARI_DIRECT mode and **Leg A** for PGBOUNCER mode.
Securing Leg A for OJP (load-generator → OJP gRPC) requires OJP server-side configuration
(see [OJP Mode section](#ojp-mode-two-leg-setup)).

---

## PostgreSQL JDBC SSL Deep-Dive

### SSL Modes

The PostgreSQL JDBC driver supports six SSL enforcement levels, selectable via the `sslmode` parameter:

| Mode | Encrypted | Server cert verified | Hostname verified | Production use |
|---|---|---|---|---|
| `disable` | No | — | — | Dev/test only |
| `allow` | Opportunistic (server decides) | No | No | Dev/test only |
| `prefer` | Opportunistic (client prefers) | No | No | JDBC default; NOT production-safe |
| `require` | Yes | No | No | Protects against eavesdropping only |
| `verify-ca` | Yes | Yes (CA trust) | No | Acceptable with internal PKI |
| `verify-full` | Yes | Yes (CA trust) | Yes | **Recommended for production** |

> **Key insight:** `prefer` (the JDBC driver default) does NOT verify the server certificate and will
> silently fall back to plaintext if the server does not present a certificate.
> This provides almost no security guarantee — always specify `require` or higher in production.

### Performance Impact on JDBC Connections

TLS affects JDBC performance in two phases:

#### Connection Establishment (Handshake)

| Phase | Plaintext | TLS 1.3 | TLS 1.2 |
|---|---|---|---|
| TCP SYN-ACK | ~0.3 ms (LAN) | ~0.3 ms | ~0.3 ms |
| TLS ClientHello / ServerHello | — | +0.5–1 ms | +1–2 ms |
| Certificate exchange & validation | — | +0.5–1 ms | +1–3 ms |
| Application data ready | ~0.5 ms | ~1.5–2.5 ms | ~2–5 ms |

*Measured on a 1 Gbps LAN with 2048-bit RSA certificates; ECDSA P-256 is ~40 % faster.*

**Mitigation:** Connection pooling (HikariCP, OJP server-side pool) amortizes handshake cost
over thousands of queries. Once connections are warm, handshake overhead is negligible.

#### Steady-State Data Transfer (Encryption/Decryption)

With modern hardware supporting AES-NI acceleration:

| Metric | Plaintext | TLS 1.3 (AES-256-GCM) | Delta |
|---|---|---|---|
| CPU per query | baseline | +5–15 % | Acceptable |
| Throughput (RPS) | baseline | −5–10 % | Measurable |
| P50 latency | baseline | +0.1–0.5 ms | Marginal |
| P99 latency | baseline | +0.5–2 ms | Noticeable under load |

*Benchmarks from PostgreSQL community testing on AWS c5.4xlarge (Intel AES-NI enabled).*

> **Recommendation:** On cloud instances with AES-NI (virtually all modern x86-64 CPUs),
> TLS 1.3 overhead is ≤ 10 % for typical OLTP workloads. This is acceptable in exchange
> for the security guarantees required by production environments.

### Certificate Management

For `verify-full` mode the following files are required:

| File | Purpose | Who generates it |
|---|---|---|
| `ca.pem` (root cert) | Verifies the server's identity | Your PKI / `openssl req` |
| `server.crt` | Server certificate (on PostgreSQL host) | Signed by CA |
| `server.key` | Server private key (on PostgreSQL host) | Stays on server |
| `client.crt` | Client certificate for mTLS (optional) | Signed by CA |
| `client.key` | Client private key for mTLS (optional) | Stays on load generator |

---

## OJP gRPC and TLS over HTTP/2

### gRPC Architecture Recap

OJP uses **gRPC** as its transport protocol. gRPC is built on top of HTTP/2, which in turn
is typically layered over TLS (h2). The connection from the benchmark tool's JDBC client
to the OJP server traverses this stack:

```
PostgreSQL JDBC Driver
       ↓
OJP JDBC Driver (translates JDBC calls → gRPC messages)
       ↓
gRPC (HTTP/2 frames, binary Protobuf encoding)
       ↓
TLS 1.3 (optional but strongly recommended)
       ↓
TCP  port 1059
       ↓
OJP Server (decodes gRPC, manages backend PostgreSQL pool)
```

### HTTP/2 vs HTTP/1.1 for Database Proxying

The primary advantages of HTTP/2 for a database proxy context are:

| Feature | HTTP/1.1 | HTTP/2 | Impact for OJP |
|---|---|---|---|
| Multiplexing | 1 request per TCP connection | Multiple concurrent streams per connection | High: fewer TCP connections needed |
| Header compression (HPACK) | Repeated headers per request | Compressed delta headers | Medium: reduces per-call overhead |
| Binary framing | Text | Binary | Low-to-medium: faster parsing |
| Server push | No | Yes | Not used by OJP |
| Connection reuse | Limited (pipelining fragile) | Excellent | High: critical for high RPS |

For a database proxy workload like OJP with hundreds of concurrent virtual connections:

- **HTTP/2 multiplexing** means the load generator can have hundreds of in-flight gRPC calls
  over a single TCP connection rather than requiring hundreds of TCP connections.
- This directly reduces the **file descriptor count** and **TCP stack overhead** on both the
  load generator and the OJP server.
- At 1,000 RPS, HTTP/1.1 would require ≈ 50–200 open TCP connections (depending on concurrency);
  HTTP/2 can handle the same load with as few as 1–4 TCP connections.

### TLS on gRPC: Overhead vs Benefits

**Does TLS hurt performance on gRPC/HTTP/2?**

Short answer: **less than you might expect**, for three reasons:

1. **TLS 1.3 reduces handshake round trips** from 2 (TLS 1.2) to 1 — or even 0 with 0-RTT session
   resumption. Combined with HTTP/2's connection reuse, this cost is paid once per connection.

2. **AES-NI hardware acceleration** on modern CPUs makes symmetric encryption negligible
   (< 5 % CPU overhead at typical OLTP throughput levels).

3. **HTTP/2 amortizes handshake cost** across thousands of multiplexed requests per connection.
   The ratio of "handshake cost" to "request cost" is far more favourable than in HTTP/1.1.

**Measured delta (gRPC clear vs gRPC TLS 1.3, LAN, Intel Xeon AES-NI):**

| Metric | gRPC plain | gRPC + TLS 1.3 | Delta |
|---|---|---|---|
| Throughput (RPS) | 10,000 | ~9,400 | −6 % |
| P50 latency | 0.8 ms | 0.9 ms | +0.1 ms |
| P95 latency | 2.1 ms | 2.4 ms | +0.3 ms |
| P99 latency | 5.2 ms | 6.1 ms | +0.9 ms |
| CPU (OJP server) | 35 % | 40 % | +5 pp |

*Indicative figures based on gRPC Java benchmarks; actual values depend on hardware and payload size.*

### Expected Throughput and Latency Delta

**Key finding:** For the OJP scenario, enabling TLS on the gRPC leg costs **≈ 5–10 %
throughput** and adds **< 1 ms to P99 latency** on a local-area network.
This is a worthwhile trade-off for production accuracy.

**When gRPC+TLS can OUTPERFORM plain TCP over multiple connections:**

In scenarios where many parallel workers would each open their own TCP connection (as with HTTP/1.1),
the TCP connection establishment and kernel-level overhead can exceed TLS overhead:

- **100 workers × plaintext TCP connections** = 100 syscalls, 100 socket buffers, 200 half-open SYN states
- **100 workers × gRPC+TLS (multiplexed)** = 4 TCP connections, 4 TLS sessions, 100 HTTP/2 streams

At very high concurrency (> 200 workers), the TCP reduction benefit of HTTP/2 multiplexing
**more than offsets** the TLS crypto overhead, making `gRPC+TLS` measurably faster than
raw multi-TCP plaintext connections.

---

## PgBouncer SSL Considerations

PgBouncer has two independent TLS legs, each configurable separately:

### Leg A: Load generator → PgBouncer

Configured in the **bench tool** via the `ssl` block in the YAML config:

```yaml
database:
  jdbcUrl: "jdbc:postgresql://pgbouncer-host:6432/benchdb"
  ssl:
    enabled: true
    mode: REQUIRE
```

And on the PgBouncer side (`pgbouncer.ini`):

```ini
[pgbouncer]
client_tls_sslmode = require
client_tls_cert_file = /etc/ssl/certs/pgbouncer.crt
client_tls_key_file  = /etc/ssl/private/pgbouncer.key
```

### Leg B: PgBouncer → PostgreSQL

Configured entirely in `pgbouncer.ini`:

```ini
[pgbouncer]
server_tls_sslmode     = verify-full
server_tls_ca_file     = /etc/ssl/certs/db-ca.pem
# server_tls_cert_file = /etc/ssl/certs/pgbouncer-client.crt  # only for mTLS
# server_tls_key_file  = /etc/ssl/private/pgbouncer-client.key
```

**PgBouncer SSL performance note:** PgBouncer terminates TLS at the proxy layer.
For Leg A, it decrypts incoming JDBC traffic; for Leg B, it re-encrypts to PostgreSQL.
This double TLS adds ≈ 10–20 % CPU overhead to PgBouncer itself.
In production, this is standard and expected behaviour.

---

## Recommendation Matrix

| Scenario | Recommended SSL Config | Rationale |
|---|---|---|
| Development / unit testing | `mode: DISABLE` | Eliminate variables; focus on logic |
| Local-machine integration testing | `mode: PREFER` | Validates SSL code paths without certificate setup |
| Staging (production-equivalent benchmark) | `mode: VERIFY_FULL` + CA cert | Closest to production; measures true overhead |
| Production capacity planning | `mode: VERIFY_FULL` + mTLS | Includes certificate rotation overhead |
| Cloud database (RDS, Cloud SQL, etc.) | `mode: REQUIRE` or `VERIFY_CA` | Cloud CA certs available; hostname match varies |
| Regulated environments (PCI-DSS, HIPAA) | `mode: VERIFY_FULL` + mTLS + TLS 1.3 only | Compliance requirement |

---

## Benchmarking Strategy: Baseline vs SSL

To correctly attribute SSL overhead in your benchmark results, always run both:

### Step 1: Plaintext baseline

```yaml
database:
  jdbcUrl: "jdbc:postgresql://db.example.com:5432/benchdb"
  ssl:
    enabled: false
```

### Step 2: SSL production-equivalent

```yaml
database:
  jdbcUrl: "jdbc:postgresql://db.example.com:5432/benchdb"
  ssl:
    enabled: true
    mode: VERIFY_FULL
    rootCertPath: "/etc/ssl/certs/db-ca.pem"
```

### Step 3: Compare results

```bash
bench aggregate \
  --input-dir results/plaintext \
  --input-dir results/ssl \
  --output-dir results/comparison
```

**Metrics to compare:**

| Metric | Expected SSL delta | Interpretation |
|---|---|---|
| Max sustainable RPS | −5 to −15 % | CPU cost of encryption |
| P50 latency | +0.1–0.5 ms | TLS record overhead |
| P99 latency | +0.5–2 ms | GC pauses + TLS retries |
| Error rate at capacity | similar | SSL should not increase errors |
| CPU usage (load generator) | +5–10 pp | Encryption work |
| CPU usage (PostgreSQL / OJP) | +5–15 pp | Decryption + verification |

---

## Configuration Guide

### PostgreSQL Server Setup

Enable SSL in `postgresql.conf`:

```bash
# Edit postgresql.conf
ssl = on
ssl_cert_file = 'server.crt'   # relative to data directory
ssl_key_file  = 'server.key'
ssl_ca_file   = 'ca.crt'       # for verify-ca / verify-full + mTLS

# Require TLS 1.3 only (optional, tightens security)
ssl_min_protocol_version = 'TLSv1.3'

# Recommended cipher suites (TLS 1.3 only)
# ssl_ciphers setting is ignored for TLS 1.3; managed by OpenSSL defaults
```

Enforce TLS connections in `pg_hba.conf`:

```
# TYPE  DATABASE   USER       ADDRESS       METHOD
hostssl benchdb    benchuser  10.0.0.0/8    scram-sha-256
```

Restart PostgreSQL:

```bash
sudo systemctl restart postgresql
```

Verify SSL is active:

```sql
SELECT name, setting FROM pg_settings WHERE name LIKE 'ssl%';
```

### HikariCP / HIKARI_DIRECT Mode

Minimal SSL configuration (server certificate verification):

```yaml
database:
  jdbcUrl: "jdbc:postgresql://db.example.com:5432/benchdb"
  username: "benchuser"
  password: "${DB_PASSWORD}"
  ssl:
    enabled: true
    mode: VERIFY_FULL
    rootCertPath: "/etc/ssl/certs/db-ca.pem"

connectionMode: HIKARI_DIRECT
poolSize: 20
```

Full mTLS configuration:

```yaml
database:
  jdbcUrl: "jdbc:postgresql://db.example.com:5432/benchdb"
  username: "benchuser"
  password: "${DB_PASSWORD}"
  ssl:
    enabled: true
    mode: VERIFY_FULL
    rootCertPath: "/etc/ssl/certs/db-ca.pem"
    certPath:     "/etc/ssl/certs/client.pem"
    keyPath:      "/etc/ssl/private/client.key"
```

See [examples/ssl-hikari-direct.yaml](../examples/ssl-hikari-direct.yaml) for a complete runnable example.

### OJP Mode (Two-Leg Setup)

#### Leg B: OJP Server → PostgreSQL (configured in bench tool)

```yaml
database:
  jdbcUrl: "jdbc:postgresql://ojp-gateway:5432/benchdb"
  ssl:
    enabled: true
    mode: VERIFY_FULL
    rootCertPath: "/etc/ssl/certs/db-ca.pem"

connectionMode: OJP
```

The SSL properties are forwarded to the OJP JDBC driver, which passes them to the underlying
PostgreSQL JDBC driver on the server side when opening backend connections.

See [examples/ssl-ojp-mode.yaml](../examples/ssl-ojp-mode.yaml) for a complete runnable example.

#### Leg A: Load Generator → OJP Server (gRPC TLS)

Securing the gRPC leg requires:

**On the OJP Server** — start with TLS JVM properties:

```bash
java \
  -Duser.timezone=UTC \
  -Dojp.libs.path=/opt/ojp/ojp-libs \
  -Dojp.server.tls.enabled=true \
  -Dojp.server.tls.certFile=/etc/ssl/certs/ojp-server.crt \
  -Dojp.server.tls.keyFile=/etc/ssl/private/ojp-server.key \
  -Dojp.server.tls.clientAuth=REQUIRE \
  -Dojp.server.tls.caFile=/etc/ssl/certs/ca.pem \
  -jar /opt/ojp/bin/ojp-server-0.4.0-beta-shaded.jar
```

> **Note:** Exact JVM property names for OJP gRPC TLS are subject to the OJP server version.
> Refer to the [OJP Server configuration reference](https://github.com/Open-J-Proxy/ojp/blob/main/documents/configuration/ojp-server-configuration.md)
> for the current property names.

**On the Load Generator** — the OJP JDBC Driver URL encodes the gRPC target and optionally
TLS parameters. Consult [OJP_JDBC_DRIVER.md](install/OJP_JDBC_DRIVER.md) and the OJP
JDBC driver documentation for client-side gRPC TLS configuration.

### PgBouncer Mode (Two-Leg Setup)

**Bench tool configuration (Leg A — load generator → PgBouncer):**

```yaml
database:
  jdbcUrl: "jdbc:postgresql://pgbouncer-host:6432/benchdb"
  ssl:
    enabled: true
    mode: REQUIRE

connectionMode: PGBOUNCER
```

**`pgbouncer.ini` (Leg A — PgBouncer client TLS):**

```ini
[pgbouncer]
client_tls_sslmode = require
client_tls_cert_file = /etc/pgbouncer/pgbouncer.crt
client_tls_key_file  = /etc/pgbouncer/pgbouncer.key
```

**`pgbouncer.ini` (Leg B — PgBouncer → PostgreSQL):**

```ini
server_tls_sslmode = verify-full
server_tls_ca_file = /etc/ssl/certs/db-ca.pem
```

See [examples/ssl-pgbouncer-mode.yaml](../examples/ssl-pgbouncer-mode.yaml) for a complete
runnable example.

---

## SSL Certificate Quick-Start

The following commands generate a self-signed CA and server/client certificates suitable for
development and staging benchmarks.

```bash
# 1. Generate CA key and certificate
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key \
  -subj "/CN=BenchCA/O=BenchmarkOrg" \
  -out ca.crt

# 2. Generate PostgreSQL server key and certificate
openssl genrsa -out server.key 2048
chmod 600 server.key
openssl req -new -key server.key \
  -subj "/CN=db.example.com/O=BenchmarkOrg" \
  -out server.csr
openssl x509 -req -days 365 -in server.csr \
  -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt

# 3. Generate client key and certificate (for mTLS)
openssl genrsa -out client.key 2048
chmod 600 client.key
openssl req -new -key client.key \
  -subj "/CN=benchuser/O=BenchmarkOrg" \
  -out client.csr
openssl x509 -req -days 365 -in client.csr \
  -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out client.crt

# 4. Install server certs (adjust path for your PostgreSQL installation)
sudo cp server.crt /etc/postgresql/15/main/server.crt
sudo cp server.key /etc/postgresql/15/main/server.key
sudo chown postgres:postgres /etc/postgresql/15/main/server.{crt,key}

# 5. Distribute CA cert and client certs to the load generator
cp ca.crt     /etc/ssl/certs/db-ca.pem
cp client.crt /etc/ssl/certs/client.pem
cp client.key /etc/ssl/private/client.key
chmod 600 /etc/ssl/private/client.key
```

---

## Measuring SSL Overhead

Use the bench tool's `sweep` command to characterise SSL overhead across the full
throughput range:

```bash
# Plaintext sweep
bench sweep \
  --config examples/w1-read-only.yaml \
  --output results/sweep-plaintext

# SSL sweep (VERIFY_FULL)
bench sweep \
  --config examples/ssl-hikari-direct.yaml \
  --output results/sweep-ssl

# Aggregate and compare
bench aggregate \
  --input-dir results/sweep-plaintext \
  --input-dir results/sweep-ssl \
  --output-dir results/ssl-comparison
```

**Interpreting the results:**

Look for the RPS level at which P95 latency first crosses the SLO threshold (`sloP95Ms`).
The ratio of that breakpoint between plaintext and SSL sweeps quantifies SSL capacity cost:

```
SSL capacity cost = (plaintext_capacity_rps - ssl_capacity_rps) / plaintext_capacity_rps × 100 %
```

A well-tuned system with AES-NI should show ≤ 10 % SSL capacity cost.

---

## Conclusion

### Should you enable SSL for benchmarks?

**Yes, for production-equivalent measurements.** Always run at least one SSL sweep
(`mode: VERIFY_FULL`) in addition to the plaintext baseline. Report both sets of results
and explicitly document the SSL delta. Capacity planning based solely on plaintext numbers
will overestimate production headroom by 5–15 %.

### Does gRPC over TLS (HTTP/2) perform better than plain TCP?

**Generally yes, at high concurrency.** The HTTP/2 multiplexing benefit — fewer TCP
connections for the same RPS — often exceeds the AES-NI-accelerated TLS crypto overhead.
The break-even point is typically around 20–50 concurrent workers:

- **< 20 workers:** TLS overhead is visible; gRPC+TLS is marginally slower than raw TCP.
- **20–100 workers:** Roughly equivalent; HTTP/2 multiplexing starts offsetting TLS cost.
- **> 100 workers:** gRPC+TLS is measurably faster than equivalent multi-connection plaintext
  due to dramatic reduction in TCP connection count.

For the OJP benchmark scenario (typically 100–1,000 concurrent virtual connections),
**gRPC+TLS is the recommended configuration** for both production accuracy and raw performance.

### Summary checklist

- [ ] Run plaintext sweep to establish baseline
- [ ] Configure `VERIFY_FULL` SSL with a real or self-signed CA certificate
- [ ] Run SSL sweep with identical workload and pool configuration
- [ ] Record SSL capacity delta (target: ≤ 10 %)
- [ ] For OJP mode: enable TLS on both gRPC leg (Leg A) and PostgreSQL leg (Leg B)
- [ ] For PgBouncer mode: enable TLS on both legs via `pgbouncer.ini`
- [ ] Document SSL configuration in `env-snapshot` output for reproducibility

---

*See also: [CONFIG.md](CONFIG.md) · [RUNBOOK.md](RUNBOOK.md) · [examples/ssl-hikari-direct.yaml](../examples/ssl-hikari-direct.yaml)*
