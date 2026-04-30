# OJP Performance Benchmark Tool - Results Format Reference

Complete reference for result data schemas, file formats, and metric calculations.

> **See also: [METRICS.md](METRICS.md)** — detailed documentation of every metric,
> how it is collected, what the latency clock covers, and the two-histogram design
> used for per-interval vs. cumulative measurements.

## Table of Contents
- [Results Directory Structure](#results-directory-structure)
- [Timeseries CSV Format](#timeseries-csv-format)
- [Summary JSON Schema](#summary-json-schema)
- [HDR Histogram Log Format](#hdr-histogram-log-format)
- [Metadata JSON](#metadata-json)
- [Aggregated Results Format](#aggregated-results-format)
- [Environment Snapshot](#environment-snapshot)
- [Metric Calculations](#metric-calculations)
- [Using Results Data](#using-results-data)

---

## Results Directory Structure

### Single-Instance Run

```
results/
└── raw/
    └── 2024-02-18_143022/              # Timestamp: YYYY-MM-DD_HHMMSS
        └── HIKARI_DIRECT/              # Connection mode
            └── W2_MIXED/               # Workload type
                └── instance_0/         # Instance ID
                    ├── timeseries.csv  # Per-second metrics
                    ├── summary.json    # Aggregate statistics
                    ├── latency.hdr     # HDR histogram log
                    └── metadata.json   # Run metadata
```

### Multi-Instance Run (K Replicas)

```
results/
├── raw/
│   └── 2024-02-18_143022/
│       └── HIKARI_DISCIPLINED/
│           └── W2_MIXED/
│               ├── instance_0/
│               │   ├── timeseries.csv
│               │   ├── summary.json
│               │   ├── latency.hdr
│               │   └── metadata.json
│               ├── instance_1/
│               │   └── ...
│               ├── instance_2/
│               │   └── ...
│               └── ...
│               └── instance_15/
│                   └── ...
└── aggregated/
    └── 2024-02-18_143022/
        └── HIKARI_DISCIPLINED/
            └── W2_MIXED/
                ├── aggregated_summary.json    # Combined metrics
                ├── timeseries_combined.csv    # Merged timeseries
                └── plots/
                    ├── latency_percentiles.png
                    ├── throughput_over_time.png
                    └── error_rate.png
```

---

## Timeseries CSV Format

### File Location
`instance_{id}/timeseries.csv`

### Purpose
Per-second metrics captured during the benchmark steady-state phase.

### Column Schema

| Column Name | Type | Unit | Description |
|-------------|------|------|-------------|
| `timestamp_iso` | String | ISO 8601 | Timestamp of metric snapshot (e.g., `2024-02-18T14:30:45Z`) |
| `attempted_rps` | Double | req/sec | Target RPS (open-loop) or offered load (closed-loop) |
| `achieved_rps` | Double | req/sec | Actual completed requests per second |
| `errors` | Integer | count | Number of failed requests in this second |
| `p50_ms` | Double | milliseconds | 50th percentile (median) latency |
| `p95_ms` | Double | milliseconds | 95th percentile latency |
| `p99_ms` | Double | milliseconds | 99th percentile latency |
| `p999_ms` | Double | milliseconds | 99.9th percentile latency |
| `max_ms` | Double | milliseconds | Maximum latency observed in this second |

### Example Data

```csv
timestamp_iso,attempted_rps,achieved_rps,errors,p50_ms,p95_ms,p99_ms,p999_ms,max_ms
2024-02-18T14:30:00Z,500.00,499.80,0,2.14,8.45,15.23,34.56,45.12
2024-02-18T14:30:01Z,500.00,500.10,0,2.18,8.52,15.67,35.22,46.78
2024-02-18T14:30:02Z,500.00,498.50,2,2.21,9.01,18.34,42.11,78.90
2024-02-18T14:30:03Z,500.00,499.95,0,2.15,8.48,15.89,36.45,47.23
2024-02-18T14:30:04Z,500.00,500.20,1,2.19,8.67,16.23,38.12,65.34
```

### Notes
- Rows are written in real-time (streamed, not buffered)
- Sampling interval controlled by `metricsIntervalSeconds` (default: 1 second)
- Latencies calculated from HDR histogram snapshots
- Only includes steady-state phase (excludes warmup and cooldown)

### Data Size
- Approximately **100-150 bytes per row**
- For 600-second test: ~600 rows = **60-90 KB per instance**
- For 16 replicas: **1-1.5 MB total**

---

## Summary JSON Schema

### File Location
`instance_{id}/summary.json`

### Purpose
Aggregate statistics for the entire benchmark run.

### Schema

```json
{
  "runInfo": {
    "sut": "string",                    // Connection mode (HIKARI_DIRECT, OJP, etc.)
    "workload": "string",               // Workload type (W1_READ_ONLY, W2_MIXED, etc.)
    "loadMode": "string",               // "open-loop" or "closed-loop"
    "targetRps": number,                // Target RPS (open-loop) or null
    "concurrency": number,              // Concurrency (closed-loop) or null
    "poolSize": number,                 // Connection pool size
    "instanceId": number,               // Instance ID (0-based)
    "timestamp": "string",              // ISO 8601 timestamp
    "durationSeconds": number           // Measurement duration
  },
  "attemptedRps": number,               // Average attempted RPS
  "achievedThroughputRps": number,      // Average achieved RPS
  "errorRate": number,                  // Fraction of failed requests (0.0-1.0)
  "latencyMs": {
    "p50": number,                      // Median latency (ms)
    "p95": number,                      // 95th percentile latency (ms)
    "p99": number,                      // 99th percentile latency (ms)
    "p999": number,                     // 99.9th percentile latency (ms)
    "max": number,                      // Maximum latency (ms)
    "mean": number                      // Mean latency (ms)
  },
  "errorsByType": {
    "timeout": number,                  // Connection/query timeouts
    "sql_exception": number,            // SQL errors (deadlock, constraint violation, etc.)
    "connection_error": number,         // Connection acquisition failures
    "unknown": number                   // Uncategorized errors
  },
  "appCpuMedian": number,               // Median application CPU usage (%)
  "appRssMedian": number,               // Median application RSS memory (MB)
  "gcPauseMsTotal": number,             // Total GC pause time (ms)
  "gcPauseCount": number,               // Number of GC pauses
  "dbActiveConnectionsMedian": number,  // Median active DB connections
  "dbIdleConnectionsMedian": number,    // Median idle DB connections
  "totalRequests": number,              // Total requests attempted
  "successfulRequests": number,         // Total successful requests
  "failedRequests": number              // Total failed requests
}
```

### Example Data

```json
{
  "runInfo": {
    "sut": "HIKARI_DIRECT",
    "workload": "W2_MIXED",
    "loadMode": "open-loop",
    "targetRps": 500,
    "concurrency": null,
    "poolSize": 20,
    "instanceId": 0,
    "timestamp": "2024-02-18T14:30:00Z",
    "durationSeconds": 600
  },
  "attemptedRps": 500.0,
  "achievedThroughputRps": 499.85,
  "errorRate": 0.0002,
  "latencyMs": {
    "p50": 2.18,
    "p95": 8.67,
    "p99": 16.23,
    "p999": 38.45,
    "max": 78.90,
    "mean": 4.52
  },
  "errorsByType": {
    "timeout": 5,
    "sql_exception": 2,
    "connection_error": 0,
    "unknown": 3
  },
  "appCpuMedian": 45.2,
  "appRssMedian": 512,
  "gcPauseMsTotal": 1250,
  "gcPauseCount": 38,
  "dbActiveConnectionsMedian": 8,
  "dbIdleConnectionsMedian": 12,
  "totalRequests": 300000,
  "successfulRequests": 299940,
  "failedRequests": 60
}
```

### Field Descriptions

#### `runInfo` Section
- **`sut`**: System Under Test (connection mode)
- **`workload`**: Workload type executed
- **`loadMode`**: Load generation mode
- **`targetRps`**: Target RPS (open-loop only, otherwise null)
- **`concurrency`**: Concurrency level (closed-loop only, otherwise null)
- **`poolSize`**: HikariCP pool size configured
- **`instanceId`**: Replica instance ID (0 for single-instance)
- **`timestamp`**: Start time of measurement phase
- **`durationSeconds`**: Duration of measurement phase

#### Throughput Metrics
- **`attemptedRps`**: Average requests/sec attempted (open-loop target)
- **`achievedThroughputRps`**: Average requests/sec completed successfully
- **`errorRate`**: Fraction of requests that failed (0.0 to 1.0)

#### Latency Metrics (`latencyMs`)
All values in milliseconds, calculated from HDR histogram over entire run:
- **`p50`**: Median latency (50% of requests faster)
- **`p95`**: 95th percentile (95% of requests faster)
- **`p99`**: 99th percentile (99% of requests faster)
- **`p999`**: 99.9th percentile (tail latency)
- **`max`**: Maximum latency observed
- **`mean`**: Arithmetic mean latency

#### Error Breakdown (`errorsByType`)
- **`timeout`**: Connection acquisition or query timeouts
- **`sql_exception`**: SQL errors (deadlock, constraint violation, etc.)
- **`connection_error`**: Failed to acquire connection from pool
- **`unknown`**: Uncategorized errors

#### System Metrics
- **`appCpuMedian`**: Median CPU usage of application process (%)
- **`appRssMedian`**: Median Resident Set Size memory (MB)
- **`gcPauseMsTotal`**: Total time spent in GVM garbage collection (ms)
- **`gcPauseCount`**: Number of GC pause events
- **`dbActiveConnectionsMedian`**: Median number of active DB connections
- **`dbIdleConnectionsMedian`**: Median number of idle connections in pool

#### Request Counts
- **`totalRequests`**: Total requests attempted
- **`successfulRequests`**: Requests completed successfully
- **`failedRequests`**: Requests that encountered errors

---

## HDR Histogram Log Format

### File Location
`instance_{id}/latency.hdr`

### Purpose
High Dynamic Range (HDR) histogram for precise percentile analysis.

### Format
Binary format defined by HdrHistogram library (https://hdrhistogram.github.io/HdrHistogram/).

### Key Features
- **Precision:** 3 significant digits
- **Range:** Up to 60 seconds (60,000 milliseconds)
- **Memory:** Constant space, regardless of observation count
- **Percentiles:** Can calculate any percentile (P50, P95, P99, P99.9, P99.99, etc.)

### Usage with HdrHistogram Tools

#### View Percentiles
```bash
java -jar HdrHistogram.jar -i latency.hdr -o percentiles.txt
```

**Example Output:**
```
Value     Percentile  TotalCount  1/(1-Percentile)
0.500     0.000000    1           1.00
2.100     0.500000    150023      2.00
8.670     0.950000    285044      20.00
16.230    0.990000    297030      100.00
38.450    0.999000    299700      1000.00
78.900    0.999900    299970      10000.00
156.780   1.000000    300000      inf
```

#### Generate Latency Distribution Chart
```bash
java -jar HdrHistogram.jar -i latency.hdr -o latency_chart.png -chart
```

#### Merge Multiple Histograms
```bash
# Merge from all replicas
java -jar HdrHistogram.jar \
  -i instance_0/latency.hdr \
  -i instance_1/latency.hdr \
  -i instance_2/latency.hdr \
  -o merged_latency.hdr
```

### Python Usage

```python
from hdrh.histogram import HdrHistogram

# Load HDR histogram
hist = HdrHistogram.load("instance_0/latency.hdr")

# Get percentiles
p50 = hist.get_value_at_percentile(50.0)
p95 = hist.get_value_at_percentile(95.0)
p99 = hist.get_value_at_percentile(99.0)
p999 = hist.get_value_at_percentile(99.9)

print(f"P50: {p50}ms, P95: {p95}ms, P99: {p99}ms, P99.9: {p999}ms")
```

---

## Metadata JSON

### File Location
`instance_{id}/metadata.json`

### Purpose
Captures configuration and environment details for reproducibility.

### Schema

```json
{
  "config": {
    "database": {
      "jdbcUrl": "string",
      "username": "string"
    },
    "connectionMode": "string",
    "poolSize": number,
    "workload": {
      "type": "string",
      "openLoop": boolean,
      "targetRps": number,
      "concurrency": number,
      "warmupSeconds": number,
      "durationSeconds": number,
      "cooldownSeconds": number
    }
  },
  "environment": {
    "javaVersion": "string",
    "jvmArgs": ["string"],
    "osName": "string",
    "osVersion": "string",
    "cpuCores": number,
    "totalMemoryMb": number,
    "hostname": "string"
  },
  "startTime": "string",
  "endTime": "string"
}
```

### Example Data

```json
{
  "config": {
    "database": {
      "jdbcUrl": "jdbc:postgresql://localhost:5432/benchdb",
      "username": "benchuser"
    },
    "connectionMode": "HIKARI_DIRECT",
    "poolSize": 20,
    "workload": {
      "type": "W2_MIXED",
      "openLoop": true,
      "targetRps": 500,
      "concurrency": null,
      "warmupSeconds": 300,
      "durationSeconds": 600,
      "cooldownSeconds": 120
    }
  },
  "environment": {
    "javaVersion": "11.0.18",
    "jvmArgs": ["-Xmx2G", "-Xms2G", "-XX:+UseG1GC"],
    "osName": "Linux",
    "osVersion": "5.15.0-91-generic",
    "cpuCores": 8,
    "totalMemoryMb": 16384,
    "hostname": "bench-server-01"
  },
  "startTime": "2024-02-18T14:25:00Z",
  "endTime": "2024-02-18T14:42:00Z"
}
```

---

## Aggregated Results Format

### File Location (Multi-Instance)
`aggregated/{timestamp}/{connectionMode}/{workloadType}/aggregated_summary.json`

### Purpose
Combined metrics from all replica instances.

### Schema

```json
{
  "runInfo": {
    "sut": "string",
    "workload": "string",
    "loadMode": "string",
    "totalReplicas": number,
    "totalPoolSize": number,
    "timestamp": "string",
    "durationSeconds": number
  },
  "aggregatedMetrics": {
    "totalAttemptedRps": number,          // Sum across all replicas
    "totalAchievedRps": number,           // Sum across all replicas
    "overallErrorRate": number,           // Weighted average
    "latencyMs": {
      "p50": number,                      // Weighted average
      "p95": number,                      // Weighted average
      "p99": number,                      // Weighted average
      "p999": number,                     // Weighted average
      "max": number,                      // Maximum across all replicas
      "mean": number                      // Weighted average
    },
    "totalErrors": number,                // Sum across all replicas
    "errorsByType": {
      "timeout": number,
      "sql_exception": number,
      "connection_error": number,
      "unknown": number
    }
  },
  "perInstanceMetrics": [
    {
      "instanceId": number,
      "achievedRps": number,
      "errorRate": number,
      "p95Ms": number
    }
  ]
}
```

### Example Data

```json
{
  "runInfo": {
    "sut": "HIKARI_DISCIPLINED",
    "workload": "W2_MIXED",
    "loadMode": "open-loop",
    "totalReplicas": 16,
    "totalPoolSize": 96,
    "timestamp": "2024-02-18T14:30:00Z",
    "durationSeconds": 600
  },
  "aggregatedMetrics": {
    "totalAttemptedRps": 8000.0,
    "totalAchievedRps": 7996.8,
    "overallErrorRate": 0.0004,
    "latencyMs": {
      "p50": 2.34,
      "p95": 9.12,
      "p99": 17.89,
      "p999": 42.56,
      "max": 156.78,
      "mean": 4.78
    },
    "totalErrors": 192,
    "errorsByType": {
      "timeout": 128,
      "sql_exception": 42,
      "connection_error": 8,
      "unknown": 14
    }
  },
  "perInstanceMetrics": [
    {
      "instanceId": 0,
      "achievedRps": 499.80,
      "errorRate": 0.0002,
      "p95Ms": 8.67
    },
    {
      "instanceId": 1,
      "achievedRps": 500.05,
      "errorRate": 0.0003,
      "p95Ms": 9.12
    }
    // ... instances 2-15 ...
  ]
}
```

### Aggregation Methodology

#### Throughput (Additive)
```
totalAchievedRps = sum(instance[i].achievedRps for i in 0..N-1)
```

#### Error Rate (Weighted Average)
```
overallErrorRate = sum(instance[i].failedRequests) / sum(instance[i].totalRequests)
```

#### Latency Percentiles (Weighted Average)
```
p95 = sum(instance[i].p95 * instance[i].totalRequests) / sum(instance[i].totalRequests)
```

**Note:** For precise percentile aggregation, merge HDR histograms instead.

---

## Environment Snapshot

### File Location
`env_snapshot.json`

### Purpose
Capture system and environment information for reproducibility and troubleshooting.

### Command
```bash
bench env-snapshot --output results/my-run/
```

### Schema

```json
{
  "timestamp": "string",
  "system": {
    "osName": "string",
    "osVersion": "string",
    "kernelVersion": "string",
    "hostname": "string"
  },
  "hardware": {
    "cpuModel": "string",
    "cpuCores": number,
    "cpuThreads": number,
    "cpuFrequencyMhz": number,
    "totalMemoryMb": number,
    "availableMemoryMb": number,
    "diskType": "string",
    "diskSizeMb": number
  },
  "java": {
    "version": "string",
    "vendor": "string",
    "jvmArgs": ["string"],
    "maxHeapMb": number,
    "allocatedHeapMb": number
  },
  "postgresql": {
    "version": "string",
    "sharedBuffersMb": number,
    "maxConnections": number,
    "effectiveCacheSizeMb": number,
    "workMemKb": number
  },
  "network": {
    "interfaces": [
      {
        "name": "string",
        "mtu": number,
        "speedMbps": number
      }
    ]
  }
}
```

### Example Data

```json
{
  "timestamp": "2024-02-18T14:20:00Z",
  "system": {
    "osName": "Linux",
    "osVersion": "Ubuntu 22.04.3 LTS",
    "kernelVersion": "5.15.0-91-generic",
    "hostname": "bench-server-01"
  },
  "hardware": {
    "cpuModel": "Intel(R) Xeon(R) CPU E5-2680 v4 @ 2.40GHz",
    "cpuCores": 8,
    "cpuThreads": 16,
    "cpuFrequencyMhz": 2400,
    "totalMemoryMb": 16384,
    "availableMemoryMb": 12456,
    "diskType": "SSD",
    "diskSizeMb": 512000
  },
  "java": {
    "version": "11.0.18",
    "vendor": "Oracle Corporation",
    "jvmArgs": ["-Xmx2G", "-Xms2G", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=20"],
    "maxHeapMb": 2048,
    "allocatedHeapMb": 2048
  },
  "postgresql": {
    "version": "PostgreSQL 15.5 (Ubuntu 15.5-1.pgdg22.04+1)",
    "sharedBuffersMb": 4096,
    "maxConnections": 200,
    "effectiveCacheSizeMb": 12288,
    "workMemKb": 32768
  },
  "network": {
    "interfaces": [
      {
        "name": "eth0",
        "mtu": 1500,
        "speedMbps": 10000
      }
    ]
  }
}
```

---

## Metric Calculations

### Throughput Metrics

#### Attempted RPS
```
attemptedRps = targetRps  (open-loop)
attemptedRps = number of requests attempted / durationSeconds  (closed-loop)
```

#### Achieved RPS
```
achievedRps = successfulRequests / durationSeconds
```

#### Error Rate
```
errorRate = failedRequests / totalRequests
```

### Latency Metrics

All latencies calculated from HdrHistogram recorded during the measurement phase.

#### Percentiles
```
p50 = histogram.getValueAtPercentile(50.0)
p95 = histogram.getValueAtPercentile(95.0)
p99 = histogram.getValueAtPercentile(99.0)
p999 = histogram.getValueAtPercentile(99.9)
max = histogram.getMaxValue()
```

#### Mean
```
mean = histogram.getMean()
```

### System Metrics

#### CPU Usage
Sampled every second using OSHI library:
```java
cpu = osBean.getSystemCpuLoad() * 100
```

Reported as **median** over measurement period to reduce impact of spikes.

#### Memory (RSS)
Resident Set Size in megabytes:
```java
rss = osBean.getCommittedVirtualMemorySize() / (1024 * 1024)
```

Reported as **median** over measurement period.

#### GC Pause Time
```java
gcPauseMsTotal = sum(gc.getCollectionTime() for gc in ManagementFactory.getGarbageCollectorMXBeans())
gcPauseCount = sum(gc.getCollectionCount() for gc in ManagementFactory.getGarbageCollectorMXBeans())
```

#### Database Connections

Queried from HikariCP pool stats:
```java
dbActiveConnections = hikariPool.getActiveConnections()
dbIdleConnections = hikariPool.getIdleConnections()
```

Reported as **median** over measurement period.

---

## Using Results Data

### Analyzing Single-Instance Results

#### Quick Summary
```bash
# View summary JSON
jq '.' results/raw/*/HIKARI_DIRECT/W2_MIXED/instance_0/summary.json

# Extract key metrics
jq '.achievedThroughputRps, .errorRate, .latencyMs.p95' summary.json
```

#### Plot Timeseries
```python
import pandas as pd
import matplotlib.pyplot as plt

# Load timeseries
df = pd.read_csv("timeseries.csv")
df['timestamp_iso'] = pd.to_datetime(df['timestamp_iso'])

# Plot latency percentiles over time
fig, ax = plt.subplots(figsize=(12, 6))
ax.plot(df['timestamp_iso'], df['p50_ms'], label='P50')
ax.plot(df['timestamp_iso'], df['p95_ms'], label='P95')
ax.plot(df['timestamp_iso'], df['p99_ms'], label='P99')
ax.set_xlabel('Time')
ax.set_ylabel('Latency (ms)')
ax.set_title('Latency Percentiles Over Time')
ax.legend()
plt.xticks(rotation=45)
plt.tight_layout()
plt.savefig('latency_over_time.png')
```

#### Check for Degradation
```python
# Check if P95 increases over time (performance degradation)
import numpy as np

time_idx = np.arange(len(df))
p95_trend = np.polyfit(time_idx, df['p95_ms'], 1)[0]

if p95_trend > 0.01:
    print(f"WARNING: P95 latency increasing over time (slope: {p95_trend:.3f} ms/sec)")
else:
    print("Performance stable over time")
```

### Comparing Multiple SUTs

```python
import json

# Load summaries
with open("results/hikari/summary.json") as f:
    hikari = json.load(f)

with open("results/ojp/summary.json") as f:
    ojp = json.load(f)

with open("results/pgbouncer/summary.json") as f:
    pgbouncer = json.load(f)

# Compare throughput
print(f"HIKARI_DIRECT: {hikari['achievedThroughputRps']:.2f} RPS")
print(f"OJP:           {ojp['achievedThroughputRps']:.2f} RPS")
print(f"PGBOUNCER:     {pgbouncer['achievedThroughputRps']:.2f} RPS")

# Compare P95 latency
print(f"\nP95 Latency:")
print(f"HIKARI_DIRECT: {hikari['latencyMs']['p95']:.2f} ms")
print(f"OJP:           {ojp['latencyMs']['p95']:.2f} ms")
print(f"PGBOUNCER:     {pgbouncer['latencyMs']['p95']:.2f} ms")
```

### Aggregating Multi-Instance Results

```bash
# Use the aggregate command
bench aggregate \
  --input results/raw/2024-02-18_143022/HIKARI_DISCIPLINED/W2_MIXED/ \
  --output results/aggregated/2024-02-18_143022/HIKARI_DISCIPLINED/W2_MIXED/

# View aggregated summary
jq '.' results/aggregated/*/aggregated_summary.json
```

### Analyzing Sweep Results

Sweep results contain multiple runs at increasing RPS levels.

```bash
# Find maximum sustainable throughput
find results/sweep/ -name "summary.json" | while read f; do
  rps=$(jq -r '.runInfo.targetRps' "$f")
  p95=$(jq -r '.latencyMs.p95' "$f")
  errors=$(jq -r '.errorRate' "$f")
  echo "$rps,$p95,$errors"
done | sort -n > sweep_results.csv

# Plot sweep curve
cat sweep_results.csv
# 100,8.23,0.0000
# 115,10.45,0.0001
# 132,14.67,0.0002
# ...
```

### Exporting for Analysis Tools

#### Export to Prometheus Format
```bash
# Convert timeseries.csv to Prometheus exposition format
cat timeseries.csv | awk -F',' 'NR>1 {
  print "benchmark_achieved_rps " $3 " " systime()
  print "benchmark_p95_latency_ms " $6 " " systime()
  print "benchmark_errors_total " $4 " " systime()
}'
```

#### Export to InfluxDB Line Protocol
```bash
# Convert timeseries.csv to InfluxDB format
cat timeseries.csv | awk -F',' 'NR>1 {
  ts = $1
  gsub(/[-:]/, "", ts)
  print "benchmark,sut=hikari,workload=w2 achieved_rps=" $3 ",p95_ms=" $6 ",errors=" $4 " " ts
}'
```

---

## Best Practices

1. **Preserve raw results**: Never delete `results/raw/` directories
2. **Use consistent output directories**: Organize by date, SUT, and workload
3. **Archive environment snapshots**: Include with results for reproducibility
4. **Merge HDR histograms**: For precise percentile analysis across replicas
5. **Plot timeseries**: Visual inspection reveals issues not visible in summary stats
6. **Compare runs**: Use same configuration (seed, duration) for fair comparison
7. **Document anomalies**: Note any issues observed during runs
8. **Export to time-series DB**: For long-term trend analysis

---

## Troubleshooting

### Missing Columns in Timeseries CSV
**Cause:** Old version of benchmark tool
**Solution:** Rebuild tool (`./gradlew build`) and re-run

### Empty Summary JSON
**Cause:** Benchmark crashed or was interrupted
**Solution:** Check logs, ensure sufficient warmup/cooldown time

### HDR Histogram Errors
**Cause:** Corrupted or incomplete HDR file
**Solution:** Re-run benchmark, ensure disk space available

### Inconsistent Metrics Across Replicas
**Cause:** System resource contention (CPU, disk I/O)
**Solution:** Run replicas on separate machines or reduce concurrency

---

## Next Steps

- Review [RUNBOOK.md](RUNBOOK.md) for operational guide
- Review [CONFIG.md](CONFIG.md) for configuration reference
- See `examples/` directory for configuration templates
- Consult HdrHistogram documentation for advanced percentile analysis
