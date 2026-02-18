# OJP Performance Benchmark Tool - Configuration Reference

Complete reference for all configuration parameters with descriptions, defaults, and examples.

## Table of Contents
- [Configuration File Format](#configuration-file-format)
- [Database Configuration](#database-configuration)
- [Connection Modes](#connection-modes)
- [Pool Configuration](#pool-configuration)
- [Workload Configuration](#workload-configuration)
- [Load Mode Settings](#load-mode-settings)
- [Phase Durations](#phase-durations)
- [Workload Types](#workload-types)
- [Parameter Distributions](#parameter-distributions)
- [Data Generation Settings](#data-generation-settings)
- [Metrics Configuration](#metrics-configuration)
- [Sweep Configuration](#sweep-configuration)
- [Example Configurations](#example-configurations)

---

## Configuration File Format

Configuration files use YAML format. All parameters are case-sensitive.

**Minimal Configuration:**
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
  targetRps: 500
```

---

## Database Configuration

### `database` (object, required)

Database connection parameters.

#### `database.jdbcUrl` (string, required)

JDBC connection URL for the database.

**Format:** `jdbc:postgresql://<host>:<port>/<database>`

**Examples:**
```yaml
# Local PostgreSQL
jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb"

# Remote PostgreSQL with SSL
jdbcUrl: "jdbc:postgresql://db.example.com:5432/benchdb?ssl=true&sslmode=require"

# OJP Gateway
jdbcUrl: "jdbc:postgresql://ojp-gateway:5432/benchdb"

# PgBouncer
jdbcUrl: "jdbc:postgresql://pgbouncer-host:6432/benchdb"
```

**Additional Connection Parameters:**
```yaml
# Enable prepared statement cache (default: enabled in code)
jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb?preparedStatementCacheQueries=250"

# Set application name
jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb?ApplicationName=BenchmarkTool"

# Timeout settings
jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb?loginTimeout=10&connectTimeout=10&socketTimeout=60"
```

#### `database.username` (string, required)

Database username for authentication.

**Example:**
```yaml
username: "benchuser"
```

#### `database.password` (string, required)

Database password for authentication.

**Example:**
```yaml
password: "benchpass"
```

**Security Note:** For production use, consider using environment variables:
```yaml
password: "${DB_PASSWORD}"  # Read from environment variable
```

---

## Connection Modes

### `connectionMode` (enum, required)

Selects the System Under Test (SUT) type.

**Values:**
- `HIKARI_DIRECT`: Direct JDBC connections via HikariCP
- `HIKARI_DISCIPLINED`: HikariCP with connection budget discipline
- `OJP`: Open JDBC Pooler gateway
- `PGBOUNCER`: PgBouncer external pooler

**Examples:**

#### HIKARI_DIRECT
```yaml
connectionMode: HIKARI_DIRECT
poolSize: 20
```

#### HIKARI_DISCIPLINED
```yaml
connectionMode: HIKARI_DISCIPLINED
dbConnectionBudget: 100
replicas: 16
maxPoolSizePerReplica: 50
```

#### OJP
```yaml
connectionMode: OJP
poolSize: 2  # Minimal client-side connections
database:
  jdbcUrl: "jdbc:postgresql://ojp-gateway:5432/benchdb"
```

#### PGBOUNCER
```yaml
connectionMode: PGBOUNCER
poolSize: 2  # Minimal client-side connections
database:
  jdbcUrl: "jdbc:postgresql://pgbouncer-host:6432/benchdb"
```

---

## Pool Configuration

### `poolSize` (integer, default: 20)

Number of database connections in the pool for HIKARI_DIRECT, OJP, and PGBOUNCER modes.

**Range:** 1 to 1000

**Examples:**
```yaml
# Small pool for OJP/PgBouncer (server handles pooling)
poolSize: 2

# Medium pool for typical JDBC application
poolSize: 20

# Large pool for high-concurrency workload
poolSize: 100
```

**Guidelines:**
- **HIKARI_DIRECT:** Size based on workload concurrency (typically 10-50)
- **OJP/PGBOUNCER:** Use minimal size (2-5) since server handles pooling
- **Rule of thumb:** `poolSize ≈ (core_count × 2) + effective_spindle_count`

---

### Disciplined Pooling Parameters

Used only when `connectionMode: HIKARI_DISCIPLINED`.

#### `dbConnectionBudget` (integer, required for HIKARI_DISCIPLINED)

Total database connections available across all replicas.

**Example:**
```yaml
dbConnectionBudget: 100  # Total connections for all replicas
```

#### `replicas` (integer, default: 1)

Number of replica instances that will run concurrently.

**Example:**
```yaml
replicas: 16  # 16 concurrent instances
```

**Pool Size Calculation:**
```
poolSize per replica = floor(dbConnectionBudget / replicas)
```

**Example:** `100 / 16 = 6.25` → **6 connections per replica**

#### `maxPoolSizePerReplica` (integer, default: 50)

Safety limit to prevent a single replica from using too many connections.

**Example:**
```yaml
maxPoolSizePerReplica: 50
```

**Use Case:** Prevents misconfiguration where `dbConnectionBudget / replicas` is too large.

---

## Workload Configuration

### `workload` (object, required)

Defines the workload characteristics and load pattern.

### `workload.type` (enum, required)

Type of workload to execute.

**Values:**
- `W1_READ_ONLY`: Read-only workload (QueryA + QueryB)
- `W2_READ_WRITE`: Write-heavy OLTP workload (order creation)
- `W2_MIXED`: Mixed read/write workload (configurable ratios)
- `W2_WRITE_ONLY`: Write-only variant
- `W3_SLOW_QUERY`: Mixed workload with slow queries

**Examples:**

#### W1_READ_ONLY
```yaml
workload:
  type: W1_READ_ONLY
  queryAPercent: 0.30  # 30% QueryA, 70% QueryB
```

**Queries:**
- **QueryA (30%):** `SELECT * FROM accounts WHERE id = ?`
- **QueryB (70%):** `SELECT * FROM orders WHERE account_id = ? ORDER BY order_date DESC LIMIT 20`

#### W2_READ_WRITE
```yaml
workload:
  type: W2_READ_WRITE
```

**Transaction:**
1. `INSERT INTO orders (account_id, total, status) VALUES (?, 0, 'pending')`
2. `INSERT INTO order_lines (order_id, item_id, quantity, price) VALUES (?, ?, ?, ?)` (1-5 times)
3. `UPDATE orders SET total = ? WHERE id = ?`
4. `COMMIT`

#### W2_MIXED
```yaml
workload:
  type: W2_MIXED
  queryAPercent: 0.30  # 30% read queries
  writePercent: 0.20   # 20% write transactions (50% neither = QueryB)
```

**Distribution:**
- 30%: QueryA (account lookup)
- 20%: Write transaction (order creation)
- 50%: QueryB (recent orders)

#### W3_SLOW_QUERY
```yaml
workload:
  type: W3_SLOW_QUERY
  slowQueryPercent: 0.01  # 1% slow queries
```

**Mix:**
- 1%: Slow query (aggregation, table scan)
- 99%: Fast queries (indexed lookups)

**Use Case:** Testing tail latency behavior under mixed fast/slow workload.

---

## Load Mode Settings

### `workload.openLoop` (boolean, required)

Controls load generation mode.

**Values:**
- `true`: Open-loop (rate-based, independent of response time)
- `false`: Closed-loop (concurrency-based, waits for response)

#### Open-Loop Mode

**Configuration:**
```yaml
workload:
  openLoop: true
  targetRps: 500  # Required for open-loop
```

**Behavior:**
- Attempts to send exactly `targetRps` requests per second
- Does not wait for responses before sending next request
- More realistic for testing capacity limits
- Can overload the system if `targetRps` exceeds capacity

**Thread Calculation:**
```
numThreads = max(4, min(targetRps / 10, 200))
```

**Example:** For `targetRps: 500`, uses `50` threads.

#### Closed-Loop Mode

**Configuration:**
```yaml
workload:
  openLoop: false
  concurrency: 20  # Required for closed-loop
```

**Behavior:**
- Maintains exactly `concurrency` concurrent requests
- Each thread waits for response before sending next request
- Measures system throughput at fixed concurrency
- Cannot exceed natural throughput limit

**Use Case:** Testing throughput at specific concurrency levels (e.g., "What's the throughput with 50 concurrent users?").

---

### `workload.targetRps` (double, required if openLoop: true)

Target requests per second for open-loop mode.

**Range:** 0.1 to 100000.0

**Examples:**
```yaml
# Low load
targetRps: 50

# Medium load
targetRps: 500

# High load
targetRps: 5000

# Very high load (stress test)
targetRps: 20000
```

### `workload.concurrency` (integer, required if openLoop: false)

Number of concurrent workers for closed-loop mode.

**Range:** 1 to 10000

**Examples:**
```yaml
# Low concurrency
concurrency: 5

# Medium concurrency
concurrency: 20

# High concurrency
concurrency: 100

# Very high concurrency
concurrency: 500
```

---

## Phase Durations

### `workload.warmupSeconds` (integer, default: 300)

Duration of warmup phase in seconds.

**Purpose:** 
- Populate database caches
- JIT compile hot code paths
- Stabilize connection pools
- Populate HikariCP prepared statement cache

**Examples:**
```yaml
# Short warmup (quick tests)
warmupSeconds: 60

# Standard warmup
warmupSeconds: 300

# Long warmup (large datasets)
warmupSeconds: 600
```

**Recommendation:** At least 300 seconds for stable results.

---

### `workload.durationSeconds` (integer, default: 600)

Duration of measurement phase in seconds.

**Purpose:** Collect metrics during steady-state operation.

**Examples:**
```yaml
# Short test (quick validation)
durationSeconds: 60

# Standard test
durationSeconds: 600

# Long test (stability analysis)
durationSeconds: 3600
```

**Recommendation:** At least 600 seconds (10 minutes) for reliable percentiles.

---

### `workload.cooldownSeconds` (integer, default: 120)

Duration of cooldown phase in seconds.

**Purpose:** 
- Allow pending transactions to complete
- Flush metrics buffers
- Graceful shutdown

**Examples:**
```yaml
# Minimal cooldown
cooldownSeconds: 30

# Standard cooldown
cooldownSeconds: 120

# Extended cooldown
cooldownSeconds: 300
```

---

### `workload.repeatCount` (integer, default: 5)

Number of times to repeat the benchmark (for sweep tests).

**Examples:**
```yaml
# Single run
repeatCount: 1

# Standard repetitions (for averaging)
repeatCount: 5

# Many repetitions (for statistical significance)
repeatCount: 10
```

**Use Case:** Sweep tests run each RPS level `repeatCount` times and report median results.

---

## Workload Types

### Workload-Specific Parameters

#### `workload.queryAPercent` (double, default: 0.30)

Percentage of requests that are QueryA (account lookup).

**Used by:** W1_READ_ONLY, W2_MIXED

**Range:** 0.0 to 1.0

**Examples:**
```yaml
# 30% QueryA, 70% other
queryAPercent: 0.30

# 50% QueryA, 50% other
queryAPercent: 0.50

# Read-heavy: 80% QueryA, 20% other
queryAPercent: 0.80
```

---

#### `workload.writePercent` (double, default: 0.20)

Percentage of requests that are write transactions (order creation).

**Used by:** W2_MIXED

**Range:** 0.0 to 1.0

**Examples:**
```yaml
# 20% writes
writePercent: 0.20

# Write-heavy: 50% writes
writePercent: 0.50

# Write-only (with W2_MIXED)
queryAPercent: 0.0
writePercent: 1.0
```

**Combined Example:**
```yaml
workload:
  type: W2_MIXED
  queryAPercent: 0.30  # 30% QueryA
  writePercent: 0.20   # 20% writes
  # Remaining 50% = QueryB (recent orders)
```

---

#### `workload.slowQueryPercent` (double, default: 0.01)

Percentage of requests that are slow queries.

**Used by:** W3_SLOW_QUERY

**Range:** 0.0 to 1.0

**Examples:**
```yaml
# 1% slow queries (realistic)
slowQueryPercent: 0.01

# 5% slow queries (more stress)
slowQueryPercent: 0.05

# 10% slow queries (high contention)
slowQueryPercent: 0.10
```

**Slow Query Example:**
```sql
SELECT 
  a.id, 
  COUNT(o.id) as order_count,
  SUM(o.total) as total_spent
FROM accounts a
LEFT JOIN orders o ON o.account_id = a.id
GROUP BY a.id
ORDER BY total_spent DESC
LIMIT 100;
```

---

## Parameter Distributions

### `workload.useZipf` (boolean, default: false)

Enable Zipf distribution for account ID selection (skewed access pattern).

**Examples:**
```yaml
# Uniform distribution (all accounts equally likely)
useZipf: false

# Zipf distribution (realistic hotspot behavior)
useZipf: true
zipfAlpha: 1.1
```

**Use Case:** 
- Models real-world access patterns where some accounts are "hot" (frequently accessed)
- Tests connection pooling and caching under skewed load

---

### `workload.zipfAlpha` (double, default: 1.1)

Zipf distribution skew parameter (only used if `useZipf: true`).

**Range:** 0.5 to 3.0

**Interpretation:**
- **Lower α (0.5-0.9):** Mild skew, relatively uniform access
- **Medium α (1.0-1.5):** Moderate skew, realistic for most applications
- **Higher α (1.5-3.0):** Extreme skew, very "hot" accounts

**Examples:**
```yaml
# Mild skew
zipfAlpha: 0.8

# Moderate skew (typical)
zipfAlpha: 1.1

# Strong skew
zipfAlpha: 1.5

# Extreme skew (power law)
zipfAlpha: 2.0
```

**Distribution Shape:**
- `α = 1.0`: Zipf's law (classic power law)
- `α = 1.1`: 20% of accounts get ~80% of requests (Pareto principle)
- `α = 2.0`: Very few accounts get most requests

---

### `workload.seed` (long, default: 42)

Random seed for reproducible data generation and workload selection.

**Examples:**
```yaml
# Consistent results across runs
seed: 42

# Different data each time
seed: 1707398765  # Timestamp or random value
```

**Use Case:** Use the same seed for reproducible benchmarks when comparing configurations.

---

## Data Generation Settings

### `numAccounts` (integer, default: 10000)

Number of customer accounts to generate during `init-db`.

**Range:** 100 to 10000000

**Examples:**
```yaml
# Small dataset (quick setup)
numAccounts: 1000

# Medium dataset
numAccounts: 10000

# Large dataset (1M accounts)
numAccounts: 1000000
```

**Storage:** ~100 bytes per account

---

### `numItems` (integer, default: 5000)

Number of product items to generate during `init-db`.

**Range:** 100 to 1000000

**Examples:**
```yaml
# Small catalog
numItems: 1000

# Medium catalog
numItems: 5000

# Large catalog
numItems: 100000
```

**Storage:** ~50 bytes per item

---

### `numOrders` (integer, default: 50000)

Number of historical orders to generate during `init-db`.

**Range:** 1000 to 100000000

**Examples:**
```yaml
# Small history
numOrders: 10000

# Medium history
numOrders: 50000

# Large history (10M orders)
numOrders: 10000000
```

**Storage:** 
- ~200 bytes per order
- ~100 bytes per order line (average 2-3 lines per order)

**Total:** ~500 bytes per order (with lines)

---

## Metrics Configuration

### `metricsIntervalSeconds` (integer, default: 1)

Interval for metrics sampling in seconds.

**Range:** 1 to 60

**Examples:**
```yaml
# High-resolution (every second)
metricsIntervalSeconds: 1

# Lower resolution (every 5 seconds)
metricsIntervalSeconds: 5

# Low resolution (every minute)
metricsIntervalSeconds: 60
```

**Trade-offs:**
- **1 second:** High resolution, larger output files
- **5 seconds:** Balanced resolution and file size
- **60 seconds:** Low resolution, minimal overhead

**Output:** Each interval generates one row in `timeseries.csv`.

---

## Sweep Configuration

Used by the `sweep` command to find maximum sustainable throughput.

### `sweepIncrementPercent` (double, default: 15.0)

Percentage to increase RPS at each sweep level.

**Range:** 5.0 to 100.0

**Examples:**
```yaml
# Fine-grained sweep (slow)
sweepIncrementPercent: 10.0

# Standard sweep
sweepIncrementPercent: 15.0

# Coarse sweep (fast)
sweepIncrementPercent: 25.0
```

**Calculation:**
```
nextRps = currentRps × (1 + sweepIncrementPercent / 100)
```

**Example Progression (15% increment, starting at 100 RPS):**
- Level 1: 100 RPS
- Level 2: 115 RPS
- Level 3: 132 RPS
- Level 4: 152 RPS
- Level 5: 175 RPS

---

### `sloP95Ms` (double, default: 50.0)

Service Level Objective (SLO) for 95th percentile latency in milliseconds.

**Range:** 1.0 to 10000.0

**Examples:**
```yaml
# Strict SLO (interactive services)
sloP95Ms: 10.0

# Standard SLO (web services)
sloP95Ms: 50.0

# Relaxed SLO (batch processing)
sloP95Ms: 500.0
```

**Sweep Behavior:** Stops increasing RPS when `p95 > sloP95Ms`.

---

### `errorRateThreshold` (double, default: 0.001)

Maximum acceptable error rate (fraction of failed requests).

**Range:** 0.0 to 1.0

**Examples:**
```yaml
# Strict: 0.1% errors
errorRateThreshold: 0.001

# Moderate: 1% errors
errorRateThreshold: 0.01

# Relaxed: 5% errors
errorRateThreshold: 0.05
```

**Sweep Behavior:** Stops increasing RPS when `errorRate > errorRateThreshold`.

---

### `outputDir` (string, default: "results")

Base directory for output files.

**Examples:**
```yaml
# Relative path
outputDir: "results"

# Absolute path
outputDir: "/data/benchmark/results"

# Organized by date
outputDir: "results/2024-02-18"
```

**Directory Structure:**
```
{outputDir}/
├── raw/
│   └── {timestamp}/
│       └── {connectionMode}/
│           └── {workloadType}/
│               └── instance_{id}/
└── aggregated/
    └── {timestamp}/
```

---

## Example Configurations

### Example 1: Baseline HIKARI_DIRECT Test

**File:** `hikari-baseline.yaml`

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
  targetRps: 500
  warmupSeconds: 300
  durationSeconds: 600
  cooldownSeconds: 120
  queryAPercent: 0.30
  writePercent: 0.20
  useZipf: false
  seed: 42

numAccounts: 10000
numItems: 5000
numOrders: 50000

metricsIntervalSeconds: 1
outputDir: "results"
```

**Run:**
```bash
bench run --config hikari-baseline.yaml --output results/baseline/
```

---

### Example 2: Disciplined Pooling with 16 Replicas

**File:** `disciplined-16-replicas.yaml`

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

numAccounts: 10000
numItems: 5000
numOrders: 50000

metricsIntervalSeconds: 1
outputDir: "results"
```

**Run (all 16 replicas):**
```bash
for i in {0..15}; do
  bench run --config disciplined-16-replicas.yaml --instance-id $i --output results/disciplined/ &
done
wait
```

---

### Example 3: OJP Gateway Test

**File:** `ojp-mode.yaml`

```yaml
database:
  jdbcUrl: "jdbc:postgresql://ojp-gateway:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: OJP
poolSize: 2  # Minimal client-side connections

workload:
  type: W2_READ_WRITE
  openLoop: true
  targetRps: 1000
  warmupSeconds: 300
  durationSeconds: 600
  cooldownSeconds: 120

numAccounts: 10000
numItems: 5000
numOrders: 50000

metricsIntervalSeconds: 1
outputDir: "results"
```

**Run:**
```bash
bench run --config ojp-mode.yaml --output results/ojp/
```

---

### Example 4: PgBouncer Test

**File:** `pgbouncer-mode.yaml`

```yaml
database:
  jdbcUrl: "jdbc:postgresql://pgbouncer-host:6432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: PGBOUNCER
poolSize: 2  # Minimal client-side connections

workload:
  type: W1_READ_ONLY
  openLoop: true
  targetRps: 2000
  warmupSeconds: 300
  durationSeconds: 600
  cooldownSeconds: 120
  queryAPercent: 0.30

numAccounts: 10000
numItems: 5000
numOrders: 50000

metricsIntervalSeconds: 1
outputDir: "results"
```

**Run:**
```bash
bench run --config pgbouncer-mode.yaml --output results/pgbouncer/
```

---

### Example 5: Capacity Sweep

**File:** `sweep-config.yaml`

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
  repeatCount: 3
  queryAPercent: 0.30
  writePercent: 0.20

numAccounts: 10000
numItems: 5000
numOrders: 50000

metricsIntervalSeconds: 1

# Sweep configuration
sweepIncrementPercent: 15.0
sloP95Ms: 50
errorRateThreshold: 0.001

outputDir: "results"
```

**Run:**
```bash
bench sweep --config sweep-config.yaml --output results/sweep/
```

---

### Example 6: Read-Only with Zipf Distribution

**File:** `w1-read-only-zipf.yaml`

```yaml
database:
  jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: HIKARI_DIRECT
poolSize: 20

workload:
  type: W1_READ_ONLY
  openLoop: true
  targetRps: 1000
  warmupSeconds: 300
  durationSeconds: 600
  cooldownSeconds: 120
  queryAPercent: 0.30
  useZipf: true
  zipfAlpha: 1.1
  seed: 42

numAccounts: 10000
numItems: 5000
numOrders: 50000

metricsIntervalSeconds: 1
outputDir: "results"
```

**Run:**
```bash
bench run --config w1-read-only-zipf.yaml --output results/zipf/
```

---

### Example 7: Slow Query Workload

**File:** `w3-slow-query.yaml`

```yaml
database:
  jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: HIKARI_DIRECT
poolSize: 50  # Higher pool for slow queries

workload:
  type: W3_SLOW_QUERY
  openLoop: true
  targetRps: 200
  warmupSeconds: 300
  durationSeconds: 600
  cooldownSeconds: 120
  slowQueryPercent: 0.01  # 1% slow queries

numAccounts: 10000
numItems: 5000
numOrders: 50000

metricsIntervalSeconds: 1
outputDir: "results"
```

**Run:**
```bash
bench run --config w3-slow-query.yaml --output results/slow-query/
```

---

### Example 8: Closed-Loop Concurrency Test

**File:** `closed-loop-50-concurrent.yaml`

```yaml
database:
  jdbcUrl: "jdbc:postgresql://localhost:5432/benchdb"
  username: "benchuser"
  password: "benchpass"

connectionMode: HIKARI_DIRECT
poolSize: 50

workload:
  type: W2_MIXED
  openLoop: false  # Closed-loop mode
  concurrency: 50  # 50 concurrent workers
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

**Run:**
```bash
bench run --config closed-loop-50-concurrent.yaml --output results/closed-loop/
```

---

## Configuration Validation

The tool validates configuration on startup and reports errors:

**Common Errors:**
- Missing required fields (`database.jdbcUrl`, `connectionMode`, `workload.type`)
- Invalid enum values (e.g., `connectionMode: INVALID`)
- Conflicting parameters (e.g., `openLoop: true` without `targetRps`)
- Out-of-range values (e.g., `poolSize: -1`)

**Validation Example:**
```bash
bench run --config my-config.yaml --output results/
# Error: Missing required field 'workload.targetRps' (openLoop mode requires targetRps)
```

---

## Environment Variable Substitution

Configuration files support environment variable substitution using `${VAR_NAME}` syntax:

**Example:**
```yaml
database:
  jdbcUrl: "jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"
  username: "${DB_USER}"
  password: "${DB_PASSWORD}"
```

**Run with environment variables:**
```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=benchdb
export DB_USER=benchuser
export DB_PASSWORD=benchpass

bench run --config my-config.yaml --output results/
```

---

## Tips and Best Practices

1. **Start with low RPS** and increase gradually to find capacity limits
2. **Use open-loop mode** for capacity testing (more realistic than closed-loop)
3. **Run warmup** before collecting metrics (at least 300 seconds)
4. **Repeat tests** 3-5 times and aggregate results for statistical significance
5. **Use Zipf distribution** for realistic hotspot testing
6. **Monitor database** during tests (CPU, disk I/O, active connections)
7. **Document configuration** in environment snapshot
8. **Use consistent seed** (42) for reproducible results across runs
9. **Tune pool size** based on workload: `poolSize ≈ 2 × CPU cores` is a good starting point
10. **Set realistic SLOs** (P95 < 50ms is typical for web services)

---

## Next Steps

- Review [RUNBOOK.md](RUNBOOK.md) for step-by-step operational guide
- Review [RESULTS_FORMAT.md](RESULTS_FORMAT.md) for results schemas
- See `examples/` directory for more configuration templates
- Run `bench --help` for command-line options
