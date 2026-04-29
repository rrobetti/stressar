# OJP Performance Benchmark Tool - Operational Runbook

Complete guide for running benchmarks with exact commands and configurations.

## Table of Contents
- [Installation Guides](#installation-guides)
- [Prerequisites](#prerequisites)
- [PostgreSQL Setup](#postgresql-setup)
- [Database Initialization](#database-initialization)
- [Running Benchmarks](#running-benchmarks)
- [SUT Modes](#sut-modes)
- [Example Scenarios](#example-scenarios)
- [Results Collection](#results-collection)
- [Interpreting Results](#interpreting-results)

---

## Installation Guides

Quick links to component installation documentation:

| Component | Guide |
|---|---|
| Java 11+ (required) | [install/JAVA.md](install/JAVA.md) |
| Gradle 7+ (included via wrapper) | [install/GRADLE.md](install/GRADLE.md) |
| PostgreSQL 12+ (required) | [install/POSTGRESQL.md](install/POSTGRESQL.md) |
| pgBouncer (T3 / PGBOUNCER mode) | [install/PGBOUNCER.md](install/PGBOUNCER.md) |
| HAProxy load balancer (T3 only) | [install/HAPROXY.md](install/HAPROXY.md) |
| OJP server + JDBC driver (T4 / OJP mode) | [install/OJP.md](install/OJP.md) · [install/OJP_JDBC_DRIVER.md](install/OJP_JDBC_DRIVER.md) |

See also: [install/README.md](install/README.md) for a guided setup overview.

---

## Prerequisites

### Required Software
- **[Java 11 or later](install/JAVA.md)** (verify with `java -version`) — see [install/JAVA.md](install/JAVA.md)
- **[PostgreSQL 12+](install/POSTGRESQL.md)** (tested with PostgreSQL 14, 15, 16) — see [install/POSTGRESQL.md](install/POSTGRESQL.md)
- **[Gradle 7+](install/GRADLE.md)** (included via `./gradlew`) — see [install/GRADLE.md](install/GRADLE.md)
- At least **4GB RAM** available for the benchmark tool
- At least **8GB RAM** for PostgreSQL (adjust `shared_buffers` accordingly)

For additional components used in multi-scenario benchmarks, see the
[installation guides index](install/README.md):
- **[pgBouncer](install/PGBOUNCER.md)** — required for the PGBOUNCER / T3 scenario
- **[HAProxy](install/HAPROXY.md)** — load balancer required for the T3 scenario
- **[OJP](install/OJP.md)** — OJP Server required for the OJP / T4 scenario; **[OJP JDBC Driver](install/OJP_JDBC_DRIVER.md)** — required on the load generator for T4

### System Setup
```bash
# Verify Java installation
java -version  # Should show version 11 or higher

# Verify PostgreSQL is running
psql --version
pg_isready
```

---

## PostgreSQL Setup

### 1. Create Benchmark Database

```bash
# Create database and user
sudo -u postgres psql <<EOF
CREATE DATABASE benchdb;
CREATE USER benchuser WITH PASSWORD 'benchpass';
GRANT ALL PRIVILEGES ON DATABASE benchdb TO benchuser;
\c benchdb
GRANT ALL ON SCHEMA public TO benchuser;
EOF
```

### 2. Enable pg_stat_statements (Required for Metrics)

```bash
# Edit postgresql.conf (location varies by installation)
sudo vim /etc/postgresql/15/main/postgresql.conf  # Ubuntu/Debian
# OR
sudo vim /var/lib/pgsql/data/postgresql.conf      # RedHat/CentOS

# Add these lines:
shared_preload_libraries = 'pg_stat_statements'
pg_stat_statements.track = all
pg_stat_statements.max = 10000
track_io_timing = on
track_activity_query_size = 2048
```

Restart PostgreSQL:
```bash
sudo systemctl restart postgresql
```

Enable the extension in your database:
```bash
psql -U benchuser -d benchdb <<EOF
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
EOF
```

### 3. Optimize PostgreSQL for Benchmarking

Recommended settings for a dedicated benchmark machine with 16GB RAM:

```bash
# Edit postgresql.conf
shared_buffers = 4GB
effective_cache_size = 12GB
maintenance_work_mem = 1GB
checkpoint_completion_target = 0.9
wal_buffers = 16MB
default_statistics_target = 100
random_page_cost = 1.1  # For SSD
effective_io_concurrency = 200
work_mem = 32MB
min_wal_size = 2GB
max_wal_size = 8GB
max_worker_processes = 8
max_parallel_workers_per_gather = 4
max_parallel_workers = 8
max_connections = 200  # Adjust based on expected load
```

Restart PostgreSQL after changes:
```bash
sudo systemctl restart postgresql
```

### 4. Reset Statistics (Before Each Benchmark Run)

```bash
psql -U benchuser -d benchdb <<EOF
SELECT pg_stat_statements_reset();
SELECT pg_stat_reset();
EOF
```

---

## Database Initialization

### Build the Tool

> **Prerequisites:** [Java 11+](install/JAVA.md) and [Gradle 7+](install/GRADLE.md) must be installed.
> The `./gradlew` wrapper downloads Gradle automatically on first use.

```bash
cd ojp-performance-tester-tool
./gradlew build
./gradlew installDist
```

The executable will be at: `build/install/ojp-performance-tester/bin/bench`

For convenience, add an alias:
```bash
alias bench="$(pwd)/build/install/ojp-performance-tester/bin/bench"
```

### Initialize Schema and Data

```bash
bench init-db \
  --jdbc-url "jdbc:postgresql://localhost:5432/benchdb" \
  --username benchuser \
  --password benchpass \
  --accounts 10000 \
  --items 5000 \
  --orders 50000 \
  --seed 42
```

**Parameters:**
- `--accounts`: Number of customer accounts (default: 10000)
- `--items`: Number of products/items (default: 5000)
- `--orders`: Number of historical orders (default: 50000)
- `--seed`: Random seed for reproducible data (default: 42)

**Expected Output:**
```
Connecting to database...
Creating schema...
Creating tables... Done
Creating indexes... Done
Generating 10000 accounts... Done
Generating 5000 items... Done
Generating 50000 orders... Done
Database initialization complete!
```

**Verification:**
```bash
psql -U benchuser -d benchdb -c "SELECT COUNT(*) FROM accounts;"
psql -U benchuser -d benchdb -c "SELECT COUNT(*) FROM orders;"
```

---

## Running Benchmarks

### Basic Workflow

1. **Create configuration file** (see examples in `examples/`)
2. **Run warmup** (optional but recommended)
3. **Run benchmark**
4. **Collect results**

### Configuration File Structure

Create a YAML file (e.g., `my-benchmark.yaml`):

```yaml
database:
  jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: HIKARI_DIRECT  # See SUT Modes section

poolSize: 20

workload:
  type: W2_MIXED
  openLoop: true
  targetRps: 500
  warmupSeconds: 300
  durationSeconds: 600
  cooldownSeconds: 120

outputDir: "results"
```

### Run a Single Benchmark

```bash
bench run \
  --config my-benchmark.yaml \
  --output results/
```

**Optional Parameters:**
- `--instance-id 0`: For multi-replica runs (default: 0)

### Run Warmup Phase Only

Warm up database caches before the actual benchmark:

```bash
bench warmup --config my-benchmark.yaml
```

This runs the workload for `warmupSeconds` (default: 300s) without recording metrics.

---

## SUT Modes

The `connectionMode` parameter selects the System Under Test (SUT):

### 1. HIKARI_DIRECT (Baseline JDBC)

Direct JDBC connections via HikariCP pool. Each process has its own pool.

**Configuration:**
```yaml
connectionMode: HIKARI_DIRECT
poolSize: 20  # Number of connections per process
```

**Use Case:** Baseline comparison, single-instance applications

**Example:**
```bash
bench run --config examples/hikari-direct.yaml --output results/hikari-direct/
```

---

### 2. HIKARI_DISCIPLINED (Multi-Instance with Budget)

Distributes a fixed connection budget across K replica instances.

**Formula:** `poolSize per replica = dbConnectionBudget / replicas`

**Configuration:**
```yaml
connectionMode: HIKARI_DISCIPLINED
dbConnectionBudget: 100  # Total connections across all replicas
replicas: 16             # Number of replicas
maxPoolSizePerReplica: 50  # Safety limit per replica
```

Each replica gets: `100 / 16 = 6.25` → **6 connections**

**Use Case:** Testing connection pooling discipline, simulating horizontal scaling

**Running K Replicas:**

You must manually start each replica with a unique `--instance-id`:

```bash
# Terminal 1: Replica 0
bench run --config disciplined-16-replicas.yaml --instance-id 0 --output results/disciplined/ &

# Terminal 2: Replica 1
bench run --config disciplined-16-replicas.yaml --instance-id 1 --output results/disciplined/ &

# ... Continue for replicas 2-15

# Or use a loop (ensure enough terminal windows/background processes):
for i in {0..15}; do
  bench run --config disciplined-16-replicas.yaml --instance-id $i --output results/disciplined/ &
done
wait  # Wait for all replicas to complete
```

**Results Location:**
```
results/disciplined/raw/{timestamp}/HIKARI_DISCIPLINED/{workload}/
  instance_0/
    timeseries.csv
    summary.json
    latency.hdr
  instance_1/
    ...
  instance_15/
    ...
```

---

### 3. OJP (Open J Proxy Gateway)

Connects through OJP server-side connection pooler. Client uses minimal connections.

**Configuration:**
```yaml
connectionMode: OJP

# OJP endpoint URL — use the OJP JDBC URL format (port 1059 = gRPC, not 5432)
database:
  jdbcUrl: "jdbc:ojp[<PROXY1_IP>:1059,<PROXY2_IP>:1059,<PROXY3_IP>:1059]_postgresql://<DB_IP>:5432/benchdb"
  username: "benchuser"
  password: "benchpass"
```

**Prerequisites:**
- OJP Server must be running on each proxy node — see [install/OJP.md](install/OJP.md)
- OJP JDBC Driver must be on the benchmark tool classpath — see [install/OJP_JDBC_DRIVER.md](install/OJP_JDBC_DRIVER.md)

**Use Case:** Testing server-side connection pooling

---

### 4. PGBOUNCER (External Connection Pooler)

Uses PgBouncer for connection pooling. Client uses minimal connections.

**Configuration:**
```yaml
connectionMode: PGBOUNCER
poolSize: 2  # Minimal client-side connections

database:
  jdbcUrl: "jdbc:postgresql://pgbouncer-host:6432/benchdb"
  username: "benchuser"
  password: "benchpass"
```

**Prerequisites:**
- PgBouncer must be running and configured — see [install/PGBOUNCER.md](install/PGBOUNCER.md)
- Database URL should point to PgBouncer (not directly to PostgreSQL)

**PgBouncer Setup Example:**

```ini
# /etc/pgbouncer/pgbouncer.ini
[databases]
benchdb = host=localhost port=5432 dbname=benchdb

[pgbouncer]
listen_addr = *
listen_port = 6432
auth_type = md5
auth_file = /etc/pgbouncer/userlist.txt
pool_mode = transaction
max_client_conn = 1000
default_pool_size = 50
```

**Use Case:** Testing PgBouncer performance

---

## Example Scenarios

### Scenario 1: Single Instance Open-Loop Baseline

Test HIKARI_DIRECT at 1000 RPS with W2_MIXED workload.

**Configuration (`hikari-1000rps.yaml`):**
```yaml
database:
  jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: HIKARI_DIRECT
poolSize: 20

workload:
  type: W2_MIXED
  openLoop: true
  targetRps: 1000
  warmupSeconds: 300
  durationSeconds: 600
  cooldownSeconds: 120
  queryAPercent: 0.30
  writePercent: 0.20

numAccounts: 10000
numItems: 5000
numOrders: 50000
metricsIntervalSeconds: 1
outputDir: "results"
```

**Commands:**
```bash
# 1. Reset database statistics
psql -U benchuser -d benchdb -c "SELECT pg_stat_statements_reset();"

# 2. Run warmup
bench warmup --config hikari-1000rps.yaml

# 3. Reset statistics again
psql -U benchuser -d benchdb -c "SELECT pg_stat_statements_reset();"

# 4. Run benchmark
bench run --config hikari-1000rps.yaml --output results/single-instance/

# 5. Capture environment snapshot
bench env-snapshot --output results/single-instance/
```

**Results:**
- `results/single-instance/raw/{timestamp}/HIKARI_DIRECT/W2_MIXED/instance_0/`

---

### Scenario 2: K=16 Replicas Open-Loop

Test HIKARI_DISCIPLINED with 16 replicas sharing 100 DB connections.

**Configuration (`disciplined-16-replicas.yaml`):**
```yaml
database:
  jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: HIKARI_DISCIPLINED
dbConnectionBudget: 100
replicas: 16
maxPoolSizePerReplica: 50

workload:
  type: W2_MIXED
  openLoop: true
  targetRps: 500  # 500 RPS × 16 = 8000 total RPS
  warmupSeconds: 300
  durationSeconds: 600
  cooldownSeconds: 120
  queryAPercent: 0.30
  writePercent: 0.20

outputDir: "results"
```

**Commands:**
```bash
# 1. Reset statistics
psql -U benchuser -d benchdb -c "SELECT pg_stat_statements_reset();"

# 2. Run 16 replicas in background
for i in {0..15}; do
  bench run \
    --config disciplined-16-replicas.yaml \
    --instance-id $i \
    --output results/16-replicas/ &
done

# 3. Wait for completion
wait

# 4. Aggregate results
bench aggregate \
  --input results/16-replicas/raw/*/HIKARI_DISCIPLINED/W2_MIXED/ \
  --output results/16-replicas/aggregated/
```

**Verification:**
```bash
# Check that 16 result directories exist
ls -l results/16-replicas/raw/*/HIKARI_DISCIPLINED/W2_MIXED/

# Should show: instance_0, instance_1, ..., instance_15
```

---

### Scenario 3: Disciplined Pooling (DB Budget 100, K=16)

Same as Scenario 2, but emphasizing the connection budget enforcement.

**Key Points:**
- Each replica gets `100 / 16 = 6` connections (rounded down)
- Total DB connections = 6 × 16 = 96 (within budget)
- Tests connection pooling discipline

**Monitor Active Connections:**
```bash
# In a separate terminal, monitor active connections:
watch -n 1 'psql -U benchuser -d benchdb -c "SELECT count(*) FROM pg_stat_activity WHERE state = '\''active'\'';"'
```

Expected: ~96 active connections (may vary slightly due to transient connections)

---

### Scenario 4: Capacity Sweep

Find maximum sustainable throughput while meeting SLO (P95 < 50ms, error rate < 0.1%).

**Configuration (`sweep-config.yaml`):**
```yaml
database:
  jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: HIKARI_DIRECT
poolSize: 20

workload:
  type: W2_MIXED
  openLoop: true
  targetRps: 100  # Starting RPS
  warmupSeconds: 120
  durationSeconds: 300
  cooldownSeconds: 60
  queryAPercent: 0.30
  writePercent: 0.20
  repeatCount: 3  # Repeat each level 3 times

# Sweep configuration
sweepIncrementPercent: 15.0  # Increase by 15% each level
sloP95Ms: 50                 # P95 latency SLO
errorRateThreshold: 0.001    # 0.1% error rate threshold

outputDir: "results"
```

**Command:**
```bash
bench sweep \
  --config sweep-config.yaml \
  --output results/sweep/ \
  --increment 15.0 \
  --slo-p95-ms 50 \
  --error-rate-threshold 0.001 \
  --repeat 3
```

**Process:**
1. Starts at `targetRps` (100 RPS)
2. Runs 3 repetitions at each level
3. Increases by 15% if SLO met: 100 → 115 → 132 → 152 → ...
4. Stops when P95 > 50ms OR error rate > 0.1%
5. Reports maximum sustainable throughput

**Expected Output:**
```
Level 1: 100 RPS - PASS (P95: 8.2ms, errors: 0.00%)
Level 2: 115 RPS - PASS (P95: 10.5ms, errors: 0.00%)
Level 3: 132 RPS - PASS (P95: 14.8ms, errors: 0.00%)
Level 4: 152 RPS - PASS (P95: 22.3ms, errors: 0.01%)
Level 5: 175 RPS - PASS (P95: 35.6ms, errors: 0.02%)
Level 6: 201 RPS - PASS (P95: 48.1ms, errors: 0.05%)
Level 7: 231 RPS - FAIL (P95: 67.3ms, errors: 0.08%)

Maximum sustainable throughput: 201 RPS
```

---

### Scenario 5: Overload Test

Stress test at very high RPS to observe system behavior under overload.

**Command:**
```bash
bench overload \
  --config my-benchmark.yaml \
  --target-rps 5000 \
  --duration-seconds 300 \
  --output results/overload/
```

**Use Case:** Test error handling, connection pool saturation, queue buildup

---

## Results Collection

### Environment Snapshot

Capture system information for reproducibility:

```bash
bench env-snapshot --output results/my-run/
```

**Collected Information:**
- OS version, kernel version
- CPU model, core count, frequency
- Total RAM, available RAM
- Java version, JVM flags
- PostgreSQL version, configuration
- Disk I/O statistics
- Network configuration

**Output:** `results/my-run/env_snapshot.json`

---

### Directory Layout

```
results/
├── raw/
│   └── 2024-02-18_143022/           # Timestamp of run
│       └── HIKARI_DIRECT/           # Connection mode
│           └── W2_MIXED/            # Workload type
│               └── instance_0/      # Instance ID
│                   ├── timeseries.csv
│                   ├── summary.json
│                   ├── latency.hdr
│                   └── metadata.json
├── aggregated/
│   └── 2024-02-18_143022/
│       └── HIKARI_DIRECT/
│           └── W2_MIXED/
│               ├── aggregated_summary.json
│               ├── timeseries_combined.csv
│               └── plots/
│                   ├── latency_percentiles.png
│                   ├── throughput_over_time.png
│                   └── error_rate.png
└── env_snapshot.json
```

---

### Aggregating Multi-Instance Results

When running multiple replicas, aggregate results:

```bash
bench aggregate \
  --input results/raw/2024-02-18_143022/HIKARI_DISCIPLINED/W2_MIXED/ \
  --output results/aggregated/2024-02-18_143022/HIKARI_DISCIPLINED/W2_MIXED/
```

**Aggregation Process:**
1. Reads all `instance_*/summary.json` files
2. Sums throughput across instances
3. Calculates weighted average latencies
4. Combines error counts
5. Generates aggregate summary and plots

---

## Interpreting Results

### Timeseries CSV

**File:** `instance_0/timeseries.csv`

**Columns:**
- `timestamp_iso`: ISO 8601 timestamp
- `attempted_rps`: Target RPS (open-loop) or offered load
- `achieved_rps`: Actual requests completed
- `errors`: Number of errors in this second
- `p50_ms`, `p95_ms`, `p99_ms`, `p999_ms`, `max_ms`: Latency percentiles

**Example:**
```csv
timestamp_iso,attempted_rps,achieved_rps,errors,p50_ms,p95_ms,p99_ms,p999_ms,max_ms
2024-02-18T14:30:00Z,500.00,499.80,0,2.14,8.45,15.23,34.56,45.12
2024-02-18T14:30:01Z,500.00,500.10,0,2.18,8.52,15.67,35.22,46.78
2024-02-18T14:30:02Z,500.00,498.50,2,2.21,9.01,18.34,42.11,78.90
```

**What to Look For:**
- **Achieved RPS close to attempted RPS**: System keeping up
- **P95/P99 stable over time**: No degradation
- **Errors = 0**: No failures
- **Spikes in max_ms**: Check for GC pauses or database contention

---

### Summary JSON

**File:** `instance_0/summary.json`

**Key Fields:**
- `achievedThroughputRps`: Average RPS over entire run
- `errorRate`: Fraction of failed requests
- `latencyMs.p95`: 95th percentile latency
- `gcPauseMsTotal`: Total GC pause time
- `dbActiveConnectionsMedian`: Median active DB connections

**Example:**
```json
{
  "runInfo": {
    "sut": "HIKARI_DIRECT",
    "workload": "W2_MIXED",
    "loadMode": "open-loop",
    "targetRps": 500,
    "poolSize": 20,
    "instanceId": 0
  },
  "achievedThroughputRps": 499.85,
  "errorRate": 0.0002,
  "latencyMs": {
    "p50": 2.18,
    "p95": 8.67,
    "p99": 16.23,
    "p999": 38.45,
    "max": 78.90,
    "mean": 4.52
  }
}
```

**Success Criteria:**
- `achievedThroughputRps` ≥ 95% of `targetRps`
- `errorRate` < 0.001 (0.1%)
- `latencyMs.p95` < SLO target (e.g., 50ms)

---

### HDR Histogram Logs

**File:** `instance_0/latency.hdr`

Binary format for detailed percentile analysis.

**Analyzing with HdrHistogram Tools:**
```bash
# Convert to human-readable percentiles
java -jar HdrHistogram.jar -i latency.hdr -o latency_percentiles.txt

# Generate latency distribution plot
java -jar HdrHistogram.jar -i latency.hdr -o latency_plot.png -chart
```

**Use Case:** Analyzing tail latencies (P99.9, P99.99, max)

---

### Plots (Generated by Aggregation)

**Files:**
- `latency_percentiles.png`: P50/P95/P99 over time
- `throughput_over_time.png`: Achieved vs attempted RPS
- `error_rate.png`: Error rate over time

**Interpretation:**
- **Flat latency lines**: Stable performance
- **Rising latency over time**: Degradation (memory leak, connection leak, etc.)
- **Throughput gaps**: System saturated (achieved < attempted)
- **Error spikes**: Overload, timeouts, or database issues

---

## Common Issues and Troubleshooting

### Issue 1: Low Achieved Throughput

**Symptom:** `achieved_rps` << `attempted_rps` (e.g., 300 vs 500)

**Possible Causes:**
- Connection pool exhausted (`poolSize` too small)
- Database CPU saturated
- Long-running queries blocking workers

**Diagnosis:**
```bash
# Check active connections
psql -U benchuser -d benchdb -c "SELECT count(*) FROM pg_stat_activity WHERE state = 'active';"

# Check slow queries
psql -U benchuser -d benchdb -c "SELECT query, calls, mean_exec_time FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 10;"

# Check database CPU
top -u postgres
```

**Solutions:**
- Increase `poolSize`
- Optimize slow queries (add indexes)
- Scale database (more CPU, tune PostgreSQL)

---

### Issue 2: High Error Rate

**Symptom:** `errorRate` > 0.01 (1%)

**Possible Causes:**
- Connection timeouts (pool exhausted)
- Database deadlocks
- Network issues

**Diagnosis:**
```bash
# Check error types in summary.json
jq '.errorsByType' results/*/summary.json

# Check PostgreSQL logs
sudo tail -f /var/log/postgresql/postgresql-15-main.log
```

**Solutions:**
- Increase `connectionTimeout` in config
- Increase `poolSize`
- Fix application logic causing deadlocks

---

### Issue 3: P95 Latency Spikes

**Symptom:** Periodic spikes in `p95_ms` in timeseries

**Possible Causes:**
- Java GC pauses
- PostgreSQL checkpoint writes
- Disk I/O contention

**Diagnosis:**
```bash
# Check GC pauses in summary.json
jq '.gcPauseMsTotal' results/*/summary.json

# Check PostgreSQL checkpoints
psql -U benchuser -d benchdb -c "SELECT * FROM pg_stat_bgwriter;"

# Check disk I/O
iostat -x 1
```

**Solutions:**
- Tune Java GC (e.g., `-XX:+UseG1GC -XX:MaxGCPauseMillis=20`)
- Tune PostgreSQL checkpoints (`checkpoint_completion_target`)
- Use faster disk (SSD with high IOPS)

---

## Best Practices

1. **Always run warmup** before collecting metrics
2. **Reset pg_stat_statements** before each run
3. **Run multiple iterations** (3-5) and aggregate results
4. **Monitor system resources** during benchmark (CPU, RAM, disk I/O)
5. **Document configuration changes** in environment snapshot
6. **Use consistent random seed** (42) for reproducible data
7. **Isolate benchmark** from other workloads on the same machine
8. **Verify database connections** match expected pool size

---

## Quick Reference

### Essential Commands

```bash
# Build tool
./gradlew installDist

# Initialize database
bench init-db -u jdbc:postgresql://localhost:5432/benchdb --username benchuser --password benchpass

# Run single benchmark
bench run --config my-config.yaml --output results/

# Run capacity sweep
bench sweep --config my-config.yaml --slo-p95-ms 50 --error-rate-threshold 0.001

# Aggregate multi-instance results
bench aggregate --input results/raw/{timestamp}/{mode}/{workload}/ --output results/aggregated/

# Capture environment
bench env-snapshot --output results/
```

### Configuration Checklist

- [ ] Database URL, username, password correct
- [ ] `connectionMode` selected (HIKARI_DIRECT, HIKARI_DISCIPLINED, OJP, PGBOUNCER)
- [ ] `poolSize` or `dbConnectionBudget`/`replicas` configured
- [ ] `workload.type` selected (W1_READ_ONLY, W2_MIXED, etc.)
- [ ] `openLoop` = true (for rate-based) or false (for concurrency-based)
- [ ] `targetRps` or `concurrency` set
- [ ] `warmupSeconds`, `durationSeconds`, `cooldownSeconds` configured
- [ ] `outputDir` specified

---

## Next Steps

- Review [CONFIG.md](CONFIG.md) for complete configuration reference
- Review [RESULTS_FORMAT.md](RESULTS_FORMAT.md) for detailed results schemas
- See `examples/` directory for more configuration templates
