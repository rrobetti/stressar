# Metrics Reference — What Is Measured and How

This document describes every metric the OJP Performance Benchmark Tool collects,
the mechanism used to collect it, the output file it appears in, and how to
interpret it.

---

## Table of Contents

1. [Measurement Scope (What the Latency Clock Covers)](#1-measurement-scope)
2. [Collection Pipeline — How Metrics Flow](#2-collection-pipeline)
3. [Output Files](#3-output-files)
   - [timeseries.csv — Per-second metrics](#timeseriescvs)
   - [summary.json — Aggregate metrics](#summaryjson)
   - [hdr.hlog — HDR histogram log](#hdrhlog)
4. [Metric Catalogue — Bench Client (Currently Collected)](#4-metric-catalogue)
   - [Latency metrics](#latency-metrics)
   - [Throughput metrics](#throughput-metrics)
   - [Error metrics](#error-metrics)
   - [Open-loop correctness metrics](#open-loop-correctness-metrics)
   - [OJP-specific metrics](#ojp-specific-metrics)
5. [Node-Level Metrics — Collection Status and Gaps](#5-node-level-metrics)
   - [Bench / control node](#bench--control-node)
   - [OJP proxy node — JVM heap vs OS RSS](#ojp-proxy-node--jvm-heap-vs-os-rss)
   - [PostgreSQL DB node](#postgresql-db-node)
6. [Workload Operations (What Generates Load)](#6-workload-operations)
7. [Environment Snapshot](#7-environment-snapshot)
8. [Multi-Replica Aggregation](#8-multi-replica-aggregation)
9. [Limitations and Known Issues](#9-limitations-and-known-issues)

---

## 1. Measurement Scope

The latency clock covers **everything the application sees**: connection
acquisition from the pool *plus* the full SQL round-trip, including result-set
consumption.

```
  ┌───── latency window ────────────────────────────────────────────────┐
  │                                                                      │
  t₀ = System.nanoTime()                                      t₁ = System.nanoTime()
  │                                                                      │
  connectionProvider.getConnection()  ──► executeQuery()  ──► rs.next() │
  └──────────────────────────────────────────────────────────────────────┘

  latencyNanos = t₁ − t₀
```

Source: `LoadGenerator.executeWorkload()` in `src/main/java/com/bench/load/LoadGenerator.java`.

**What is included:** connection-pool wait, TCP round-trip to DB or proxy, query
execution on PostgreSQL, result-set transmission and iteration.

**What is excluded:** JVM scheduling jitter between the dispatcher thread
submitting the task and the worker thread starting it. This overhead is captured
separately as the *scheduling delay* metric (see §4).

---

## 2. Collection Pipeline

```
Worker Thread                  Interval Sampler (1 s)         Output Files
─────────────────────────────  ────────────────────────────── ───────────────
executeWorkload()
  t₀ = nanoTime()
  workload.execute()           ─── every N seconds ───►
  t₁ = nanoTime()                                             timeseries.csv
  metrics.recordSuccess(t₁-t₀)  intervalMetrics.getSnapshot()   (row/second)
                               ← intervalMetrics.reset()
  metrics.recordAttempt()
  (error) →
  metrics.recordError(type)                                   summary.json
                                                               (at run end)
                                                             hdr.hlog
                                                              (at run end)
```

There are **two** `MetricsCollector` instances running in parallel:

| Collector | Purpose | Reset cadence |
|-----------|---------|---------------|
| `metrics` (cumulative) | Tracks totals from warmup-reset onwards; used for `summary.json`. | After warmup phase. |
| `intervalMetrics` (interval) | Tracks per-sampling-window values; used for `timeseries.csv` percentiles. | After every row is written. |

Using a per-interval histogram guarantees that `p99` in row *N* of the CSV reflects
only requests completed in that specific second, not a diluted value across the whole
run. The cumulative histogram in `hdr.hlog` represents the entire steady-state phase.

The sampling interval is controlled by `metricsIntervalSeconds` (default: **1 second**).

Source: `BenchmarkRunner.java`, lines 80–133.

---

## 3. Output Files

### timeseries.csv

Written in real-time during the **steady-state phase only** (warmup and cooldown
are excluded).

| Column | Unit | Source |
|--------|------|--------|
| `timestamp_iso` | ISO 8601 UTC | Wall clock at time of snapshot |
| `attempted_rps` | req/s | Δ(attempted) ÷ Δt since last row |
| `achieved_rps` | req/s | Δ(completed) ÷ Δt since last row |
| `errors` | count | Δ(errors) in this interval |
| `p50_ms` | ms | HdrHistogram p50 — **interval only** |
| `p95_ms` | ms | HdrHistogram p95 — **interval only** |
| `p99_ms` | ms | HdrHistogram p99 — **interval only** |
| `p999_ms` | ms | HdrHistogram p99.9 — **interval only** |
| `max_ms` | ms | HdrHistogram max — **interval only** |

> Percentiles are derived from the **per-interval histogram** that is reset after
> every row. This prevents percentiles from converging toward a run-average over
> time and allows transient spikes to be visible.

File size: ≈ 100–150 bytes/row → ≈ 60–90 KB for a 600 s run per instance.

---

### summary.json

Written once at the end of each `bench run` invocation. Contains the aggregate
view of the entire steady-state phase (warmup excluded, cooldown included up to
the moment the load generator is stopped).

Key fields:

```
runInfo                         — run context (SUT, workload, instance ID, …)
attemptedRps                    — mean attempted RPS over the measurement window
achievedThroughputRps           — mean completed RPS over the measurement window
errorRate                       — failedRequests / (completedRequests + failedRequests)
latencyMs.{p50,p95,p99,p999,max,mean}  — cumulative histogram percentiles (all requests)
errorsByType.{timeout, sql_exception, other}  — error breakdown
appCpuMedian                    — median application CPU % (optional)
appRssMedian                    — median resident set size in MB (optional)
gcPauseMsTotal                  — total JVM GC pause time in ms (optional)
dbActiveConnectionsMedian       — median active backend connections (optional)
queueDepthMax                   — peak connection-pool queue depth (optional)
```

**Open-loop–specific fields** (present when `openLoop: true`):

```
runInfo.openLoopAttemptedOps         — total operations submitted by dispatcher
runInfo.openLoopMissedOpportunities  — send slots skipped because system fell behind
runInfo.openLoopSchedulingDelayMs    — cumulative dispatcher scheduling delay in ms
```

**OJP-specific fields** (present when `connectionMode: OJP`):

```
runInfo.clientPooling                          — always "none" (server-side pooling)
runInfo.ojpVirtualConnectionMode               — PER_WORKER or PER_OPERATION
runInfo.ojpPoolSharing                         — PER_INSTANCE or SHARED
runInfo.clientVirtualConnectionsOpenedTotal    — total virtual connections opened
runInfo.clientVirtualConnectionsMaxConcurrent  — peak concurrent virtual connections
```

Source: `SummaryWriter.java`, `MetricsSnapshot.java`.

---

### hdr.hlog

An HdrHistogram log file covering the entire steady-state phase. Values are stored
in **microseconds** (1 µs resolution, 3 significant digits).

The file can be analysed offline with standard HdrHistogram tooling:

```bash
# View percentile distribution using the HdrHistogram log processor
java -jar HdrHistogram-2.1.12.jar -i results/run-1/hdr.hlog -outputValueUnitRatio 1000

# Or using the online viewer at https://hdrhistogram.github.io/HdrHistogram/plotFiles.html
```

For multi-replica runs, `HistogramAggregator` merges histograms by **adding
counts** (not averaging percentiles), which is the statistically correct way to
combine independent histograms.

Source: `LatencyRecorder.exportToLog()`, `HistogramAggregator.java`.

---

## 4. Metric Catalogue — Bench Client (Currently Collected)

### Latency metrics

| Metric | Description | Collection method |
|--------|-------------|-------------------|
| **p50** (median) | Half of all requests finish faster than this. | `HdrHistogram.getValueAtPercentile(50.0)` |
| **p95** | 95 % of requests finish faster. Primary SLO threshold (default < 50 ms). | `HdrHistogram.getValueAtPercentile(95.0)` |
| **p99** | 99 % of requests finish faster. Indicates tail behaviour. | `HdrHistogram.getValueAtPercentile(99.0)` |
| **p99.9** | 99.9 % of requests finish faster. Captures extreme outliers. | `HdrHistogram.getValueAtPercentile(99.9)` |
| **max** | Worst single latency observed. | `HdrHistogram.getMaxValue()` |
| **mean** | Arithmetic mean. Less useful than percentiles but included for completeness. | `HdrHistogram.getMean()` |

All latency values are reported in **milliseconds** in the output files. The
histogram stores values internally in **microseconds** for precision.

The highest trackable latency is **60,000 ms** (60 seconds). Any latency
exceeding this is clamped to the maximum bucket. Values this large indicate a
completely saturated system; normal operating range is well below 1 second.

---

### Throughput metrics

| Metric | Description |
|--------|-------------|
| `attempted_rps` | Rate at which the dispatcher submitted operations to the worker pool. For open-loop runs this equals `targetRps` under normal conditions; it drops when the dispatcher itself is CPU-limited. |
| `achieved_rps` | Rate at which operations actually completed (success or error). Under saturation, this decouples from `attempted_rps`. The gap between them measures back-pressure. |
| `errorRate` | `failedRequests ÷ (completedRequests + failedRequests)`. SLO threshold: < 0.001 (0.1 %). |

---

### Error metrics

Errors are classified by the Java exception type caught in `LoadGenerator.executeWorkload()`:

| Key | Exception | Meaning |
|-----|-----------|---------|
| `timeout` | `SQLTimeoutException` | Connection or query exceeded the configured timeout |
| `sql_exception` | `SQLException` (non-timeout) | DB error: constraint violation, deadlock, connection refused |
| `other` | Any other `Exception` | Unexpected JVM error |

Each key appears in `summary.json` under `errorsByType`.

---

### Open-loop correctness metrics

These fields appear in `summary.json > runInfo` when `openLoop: true`.

| Field | Description |
|-------|-------------|
| `openLoopAttemptedOps` | Total send-slots the dispatcher processed. |
| `openLoopMissedOpportunities` | Send-slots skipped because `System.nanoTime()` was already past the scheduled send time by more than one interval. A non-zero value means the system is over capacity. |
| `openLoopSchedulingDelayMs` | Cumulative sum of how many nanoseconds late each dispatch was (divided by 1,000,000 for ms). Captures OS and JVM scheduling jitter. |

The dispatcher uses **absolute time-based scheduling** (`nextSendTimeNanos += intervalNanos`).
When the system falls behind, it records the delay and moves forward — it never
issues a burst of catch-up requests. This is critical for correct open-loop
measurement.

Source: `TrueOpenLoopLoadGenerator.java`.

---

### OJP-specific metrics

| Field | Description |
|-------|-------------|
| `clientVirtualConnectionsOpenedTotal` | Total number of virtual JDBC connections opened from this bench instance to the OJP server during the run. High values in `PER_OPERATION` mode indicate connection churn. |
| `clientVirtualConnectionsMaxConcurrent` | Peak number of virtual connections held open simultaneously. |
| `ojpVirtualConnectionMode` | `PER_WORKER`: one virtual connection per worker thread (low churn). `PER_OPERATION`: open/close per SQL operation (tests connection setup overhead). |
| `ojpPoolSharing` | `PER_INSTANCE`: each bench JVM gets its own server-side pool (size = `dbConnectionBudget`). `SHARED`: all replicas share one pool (size = `dbConnectionBudget` total). |

Source: `OjpProvider.java`, `BenchmarkRunner.java`.

---

## 5. Node-Level Metrics — Collection Status and Gaps

The bench client (§4) measures what the *application sees*. To fully understand
system behaviour under load you also need metrics from the three other tiers:
the bench/control node itself, the OJP proxy nodes, and the PostgreSQL DB node.

**Current status: none of these are collected automatically.** The fields
`appCpuMedian`, `appRssMedian`, `gcPauseMsTotal`, `dbActiveConnectionsMedian`,
and `queueDepthMax` exist as schema placeholders in `MetricsSnapshot` but are
**not populated** during a run. Collecting them requires either adding
instrumentation to the bench JVM or running side-car collection scripts on each
node tier during the test window.

---

### Bench / control node

These metrics describe the load-generator process itself. High CPU or memory on
the bench node can skew results (the generator becomes the bottleneck instead of
the SUT).

| Metric | Why it matters | How to collect |
|--------|---------------|----------------|
| **CPU %** | Bench JVM saturating CPU → it can no longer dispatch at the target RPS; `openLoopMissedOpportunities` will spike. | `OperatingSystemMXBean.getProcessCpuLoad()` (in-process) or `vmstat`/`sar` on the host. |
| **JVM heap used / committed** | GC pressure on the bench JVM can introduce stop-the-world pauses that inflate latency measurements. | `java.lang:type=Memory` via JMX or `-verbose:gc` JVM flag. |
| **GC pause total (ms)** | Direct measurement of time the bench JVM was stopped. | `GarbageCollectorMXBean.getCollectionTime()` summed across all collectors. |
| **Thread count** | Number of live threads (dispatcher + worker pool). | `java.lang:type=Threading#ThreadCount` via JMX. |
| **Network TX/RX (bytes/s)** | Confirm the bench node NIC is not the bottleneck. | `sar -n DEV 1` or `/proc/net/dev` polling. |

> **Collection hook:** `MetricsSnapshot.appCpuMedian`, `MetricsSnapshot.gcPauseMsTotal` — already in the schema, not yet wired up.

---

### OJP proxy node — JVM heap vs OS RSS

> ⚠️ **Critical distinction for OJP:** Java reserves virtual memory from the OS and
> holds it even after GC reclaims the objects. This means the **OS-reported RSS
> (Resident Set Size) is not a reliable indicator of actual memory usage**.
> A process showing 2 GB RSS may have only 400 MB of live objects.
>
> **Do NOT use `/proc/<pid>/status VmRSS`, `ps`, or `top` to measure OJP memory.**
> Use JVM-internal heap metrics instead.

| Metric | Why it matters | Correct collection method |
|--------|---------------|--------------------------|
| **JVM heap used** | Actual live object bytes on the heap. The number that correlates with GC frequency and GC pause duration. | `java.lang:type=Memory#HeapMemoryUsage.used` via JMX (`jcmd <pid> VM.native_memory` or `jstat -gc`). |
| **JVM heap committed** | Memory the JVM has actually obtained from the OS and won't return until JVM exit. | `java.lang:type=Memory#HeapMemoryUsage.committed` via JMX. |
| **JVM heap max** | The `-Xmx` ceiling. Heap used approaching heap max → imminent OOME or GC storm. | `java.lang:type=Memory#HeapMemoryUsage.max` via JMX. |
| **GC pause count / duration** | OJP with G1GC or ZGC should have near-zero STW pauses; unexpected long pauses add server-side latency. | `java.lang:type=GarbageCollector,name=*#CollectionCount/Time` via JMX; or parse `-Xlog:gc*` log. |
| **JVM thread count** | Number of server-side handler threads. Growth indicates thread leaks or pool exhaustion. | `java.lang:type=Threading#ThreadCount` via JMX. |
| **OJP server-side pool occupancy** | How many backend PG connections are in-use vs idle at any given second. | OJP exposes pool stats via its management API / JMX MBean (see OJP server docs). |
| **OJP request queue depth** | Requests waiting for a backend connection slot. Non-zero → pool is saturated. | OJP management API / JMX. |
| **Proxy node CPU %** | Sustained high CPU on OJP → it is the bottleneck, not PG. | `OperatingSystemMXBean.getSystemCpuLoad()` via JMX, or `sar 1` side-car. |
| **Proxy node network TX/RX** | Confirm OJP NIC is not the bottleneck. | `/proc/net/dev` polling or `sar -n DEV`. |

**How to attach to OJP JMX:**

OJP Server exposes standard JMX when started with:

```bash
-Dcom.sun.management.jmxremote.port=9999
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
```

Then during a run, poll every 1 s with:

```bash
# Heap used in MB, printed every second
jstat -gc <ojp-pid> 1000
# Fields: S0C S1C S0U S1U EC EU OC OU MC MU YGC YGCT FGC FGCT GCT
# EU = Eden Used, OU = Old Used — sum these for total heap used
```

For Ansible-automated collection, the `run_benchmarks.yml` playbook should
start a background `jstat` loop on each proxy node before launching bench
replicas and kill it after they complete.

---

### PostgreSQL DB node

PostgreSQL exposes rich internal statistics through its system views. These
should be snapshotted (or polled continuously) during the test window to
understand where DB time is spent.

#### Key statistics views

| View / column | Metric | Why it matters |
|---------------|--------|----------------|
| `pg_stat_activity` | Count of active / idle / waiting backends | Direct view of connection usage; idle-in-transaction connections indicate connection leaks |
| `pg_stat_bgwriter.buffers_checkpoint` | Buffers written by checkpoint | High values → WAL/checkpoint pressure; checkpoint may stall queries |
| `pg_stat_bgwriter.checkpoint_write_time` | Milliseconds spent writing during checkpoints | High → I/O-bound; will cause latency spikes |
| `pg_stat_bgwriter.buffers_clean` | Buffers written by background writer | Non-zero → `shared_buffers` may be too small |
| `pg_stat_database.blks_hit` / `blks_read` | Buffer cache hit ratio = `blks_hit / (blks_hit + blks_read)` | < 99 % hit ratio on OLTP workloads suggests insufficient `shared_buffers` |
| `pg_stat_database.xact_commit` / `xact_rollback` | Transaction rate | Cross-check against bench-reported RPS; rollbacks indicate contention |
| `pg_stat_database.deadlocks` | Deadlock count | Unexpected deadlocks in the test → schema/workload issue |
| `pg_stat_database.temp_bytes` | Temporary file writes | Non-zero → sort/hash spills; `work_mem` may need tuning |
| `pg_locks` | Lock waits | Count rows where `granted = false`; indicates hot-row contention |
| OS CPU on DB node | Total CPU (user + sys) | High sys% → I/O or network; high user% → query execution |
| OS disk I/O (MB/s, IOPS) | Read and write throughput | WAL writes, checkpoints, dirty-page flush |
| OS network RX (MB/s) | Data sent from PG to clients | High → large result sets; saturated NIC |

#### Collection during a run

The simplest approach is a polling loop that runs in parallel with the bench:

```bash
# Run from the DB node (or via psql from the control node) every 5 s
while true; do
  psql -U postgres -c "
    SELECT now(),
           numbackends,
           xact_commit,
           xact_rollback,
           blks_hit,
           blks_read,
           ROUND(blks_hit * 100.0 / NULLIF(blks_hit + blks_read, 0), 2) AS cache_hit_pct,
           temp_bytes,
           deadlocks
    FROM pg_stat_database
    WHERE datname = 'ojp_bench';
    SELECT now(), count(*) AS waiting
    FROM pg_locks WHERE NOT granted;
  " -t >> results/pg_stats.csv
  sleep 5
done
```

This loop should be launched as a background task in the Ansible
`run_benchmarks.yml` playbook (before starting bench replicas) and killed after
they complete.

#### Reset stats before each run

PostgreSQL statistics counters are cumulative since last `pg_stat_reset()`. Always reset before a run to get clean deltas:

```sql
SELECT pg_stat_reset();                       -- resets pg_stat_database, pg_stat_user_tables, etc.
SELECT pg_stat_reset_shared('bgwriter');      -- resets pg_stat_bgwriter
```

The `teardown.yml` Ansible playbook already calls `pg_stat_reset()`.

---

## 6. Workload Operations

The SQL executed by each workload type:

### W1_READ_ONLY

| Mix | SQL |
|-----|-----|
| 30 % Query A | `SELECT account_id, username, email, full_name, balance_cents, status FROM accounts WHERE account_id = ?` |
| 70 % Query B | `SELECT order_id, account_id, created_at, status, total_cents FROM orders WHERE account_id = ? ORDER BY created_at DESC LIMIT 20` |

Each operation opens **one connection** from the pool, executes **one** prepared
statement, and closes the connection.

### W2_READ_WRITE (and W2_MIXED)

The write path is a three-statement **explicit transaction**:

1. `INSERT INTO orders(account_id, created_at, status, total_cents) VALUES (?, ?, 0, 0) RETURNING order_id`
2. `INSERT INTO order_lines(order_id, line_no, item_id, qty, price_cents) SELECT …` — repeated 1–4 times (uniform random)
3. `UPDATE orders SET total_cents = (SELECT COALESCE(SUM(qty * price_cents), …)) WHERE order_id = ?`

`W2_MIXED` mixes W1 reads with W2_READ_WRITE writes at a configurable ratio
(default 80 % read / 20 % write, controlled by `writePercent`).

The **latency clock covers the entire transaction** including the commit.

### W3_SLOW_QUERY

| Mix | SQL |
|-----|-----|
| 99 % fast path | Same as W1 Query B |
| 1 % slow path | `SELECT … FROM orders o JOIN order_lines ol … WHERE o.created_at > (CURRENT_TIMESTAMP - INTERVAL '90 days') GROUP BY … ORDER BY … LIMIT 500` |

The slow query is a full JOIN + GROUP BY + ORDER BY on the last 90 days of orders.
This workload is used to evaluate how the proxy tier handles queries of mixed
duration (head-of-line blocking, server-side pool starvation under slow queries).

---

## 7. Environment Snapshot

Running `bench env-snapshot` captures a point-in-time snapshot of the control
node's environment into `results/env/<timestamp>/`:

| File | Contents |
|------|----------|
| `snapshot.json` | Machine-readable: OS family/version, CPU model/cores/frequency, total RAM, Java version/vendor/JVM flags, Git commit + branch + dirty flag, dependency versions. |
| `snapshot.md` | Human-readable Markdown version of the same data, with placeholder sections for PostgreSQL `postgresql.conf` and PgBouncer `pgbouncer.ini` settings to be filled in manually. |

The snapshot is used for reproducibility: attach it to every published benchmark
result so readers can verify the hardware and software baseline.

Source: `EnvSnapshotCommand.java` (uses [OSHI](https://github.com/oshi/oshi) for
hardware detection).

---

## 8. Multi-Replica Aggregation

When 16 bench JVM replicas run in parallel, each writes its own
`instance_N/summary.json` and `instance_N/hdr.hlog`. Running `bench aggregate`
or `ansible/scripts/generate_report.sh` combines them.

**Correct aggregation for histograms:**
`HistogramAggregator.aggregateHistogramLogs()` merges HDR logs by **adding
histogram bucket counts**. This preserves the true combined distribution. Do not
average percentiles across replicas; that is statistically incorrect.

**Throughput aggregation:**
Total aggregate RPS = sum of `achievedThroughputRps` across all instances.
`generate_report.sh` reports both per-instance mean and aggregate total.

Source: `HistogramAggregator.java`, `ansible/scripts/generate_report.sh`.

---

## 9. Limitations and Known Issues

| # | Issue | Impact |
|---|-------|--------|
| 1 | **Latency clock starts at actual send, not scheduled time.** The gap between when the dispatcher submitted a task and when the worker thread actually started it is not included in the per-operation latency. This gap is tracked as `openLoopSchedulingDelayMs` in aggregate but not per-operation. | Reported latencies may understate end-to-end response time under heavy contention for worker threads. |
| 2 | **No cross-replica clock synchronisation.** Each bench JVM uses its own `System.currentTimeMillis()` for `timestamp_iso`. Merging timeseries from different machines assumes clocks are within NTP-synchronised bounds (≤ 10 ms drift). | Timeseries from LG-1 and LG-2 may have small timestamp offsets. |
| 3 | **Node-level metrics not collected.** CPU, JVM heap, GC, and DB stats for all three node tiers (bench, OJP proxy, PostgreSQL) are not currently gathered during a run. See §5 for what needs to be added. | Cannot correlate client-observed latency with server-side resource saturation without manual collection. |
| 4 | **`appRssMedian` is misleading for OJP.** The schema field measures OS RSS, but JVM processes hold memory from the OS without returning it after GC. RSS overstates live heap usage. Use JMX `HeapMemoryUsage.used` instead — see §5. | Reported OJP memory may be 2–5× the actual live heap. |
| 5 | **HDR histogram highest trackable value is 60 s.** Latencies above 60,000 ms are clamped to the max bucket. | Extremely high tail values during catastrophic saturation are reported as 60,000 ms instead of their true value. |

See [TECHNICAL_ANALYSIS.md](TECHNICAL_ANALYSIS.md) for the full 30-question
correctness analysis of the load model and measurement methodology.

---

*See also: [RESULTS_FORMAT.md](RESULTS_FORMAT.md) — full data schemas and file formats.*
