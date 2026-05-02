# Benchmark Harness Technical Analysis - Answers to 30 Questions

This document provides detailed answers to questions about the implementation correctness and completeness of Stressar.

---

## 🧠 A) Load Model & Timing Correctness (Highest Priority)

### 1️⃣ Open-loop scheduling

**How exactly are requests scheduled?**

Requests are scheduled using Java's `ScheduledExecutorService` with `scheduleAtFixedRate()`:
- **Multiple dispatcher threads**: `numThreads` threads (calculated as `Math.max(4, Math.min(targetRps / 10, 200))`)
- Each thread schedules tasks at a fixed rate
- Interval calculation: `intervalNanos = 1_000_000_000L / targetRps`
- Each thread schedules at `intervalNanos * numThreads` to distribute load

**Scheduling mechanism:**
```java
scheduler.scheduleAtFixedRate(() -> {
    executeWorkload();
}, 0, intervalNanos * numThreads, TimeUnit.NANOSECONDS);
```

**⚠️ ISSUE**: This is **relative scheduling** (sleep between sends), not absolute time-based scheduling.

**What happens when the system falls behind schedule?**
- Java's `scheduleAtFixedRate` will execute immediately if the scheduled time has passed
- Tasks may accumulate if execution time exceeds interval
- **No explicit token bucket** or backlog tracking
- **No explicit drop logic** - tasks queue up in the executor
- **Potential burst behavior** when catching up after falling behind

**🔴 LIMITATION**: True open-loop requires absolute time scheduling with explicit "missed opportunity" tracking. Current implementation may inadvertently shift to closed-loop behavior under saturation.

---

### 2️⃣ Time measurement

**Is latency measured from scheduling time or actual send time?**

Latency is measured from **actual send time** (when executeWorkload() starts):
```java
long startNanos = System.nanoTime();
try {
    workload.execute();
    long latencyNanos = System.nanoTime() - startNanos;
    metrics.recordSuccess(latencyNanos);
}
```

**✅ Correct**: Uses `System.nanoTime()` (monotonic clock)

**❌ Missing**: No measurement of scheduling delay (time from intended schedule to actual execution)

**Clock drift between replicas:**
- **Not considered** - each replica uses its own wall-clock time
- Timestamps in results are local to each instance
- **⚠️ ISSUE**: Cross-replica time correlation assumes synchronized clocks

---

### 3️⃣ Warmup isolation

**Are histograms reset at the start of the measurement window?**

**✅ YES**: In `BenchmarkRunner.java` lines 138-140:
```java
// Reset metrics after warmup
metrics.reset();
intervalMetrics.reset();
logger.info("Warmup complete, metrics reset");
```

**Are time-series metrics excluding warmup?**
- **✅ YES**: Metrics scheduler starts before warmup, but interval metrics are reset
- Time-series CSV only captures post-warmup data

**Is cooldown excluded from percentiles?**
- **✅ YES**: Load generator is stopped before cooldown phase (line 152)
- Cooldown happens with no active load generation
- **⚠️ CAVEAT**: Final snapshot is taken AFTER cooldown, but no new requests are issued during cooldown

---

## 📊 B) Histogram & Percentile Correctness

### 4️⃣ HDRHistogram usage

**Is a single histogram used for the entire steady-state window?**

**✅ YES**: One histogram in `MetricsCollector` for the entire run after warmup reset

**Configuration:**
- **Precision**: 3 significant digits (line 23 in LatencyRecorder.java)
- **Max expected latency**: 60,000 ms (60 seconds) - line 61 in MetricsCollector.java
- Stored in **microseconds** internally
- Values exceeding max are clamped to max (lines 37-38 in LatencyRecorder.java)

---

### 5️⃣ Cross-replica aggregation

**⚠️ NOT IMPLEMENTED**: Cross-replica histogram merging is NOT currently implemented.

**Current behavior:**
- Each replica writes its own `summary.json` with percentiles
- No automatic merging of HDR histograms across replicas
- **🔴 LIMITATION**: The `aggregate` command is a placeholder and does not properly merge histograms

**Correct approach would be:**
1. Export HDR histogram from each replica
2. Merge histograms using `histogram.add(otherHistogram)`
3. Compute percentiles from merged histogram
4. **Averaging percentiles is mathematically incorrect** ❌

---

### 6️⃣ Time-series percentiles

**Are per-second p95 values computed from per-second histograms?**

**⚠️ ISSUE**: The current implementation has a problem.

Looking at lines 82-119 in `BenchmarkRunner.java`:
- There's a separate `intervalMetrics` collector
- It's reset after each interval (line 119)
- **BUT**: The percentiles written to timeseries come from the cumulative `snapshot`, not interval-specific histograms

**Current behavior:** Percentiles in timeseries.csv are **cumulative** from start of steady-state
**Expected behavior:** Should be computed from per-interval histogram

**🔴 BUG**: Time-series percentiles are cumulative, not per-interval

---

## 🏗 C) Connection & Pooling Fairness

### 7️⃣ DB connection budget

**Is there an explicit DB safe connection budget defined in config?**

**✅ YES**: `dbConnectionBudget` in `BenchmarkConfig.java` (line 19)
- Default value: 100
- Used in disciplined pooling calculation

**Is it enforced in disciplined pooling mode?**

**✅ YES**: In `BenchmarkConfig.calculateDisciplinedPoolSize()` (lines 187-193):
```java
public int calculateDisciplinedPoolSize() {
    if (replicas <= 0) {
        return poolSize;
    }
    int calculated = dbConnectionBudget / replicas;
    return Math.max(1, Math.min(calculated, maxPoolSizePerReplica));
}
```

**Is it logged in summary.json?**

**✅ YES**: Indirectly - `poolSize` is logged, which reflects the disciplined calculation

**⚠️ ENHANCEMENT**: Should explicitly log `dbConnectionBudget` and `replicas` in summary.json

---

### 8️⃣ Pool size enforcement

**Is Hikari maximumPoolSize actually set from calculated value?**

**✅ YES**: In `HikariProvider.java` line 32:
```java
config.setMaximumPoolSize(poolSize);
```

**Are minimumIdle values aligned?**

**⚠️ PARTIAL**: Set to `Math.max(1, poolSize / 2)` (line 33)
- This is reasonable but not exactly aligned with disciplined pooling principles
- May cause pool to shrink below optimal size

**Are connectionTimeout and validation settings consistent across SUTs?**

**✅ YES**: All providers use the same settings:
- `connectionTimeout`: 30000ms
- `idleTimeout`: 600000ms (10 min)
- `maxLifetime`: 1800000ms (30 min)
- Consistent across HikariProvider, OjpProvider, PgbouncerProvider

---

### 9️⃣ OJP vs PgBouncer fairness

**Is OJP DB pool size equal to the disciplined budget?**

**⚠️ NOT CONFIGURABLE**: OJP and PgBouncer use **hardcoded** minimal pool size of 2:
```java
private static final int MINIMAL_POOL_SIZE = 2;
```

**Expected behavior:** OJP should be configured with DB-side pool = disciplined budget

**Are local app pools reduced appropriately?**

**✅ YES**: Local pool is minimal (2 connections)

**Are prepared statement settings identical across modes?**

**✅ YES**: All providers set:
```java
config.addDataSourceProperty("cachePrepStmts", "true");
config.addDataSourceProperty("prepStmtCacheSize", "250");
config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
config.addDataSourceProperty("useServerPrepStmts", "true");
```

---

## 🌐 D) Cluster & Replica Behavior

### 🔟 Replica synchronization

**How is the start barrier implemented?**

**⚠️ NOT IMPLEMENTED**: There is NO start barrier synchronization.

**Current behavior:**
- Each replica starts independently when `bench run` is invoked
- No coordination between replicas
- Start times may differ by seconds or minutes

**🔴 LIMITATION**: Without barrier synchronization, multi-replica results are not truly synchronized

**Expected implementation:**
- Shared coordination service (e.g., file lock, Redis, Zookeeper)
- All replicas wait until a global start timestamp
- Requires external coordination mechanism

---

### 1️⃣1️⃣ Throughput aggregation

**Is achieved throughput summed across replicas per timestamp?**

**⚠️ NOT IMPLEMENTED**: No automatic cross-replica aggregation

**Current behavior:**
- Each replica writes its own `timeseries.csv` and `summary.json`
- Manual aggregation required

**The `aggregate` command is a placeholder** - it doesn't implement proper merging:
```java
logger.info("Aggregation not yet fully implemented");
```

**🔴 LIMITATION**: User must manually sum throughput across replicas

---

### 1️⃣2️⃣ Scale event simulation

**Is there support for changing replica count mid-test?**

**❌ NO**: Not supported

**Is this explicitly documented as a limitation?**

**⚠️ NOT DOCUMENTED**: This limitation should be in documentation

---

### 1️⃣3️⃣ Connection storm behavior

**During replica startup, do instances initialize pools immediately?**

**✅ YES**: HikariCP initializes the minimum pool size immediately

**Is there ramp-up delay to avoid unrealistic thundering herd?**

**❌ NO**: All replicas initialize pools simultaneously
- No staggered startup
- No connection rate limiting

**🔴 ISSUE**: Unrealistic thundering herd during replica startup

**Expected behavior:**
- Gradual pool initialization
- Staggered startup across replicas
- Connection acquisition rate limiting

---

## ⚖️ E) SLO & Capacity Determination

### 1️⃣4️⃣ SLO enforcement logic

**How exactly is "p95 > SLO for 2 consecutive levels" implemented?**

In `SweepCommand.java` lines 129-154:
```java
// Check if this level violates SLO
double medianP95 = calculateMedian(p95Values);
boolean violatesSlo = medianP95 > sloP95Ms;
boolean violatesErrorRate = medianErrorRate > errorRateThreshold;
boolean isUnstable = detectInstability(runResults, medianP95, medianErrorRate);

if (violatesSlo) {
    consecutiveViolations++;
    logger.warn("Level {} violates SLO: p95={} ms > {} ms", level, medianP95, sloP95Ms);
} else {
    consecutiveViolations = 0;
}

if (consecutiveViolations >= 2) {
    logger.info("Stopping sweep: SLO violated for 2 consecutive levels");
    break;
}
```

**Is p95 median across repetitions used?**

**✅ YES**: Uses median p95 across the N repetitions at each level

**Or per-run?**

**No**: Not per-run - uses aggregated median

---

### 1️⃣5️⃣ Instability detection

**How is instability defined?**

In `SweepCommand.java` lines 179-201:
```java
private boolean detectInstability(List<RunResult> runs, double medianP95, double medianErrorRate) {
    // High variance in p95 suggests instability
    double p95Variance = calculateVariance(runs.stream()...);
    double p95Cv = Math.sqrt(p95Variance) / medianP95;
    
    // Error rate spike
    boolean errorSpike = medianErrorRate > errorRateThreshold * INSTABILITY_ERROR_RATE_MULTIPLIER;
    
    // High coefficient of variation in p95
    boolean highVariance = p95Cv > 0.3;
    
    return errorSpike || highVariance;
}
```

**Criteria:**
1. **Throughput collapse**: Not explicitly checked
2. **Tail divergence**: High CV (>30%) in p95 across repetitions
3. **Error spike**: Error rate > 3x threshold

**Is this formalized or heuristic?**

**Heuristic**: Based on reasonable thresholds but not rigorously derived

---

## 📈 F) Metrics Completeness

### 1️⃣6️⃣ DB metrics

**Are DB CPU and IO wait captured automatically?**

**❌ NO**: Not captured automatically

**Are they placeholders in summary.json?**

**✅ YES**: Optional fields exist in `MetricsSnapshot.java`:
```java
private Integer dbActiveConnectionsMedian;
```

**Are pg_stat_activity snapshots taken?**

**❌ NO**: Not implemented

**🔴 LIMITATION**: DB-side metrics require manual collection

---

### 1️⃣7️⃣ Queue depth metrics

**For OJP and PgBouncer, how is queue depth obtained?**

**❌ NOT AVAILABLE**: Queue depth is not collected

**Via metrics endpoint?**

**Not implemented**: Would require querying OJP/PgBouncer metrics endpoints

**🔴 LIMITATION**: Cannot measure queueing behavior in proxy modes

---

### 1️⃣8️⃣ GC and JVM metrics

**Are GC pauses collected via JMX?**

**❌ NO**: Not implemented

**Are they included in summary.json?**

**Placeholder exists**: `gcPauseMsTotal` field in `MetricsSnapshot.java`

**Is heap pressure reported?**

**❌ NO**: Not implemented

**🔴 LIMITATION**: JVM metrics would require JMX integration or OSHI

---

## 🧪 G) Workload Validity

### 1️⃣9️⃣ Prepared statements

**Are prepared statements actually reused?**

**⚠️ MIXED BEHAVIOR**:

In `ReadWriteWorkload.java` lines 48-89:
```java
try (PreparedStatement stmt = conn.prepareStatement(INSERT_ORDER)) {
    stmt.setLong(1, accountId);
    // ...
}
```

**Issue**: PreparedStatement is created within try-with-resources, so it's **closed after each use**

**HikariCP caching**: The DataSource properties enable prepared statement caching:
```java
config.addDataSourceProperty("cachePrepStmts", "true");
```

**✅ PARTIAL**: Driver-level caching exists, but statements are not explicitly reused at application level

**Better practice**: Reuse PreparedStatement objects or rely on connection-level caching

---

### 2️⃣0️⃣ Autocommit behavior

**Is W2 using explicit transactions?**

**✅ YES**: Line 43 in `ReadWriteWorkload.java`:
```java
conn.setAutoCommit(false);
```

**Is autocommit disabled correctly?**

**✅ YES**: Also set at pool level in HikariConfig (line 39 in HikariProvider.java):
```java
config.setAutoCommit(false);
```

**Explicit commit/rollback:** Lines 83-87:
```java
conn.commit();
// ...
catch (SQLException e) {
    conn.rollback();
    throw e;
}
```

---

### 2️⃣1️⃣ Data distribution

**Is dataset large enough to exceed memory?**

**⚠️ DEPENDS ON CONFIGURATION**:
- Default: 10K accounts, 5K items, 50K orders, ~150K order lines
- Total size: Approximately 50-100 MB with indexes
- **Likely fits in memory** with default settings

**Recommendation**: Increase dataset sizes for realistic testing:
- Accounts: 1M+
- Orders: 10M+
- To exceed typical DB cache sizes (several GB)

**Is Zipf distribution implemented correctly and seedable?**

**✅ YES**: `ZipfGenerator.java` implements proper Zipf distribution:
- Precomputes cumulative probabilities (lines 35-44)
- Uses binary search for value selection (lines 52-64)
- Seedable random number generator (line 28)
- **Verified by unit tests** (ZipfGeneratorTest.java)

---

### 2️⃣2️⃣ Slow query isolation

**Does W3 slow query truly consume CPU/IO?**

In `SlowQueryWorkload.java` lines 24-32:
```java
private static final String SLOW_QUERY =
    "SELECT o.order_id, o.account_id, o.created_at, o.status, " +
    "       SUM(ol.qty * ol.price_cents) AS computed_total " +
    "FROM orders o " +
    "JOIN order_lines ol ON o.order_id = ol.order_id " +
    "WHERE o.created_at > (CURRENT_TIMESTAMP - INTERVAL '90 days') " +
    "GROUP BY o.order_id, o.account_id, o.created_at, o.status " +
    "ORDER BY o.created_at DESC LIMIT 500";
```

**Analysis:**
- Join across orders and order_lines
- 90-day window filter
- GROUP BY aggregation
- ORDER BY with LIMIT 500

**⚠️ MAY BE CACHE-BOUND**:
- With default dataset (50K orders), 90-day window likely covers most data
- If entire dataset fits in memory, query may be cache-bound
- **Limited CPU/IO consumption**

**Recommendation**: Use larger dataset or adjust query to force sequential scans

---

## 🧾 H) Reproducibility & Documentation

### 2️⃣3️⃣ Environment snapshot completeness

**Does env-snapshot capture required info?**

In `EnvSnapshotCommand.java` lines 56-105:

**✅ Captured:**
- CPU model (via OSHI - `systemInfo.getHardware().getProcessor()`)
- RAM (via OSHI)
- JVM version, vendor, flags (`System.getProperty()`)
- Git commit hash (`git rev-parse HEAD`)

**⚠️ Partial:**
- Driver version: Via package inspection
- OS/kernel: Via OSHI

**Are DB config files copied into snapshot?**

**✅ YES**: Lines 107-120 support copying config files if paths are provided

**⚠️ ENHANCEMENT**: Should auto-detect and copy postgresql.conf if accessible

---

### 2️⃣4️⃣ Determinism

**Is RNG seed logged?**

**✅ YES**: Stored in `summary.json` via `BenchmarkRunInfo.seed` (line 178 in BenchmarkRunner.java)

**Does the same seed produce identical request sequences?**

**✅ YES**: 
- ZipfGenerator uses seeded Random (line 28 in ZipfGenerator.java)
- RandomGenerator uses seeded Random (line 10 in RandomGenerator.java)
- **Verified by unit test** `testDeterministicGeneration()` in ZipfGeneratorTest.java

---

### 2️⃣5️⃣ Results integrity

**Are summary.json files immutable once written?**

**✅ YES**: Written once at end of run, not modified

**Is config hash stored with results?**

**❌ NO**: Config hash is not computed or stored

**🔴 ENHANCEMENT**: Should compute and store config file hash for reproducibility verification

---

## 🚨 I) Failure Handling & Edge Cases

### 2️⃣6️⃣ Timeout classification

**Are timeouts distinguished from SQL errors?**

**✅ YES**: In `LoadGenerator.executeWorkload()` lines 48-58:
```java
try {
    workload.execute();
    // ...
} catch (java.sql.SQLTimeoutException e) {
    metrics.recordError("timeout");
} catch (java.sql.SQLException e) {
    metrics.recordError("sql_exception");
} catch (Exception e) {
    metrics.recordError("other");
}
```

**Is connection acquisition timeout tracked separately?**

**❌ NO**: Connection acquisition timeout would be caught as generic `SQLException`

**🔴 ENHANCEMENT**: Distinguish connection pool exhaustion from query timeout

---

### 2️⃣7️⃣ Backpressure vs failure

**Are rejected requests counted as errors?**

**⚠️ DEPENDS**: 
- If connection pool is exhausted, will throw SQLException → counted as "sql_exception"
- No explicit "rejected" category for pool exhaustion

**Or separately reported as "shed load"?**

**❌ NO**: Not separately tracked

**🔴 ENHANCEMENT**: Add explicit "connection_pool_timeout" error category

---

### 2️⃣8️⃣ Resource exhaustion

**What happens if load generator CPU saturates?**

**⚠️ UNDEFINED BEHAVIOR**:
- Scheduled tasks may queue up
- No explicit detection of generator saturation
- May appear as increased latency

**Is generator saturation detectable?**

**❌ NO**: No monitoring of generator thread pool queue depth or CPU usage

**🔴 LIMITATION**: Cannot distinguish DB saturation from generator saturation

**Recommendation**: Monitor `attemptedRps` vs configured `targetRps` to detect degradation

---

## 🎯 J) Interpretation Guardrails

### 2️⃣9️⃣ Does the tool prevent misleading comparisons?

**Does it warn if DB max_connections < theoretical total pool?**

**❌ NO**: No validation of DB connection limits

**Does it warn if open-loop falls back to closed-loop behavior?**

**❌ NO**: No detection of scheduling degradation

**🔴 LIMITATION**: User must manually verify configuration consistency

**Recommendations:**
1. Add config validation: `totalPoolSize = poolSize * replicas <= db_max_connections`
2. Monitor scheduling delay: `actual_send_time - scheduled_time`
3. Warn if `achievedRps << targetRps` without errors

---

### 3️⃣0️⃣ Does reporting distinguish different performance characteristics?

**Current reporting includes:**
- ✅ Throughput (`achievedThroughputRps`)
- ✅ Error rate (`errorRate`)
- ✅ Tail latency (p95, p99, p999)

**Missing distinctions:**
- ❌ **Load shaping via queueing**: Not measured (no queue depth)
- ❌ **Goodput vs throughput**: No distinction between successful and total ops
- ⚠️ **Degradation modes**: Error spike vs latency spike not clearly separated

**🔴 ENHANCEMENT**: Report should explicitly call out:
1. "Higher throughput with lower error rate" = better capacity
2. "Same throughput with higher latency" = queueing behavior
3. "Lower throughput with high errors" = overload/failure

---

## 📋 Summary of Key Issues

### 🔴 Critical Issues
1. **Open-loop scheduling**: Not true open-loop (relative scheduling, no missed opportunity tracking)
2. **Time-series percentiles**: Cumulative instead of per-interval
3. **No replica synchronization**: No start barrier for multi-instance runs
4. **No cross-replica aggregation**: Placeholder implementation
5. **Prepared statements**: Created/closed per operation (relies on driver cache)

### ⚠️ Significant Limitations
1. **No generator saturation detection**: Cannot distinguish DB vs generator limits
2. **No queue depth metrics**: Missing for OJP/PgBouncer comparison
3. **No JVM/GC metrics**: Missing performance diagnostics
4. **Dataset size**: Default configuration likely cache-bound
5. **Connection storm**: No staggered startup for replicas

### ✅ Strengths
1. **Explicit transactions**: Proper autocommit=false and manual commit/rollback
2. **HDR histogram usage**: Correct precision and max value configuration
3. **Warmup isolation**: Proper metric reset after warmup
4. **Deterministic RNG**: Seedable and verified by tests
5. **Error classification**: Distinguishes timeout from SQL exceptions
6. **Consistent pool settings**: Same configuration across all SUTs

### 🎯 Recommendations for Production Use

**Before running production benchmarks:**

1. **Fix open-loop scheduling**: Implement absolute time-based scheduling with missed opportunity tracking
2. **Implement replica synchronization**: Add barrier coordination for multi-instance runs
3. **Fix time-series percentiles**: Compute from per-interval histograms
4. **Increase dataset size**: Ensure data exceeds memory to test realistic scenarios
5. **Add monitoring**: Generator saturation, queue depth, JVM metrics
6. **Implement proper aggregation**: Merge HDR histograms across replicas
7. **Add validation**: Check total pool size vs DB limits, scheduling delay monitoring
8. **Stagger replica startup**: Avoid unrealistic connection storm

**For comparative analysis:**

Despite limitations, the tool is **usable for comparative analysis** if:
- Same configuration used across all SUTs
- Single-instance runs (avoid replica coordination issues)
- Results interpreted with awareness of limitations
- Focus on relative differences, not absolute numbers
- Manual verification of no generator saturation

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-18  
**Implementation Reviewed**: Commit 70e230b

---

## 🔄 UPDATE: OJP Model Correction (2026-02-18)

The OJP implementation has been corrected to reflect the accurate architectural model:

### Previous Incorrect Model (REMOVED)
- ❌ OJP used minimal client-side pooling (HikariCP with 2 connections)
- ❌ Assumption that client must restrict to 1-2 connections
- ❌ Cannot configure pool parameters on client

### Correct OJP Model (IMPLEMENTED)
1. **No Client-Side Pooling**: Application does NOT use HikariCP, DBCP, or any client pooling library
2. **Virtual JDBC Connections**: Client creates many virtual JDBC Connection handles via `DriverManager.getConnection()`
3. **Server-Side Pool**: OJP JDBC driver properties (ojp.maxConnections, etc.) configure the real backend DB pool on the OJP server
4. **Pool Configuration**: Passed as Properties when obtaining connections, applied server-side

### Implementation Details

**New Classes:**
- `OjpConfig`: Configuration for OJP-specific settings
- `OjpVirtualConnectionMode`: PER_WORKER (default) or PER_OPERATION
- `OjpPoolSharing`: SHARED (all replicas) or PER_INSTANCE (divided)
- `ConnectionWrapper`: Base class for tracking virtual connection lifecycle

**OjpProvider (Rewritten):**
- Uses `DriverManager.getConnection()` directly (no HikariCP)
- Passes OJP properties with each connection request
- Tracks virtual connection metrics (opened, current, max concurrent)
- Supports both PER_WORKER and PER_OPERATION modes

**Disciplined Pooling Equivalence:**
- SHARED: `ojp.maxConnections = dbConnectionBudget` (all replicas share one pool)
- PER_INSTANCE: `ojp.maxConnections = floor(dbConnectionBudget / replicas)` (each replica gets own pool)

**Configuration Validation:**
- Rejects client-side poolSize for OJP mode
- Fails fast if hikari.* or pool-related properties detected
- Automatically calculates server-side pool allocation

**Summary Output:**
- `clientPooling: "none"` for OJP mode
- `ojpVirtualConnectionMode`: PER_WORKER or PER_OPERATION
- `ojpPoolSharing`: SHARED or PER_INSTANCE
- `ojpPropertiesUsed`: OJP driver properties (password redacted)
- `clientVirtualConnectionsOpenedTotal`: Total virtual connections opened
- `clientVirtualConnectionsMaxConcurrent`: Peak concurrent virtual connections

### Updated Documentation
- RUNBOOK.md: Clarifies no client-side pooling for OJP
- CONFIG.md: Documents all ojp.* properties
- Examples: ojp-mode.yaml and ojp-per-instance-16-replicas.yaml

### Test Coverage
8 new unit tests in `OjpConfigTest`:
- Allocation calculation (SHARED, PER_INSTANCE, rounding, minimum)
- Configuration validation (rejects poolSize)
- Properties building and logging

All 25 tests passing (100%).
