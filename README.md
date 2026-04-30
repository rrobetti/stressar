# OJP Performance Benchmark Tool

Comprehensive tooling to assess the performance of OJP (Open J Proxy) and compare it with other connection pooling approaches including direct JDBC (HikariCP) and PgBouncer.

---

## Test Results Summary

All tests are executed with **no TLS** (plaintext on all network legs inside a trusted benchmark network) and **16 independent client JVM processes** (8 on each of two load-generator machines). The backend connection budget differs by SUT: **300 direct connections** for HikariCP (≈19 per replica) and **48 proxy backend connections** for OJP and PgBouncer (16 per proxy node). Two test protocols are run for each of the three systems under test (SUTs).

See [Simplified Test List](#simplified-test-list) below for a plain-language description of what is run.
See [BENCHMARKING_GUIDE.md](docs/BENCHMARKING_GUIDE.md) for the full protocol.

### Test A — Capacity Sweep (Increasing Load)

| SUT | Clients | Starting RPS | p50 (ms) | p95 (ms) | p99 (ms) | Max Sustainable Throughput (RPS) | Error Rate |
|-----|---------|-------------|----------|----------|----------|----------------------------------|-----------|
| HikariCP Direct (disciplined baseline) | 16 | 16 × 63 ≈ 1,000 | TBD | TBD | TBD | TBD | TBD |
| OJP — 3 nodes, client-side LB | 16 | 16 × 63 ≈ 1,000 | TBD | TBD | TBD | TBD | TBD |
| PgBouncer — 3 nodes + HAProxy | 16 | 16 × 63 ≈ 1,000 | TBD | TBD | TBD | TBD | TBD |

### Test B — Overload & Recovery (130 % of Max Sustainable Throughput)

| SUT | Overload Level | Peak p99 (ms) | Error Rate During Overload | Recovery Time (s) |
|-----|---------------|---------------|---------------------------|------------------|
| HikariCP Direct (disciplined baseline) | 130 % MST | TBD | TBD | TBD |
| OJP — 3 nodes, client-side LB | 130 % MST | TBD | TBD | TBD |
| PgBouncer — 3 nodes + HAProxy | 130 % MST | TBD | TBD | TBD |

---

## Simplified Test List

> Full specs, topology diagrams, and exact commands are in [BENCHMARKING_GUIDE.md](docs/BENCHMARKING_GUIDE.md).

Two tests are run, each against three different systems under test (SUTs):

| # | What we run | Why |
|---|-------------|-----|
| **Test A** | Gradually increase the request rate in 15 % steps until the system can no longer keep up (p95 latency > 50 ms or error rate > 0.1 %). Record the maximum sustainable throughput for each SUT. | Finds each system's breaking point and compares throughput capacity. |
| **Test B** | Push each system to 130 % of its maximum throughput for 5 minutes, then drop back to 70 % and measure how long it takes to recover. | Reveals queue management, back-pressure behaviour, and resilience under overload. |

The three systems under test (run in this order):

1. **HikariCP Direct (baseline)** — 16 independent Java processes each holding a small HikariCP pool, connecting directly to PostgreSQL (plaintext). No proxy. This is the upper-bound baseline.
2. **OJP (3 nodes)** — The same 16 Java processes connect to 3 OJP server nodes via the OJP JDBC driver (built-in client-side load balancing). All traffic is plaintext gRPC over HTTP/2.
3. **PgBouncer (3 nodes + HAProxy)** — The same 16 Java processes connect through an HAProxy load balancer to 3 PgBouncer instances in transaction-pooling mode. All traffic is plaintext.

**Key rule: no single-client tests.** Every scenario uses 16 bench-JVM replicas split across two machines to simulate a realistic microservice deployment (8 replicas × 2 machines). See [Section 2](docs/BENCHMARKING_GUIDE.md#2-hardware-specifications) for machine specs.

---

## Features

- **Multiple SUT Modes**: HIKARI_DISCIPLINED, OJP, PGBOUNCER
- **Workload Types**: Read-only (W1), Read-Write (W2), Mixed, Slow Query (W3)
- **Load Modes**: Open-loop (rate-based) and closed-loop (concurrency-based)
- **Plaintext networking**: No TLS on any leg — TLS overhead is excluded as a variable
- **Metrics**: Per-second timeseries, HDR histograms, system resource monitoring
- **Multi-Instance**: 16 independent client JVM replicas simulating a microservice deployment
- **Capacity Testing**: Automated sweep to find maximum sustainable throughput

## Quick Start

### 1. Build the Tool

```bash
./gradlew build
./gradlew installDist
```

The executable will be at: `build/install/ojp-performance-tester/bin/bench`

### 2. Initialize Database

```bash
build/install/ojp-performance-tester/bin/bench init-db \
  --jdbc-url "jdbc:postgresql://localhost:5432/benchdb" \
  --username benchuser \
  --password benchpass \
  --accounts 10000 \
  --items 5000 \
  --orders 50000
```

### 3. Run Benchmark

```bash
build/install/ojp-performance-tester/bin/bench run \
  --config examples/w2-open-loop-500rps.yaml \
  --output results/
```

## Documentation

Comprehensive documentation is available in the `docs/` directory:

- **[DEPLOYMENT_GUIDE.md](docs/DEPLOYMENT_GUIDE.md)** - Single control-node deployment guide (pre-provisioned machines, SSH-based setup, OJP 0.4.8-beta)

- **[install/README.md](docs/install/README.md)** - Installation guides index
  - [Java](docs/install/JAVA.md) — JVM runtime (required)
  - [Gradle](docs/install/GRADLE.md) — build tool (wrapper included)
  - [PostgreSQL](docs/install/POSTGRESQL.md) — database (required)
  - [pgBouncer](docs/install/PGBOUNCER.md) — connection pooler (PgBouncer scenario)
  - [HAProxy](docs/install/HAPROXY.md) — load balancer (PgBouncer scenario)
  - [OJP](docs/install/OJP.md) — Open J Proxy (OJP scenario)

- **[RATIONALE.md](docs/RATIONALE.md)** - Why this tool was built
  - Absence of JDBC-client benchmarks for PgBouncer
  - Limitations of closed-loop load generation
  - Reproducibility requirements for scientific publication

- **[PARAMETER_DECISIONS.md](docs/PARAMETER_DECISIONS.md)** - Explainability: why every numeric constant was chosen
  - Why 16 client JVM processes (and why 8 per machine)
  - Why 300 direct backend connections (19 per replica) vs 48 proxy backend connections (16 per proxy node), and 63 RPS per client
  - Why specific timing windows, SLO thresholds, dataset sizes, and infrastructure settings

- **[BENCHMARKING_GUIDE.md](docs/BENCHMARKING_GUIDE.md)** - Step-by-step benchmarking protocol
  - Deployment topology and hardware specifications
  - Software installation and configuration (PostgreSQL, PgBouncer, OJP)
  - No-TLS network design rationale
  - Little's Law capacity analysis
  - Two test protocols (Capacity Sweep, Overload & Recovery) across three SUTs
  - Expected outcomes stated as falsifiable hypotheses
  - Analysis and reporting procedures

- **[RUNBOOK.md](docs/RUNBOOK.md)** - Complete operational guide with exact commands
  - PostgreSQL setup and configuration
  - Database initialization
  - Running all SUT modes
  - Multi-instance scenarios
  - Results interpretation and troubleshooting

- **[CONFIG.md](docs/CONFIG.md)** - Complete configuration reference
  - All parameters with descriptions and defaults
  - Workload types and load modes
  - Example configurations for common scenarios

- **[RESULTS_FORMAT.md](docs/RESULTS_FORMAT.md)** - Data schemas and formats
  - Timeseries CSV format
  - Summary JSON schema
  - HDR histogram logs
  - Metrics calculation methodology

## Example Configurations

The `examples/` directory contains ready-to-use configuration files:

- `ta-baseline-hikari.yaml` — Capacity Sweep / Overload: 16-replica HikariCP direct (baseline)
- `ta-ojp.yaml` — Capacity Sweep / Overload: 16-replica OJP (client-side LB)
- `ta-pgbouncer.yaml` — Capacity Sweep / Overload: 16-replica PgBouncer via HAProxy
- `w1-read-only.yaml` — Read-only workload
- `w3-slow-query.yaml` — Slow query workload

## Requirements

- [Java 11 or later](docs/install/JAVA.md)
- [PostgreSQL 12+](docs/install/POSTGRESQL.md) (tested with 14, 15, 16)
- [Gradle 7+](docs/install/GRADLE.md) (included via `./gradlew`)

For multi-scenario benchmarks (pgBouncer, HAProxy, OJP), see the
[installation guides index](docs/install/README.md).

## License

See [LICENSE](LICENSE) file for details.
