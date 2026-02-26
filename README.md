# OJP Performance Benchmark Tool

Comprehensive tooling to assess the performance of OJP (Open JDBC Pooler) and compare it with other connection pooling approaches including direct JDBC (HikariCP), PgBouncer, and disciplined pooling strategies.

## Features

- **Multiple SUT Modes**: HIKARI_DIRECT, HIKARI_DISCIPLINED, OJP, PGBOUNCER
- **Workload Types**: Read-only (W1), Read-Write (W2), Mixed, Slow Query (W3)
- **Load Modes**: Open-loop (rate-based) and closed-loop (concurrency-based)
- **Metrics**: Per-second timeseries, HDR histograms, system resource monitoring
- **Multi-Instance**: Support for K replicas with connection budget discipline
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

- **[RATIONALE.md](docs/RATIONALE.md)** - Why this tool was built
  - Absence of JDBC-client benchmarks for PgBouncer
  - Limitations of closed-loop load generation
  - Reproducibility requirements for scientific publication

- **[BENCHMARKING_GUIDE.md](docs/BENCHMARKING_GUIDE.md)** - Step-by-step benchmarking protocol
  - Deployment topology and hardware specifications
  - Software installation and configuration (PostgreSQL, PgBouncer, OJP)
  - Six test scenarios including a 130 % overload / recovery test
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

- `w2-open-loop-500rps.yaml` - Basic open-loop test at 500 RPS
- `disciplined-16-replicas.yaml` - Multi-instance with connection budget (K=16)
- `ojp-mode.yaml` - OJP gateway configuration
- `pgbouncer-mode.yaml` - PgBouncer external pooler
- `w1-read-only.yaml` - Read-only workload
- `w3-slow-query.yaml` - Slow query workload

## Requirements

- Java 11 or later
- PostgreSQL 12+ (tested with 14, 15, 16)
- Gradle 7+ (included via `./gradlew`)

## License

See [LICENSE](LICENSE) file for details.
