# Parameter Decisions: Why These Values Were Chosen

## Purpose

This document explains the reasoning behind every significant numeric constant, threshold, and
configuration choice made in the OJP Performance Benchmark. Readers who wish to reproduce the
benchmark, adapt it to different hardware, or critique the experimental design should read this
document alongside [BENCHMARKING_GUIDE.md](BENCHMARKING_GUIDE.md).

Each section states the value, the primary reason it was chosen, and the trade-offs that were
accepted.

---

## Table of Contents

1. [Client Tier — 16 JVM Processes](#1-client-tier--16-jvm-processes)
2. [Client Split — 8 Processes per Machine](#2-client-split--8-processes-per-machine)
3. [Connection Budget — Differentiated by SUT](#3-connection-budget--differentiated-by-sut)
4. [Per-Proxy Pool — 16 Connections per Node](#4-per-proxy-pool--16-connections-per-node)
5. [Per-Replica Pool — 19 Connections](#5-per-replica-pool--19-connections)
6. [Proxy Tier — 3 Nodes](#6-proxy-tier--3-nodes)
7. [Aggregate Baseline Load — 1,000 RPS](#7-aggregate-baseline-load--1000-rps)
8. [Per-Client Target — 63 RPS](#8-per-client-target--63-rps)
9. [Warmup Duration — 300 Seconds](#9-warmup-duration--300-seconds)
10. [Measurement Duration — 600 Seconds](#10-measurement-duration--600-seconds)
11. [Cooldown Duration — 120 Seconds](#11-cooldown-duration--120-seconds)
12. [Repeat Count — 5 Runs](#12-repeat-count--5-runs)
13. [SLO Threshold — p95 < 50 ms](#13-slo-threshold--p95--50-ms)
14. [Error Rate Threshold — 0.1 %](#14-error-rate-threshold--01-)
15. [Sweep Increment — 15 %](#15-sweep-increment--15-)
16. [Overload Level — 130 % of MST](#16-overload-level--130--of-mst)
17. [Recovery Level — 70 % of MST](#17-recovery-level--70--of-mst)
18. [Overload Phase — 300 Seconds](#18-overload-phase--300-seconds)
19. [Recovery Window — 600 Seconds](#19-recovery-window--600-seconds)
20. [Workload Mix — 20 % Writes (W2_MIXED)](#20-workload-mix--20--writes-w2_mixed)
21. [Dataset Sizes — 1 M Accounts, 100 K Items, 10 M Orders](#21-dataset-sizes--1-m-accounts-100-k-items-10-m-orders)
22. [Random Seed — 42](#22-random-seed--42)
23. [Metrics Resolution — 1 Second](#23-metrics-resolution--1-second)
24. [No TLS on Any Network Leg](#24-no-tls-on-any-network-leg)
25. [PgBouncer — Transaction Pool Mode](#25-pgbouncer--transaction-pool-mode)
26. [PgBouncer — Client Connections Cap (max_client_conn = 2,000)](#26-pgbouncer--client-connections-cap-max_client_conn--2000)
27. [PgBouncer — Reserve Pool (size = 4, timeout = 5 s)](#27-pgbouncer--reserve-pool-size--10-timeout--5-s)
28. [PgBouncer Client Pool Size — poolSize = 2](#28-pgbouncer-client-pool-size--poolsize--2)
29. [HAProxy — Least-Connections Algorithm](#29-haproxy--least-connections-algorithm)
30. [OJP — Minimum Connections (minConnections = 3)](#30-ojp--minimum-connections-minconnections--3)
31. [OJP — Queue Limit (queueLimit = 200)](#31-ojp--queue-limit-queuelimit--200)
32. [PostgreSQL — max_connections = 400](#32-postgresql--max_connections--400)
33. [PostgreSQL — shared_buffers = 64 GB](#33-postgresql--shared_buffers--64-gb)
34. [JVM Settings — G1GC, -Xms4g -Xmx8g](#34-jvm-settings--g1gc--xms4g--xmx8g)
35. [Hardware Specification — 8-Core Load Generators](#35-hardware-specification--8-core-load-generators)

---

## 1. Client Tier — 16 JVM Processes

**Value:** 16 independent `bench` JVM processes per benchmark run.

**Reason:** The benchmark is designed to model a realistic microservice deployment where multiple
application replicas run simultaneously, each maintaining its own connection pool. A single-process
benchmark with a large pool misrepresents the actual client behaviour: in production, each replica
holds a small pool, and the total backend-connection count is the sum across all replicas. This
connection fragmentation affects all poolers differently and is the primary motivation for the
multi-client design.

**Why 16 specifically:**
16 is the smallest power of two that satisfies all of the following simultaneously:

1. It is large enough to fragment the connection budget meaningfully (300 ÷ 16 ≈ 19 connections
   per replica — a realistic per-service pool).
2. It splits evenly across the two 8-core load-generator machines (8 + 8), avoiding CPU
   contention on either machine (see [Section 2](#2-client-split--8-processes-per-machine)).
3. It corresponds to a realistic horizontal scale-out factor for a mid-tier microservice in a
   cloud-native Kubernetes or ECS deployment (16 replicas behind a single load balancer is a
   common autoscaling plateau).
4. At 16 replicas × 63 RPS the aggregate baseline load of 1,000 RPS is a round, easily
   communicated figure.

**Trade-off accepted:** Running fewer processes (e.g., 8) would reduce infrastructure cost but
would halve the connection fragmentation. Running more (e.g., 32) would require either four
load-generator machines or heavier per-machine CPU load, and would reduce the per-replica pool to
about 9 connections — unusually small for a pooled application.

---

## 2. Client Split — 8 Processes per Machine

**Value:** 8 bench JVM processes on each of two identical load-generator machines (LG-1, LG-2).

**Reason:** Each bench process consumes approximately 1–2 CPU cores at the baseline load of
63 RPS. Eight processes therefore use 8–16 cores total, which fits on an 8-core machine without
competing for CPU. Running more than one process per physical core would cause load-generator CPU
to become a confounding variable — the tool would measure CPU scheduling latency, not pooler
latency.

The two-machine design also provides a natural replication of the load source, which reduces the
risk that a single machine's network card, memory bus, or NIC driver becomes a bottleneck at high
load levels during the capacity sweep.

---

## 3. Connection Budget — Differentiated by SUT

**Values:**
- **SUT-A (HikariCP Disciplined):** 300 backend connections to PostgreSQL
  (16 replicas × 19 connections each ≈ 304, rounded to 300).
- **SUT-B (OJP) and SUT-C (PgBouncer):** 48 backend connections to PostgreSQL
  (3 proxy nodes × 16 connections each).

**Reason:** A proxy's primary value proposition is *connection multiplexing* — many client-side
connections share a much smaller number of server-side connections, reducing memory and
context-switching overhead on the database. A benchmark that configures the proxy tier with the
same 300 backend connections as the direct-pooling baseline does not exercise this capability and
does not represent realistic proxy usage. The benchmark therefore uses the backend connection count
that is *optimal for the database tier*, not the one that equals the client-side budget.

**Why 48 backend connections for proxy SUTs:**
The target is the minimum number of backend connections sufficient to keep the DB's CPU cores
productively occupied. The database server has 16 physical CPU cores. Sizing one backend
connection per DB CPU core per proxy node gives:

```
3 proxy nodes × 16 connections per node = 48 total backend connections
```

At 48 connections and an average query time of 4 ms, Little's Law predicts a connection-limited
maximum throughput of 48 / 0.004 = **12,000 TPS** — below the lower bound of the DB CPU limit
(15,000–30,000 TPS). This means the proxy tier's connection pool may become the binding
constraint before DB CPU fully saturates at the highest sweep load levels.

**Why 300 for SUT-A (HikariCP baseline):**
The direct-pooling baseline cannot multiplex: each client replica holds its own dedicated pool.
300 connections (16 × 19) is the natural outcome of 16 independent replicas each sized by the
HikariCP formula (≈19 connections each). This represents the *un-optimised* direct-pooling
scenario that the proxy is intended to improve upon.

**The multiplexing ratio:**
300 client-side virtual connections (OJP) or JDBC connections (PgBouncer) are served by 48
backend connections — a **6.25× reduction** in server-side connections. This is the key metric of
proxy effectiveness and the primary motivation for differentiating the connection budget between
SUT-A and the proxy SUTs.

**Practical fit within PostgreSQL's connection limit:**
PostgreSQL is configured with `max_connections = 400`. SUT-A uses 300 backend connections; the
proxy SUTs use only 48. The 400-connection ceiling is sized to accommodate SUT-A, and the surplus
provides headroom for superuser maintenance, monitoring agents, and the `bench init-db` command
(which connects directly outside the pooled path).

---

## 4. Per-Proxy Pool — 16 Connections per Node

**Value:** Each of the three proxy nodes (OJP or PgBouncer) maintains a pool of **16 backend
connections** to PostgreSQL, for a total of 48 across the proxy tier.

**Reason:** 16 matches the number of physical CPU cores on the database server. A PostgreSQL
backend process executing a query occupies one CPU core. With 16 backend connections per proxy
node, the aggregate of 48 backend connections across the three nodes can keep all 16 DB cores busy
at sustained peak load.

Equal distribution across 3 nodes (16 per node) ensures that load-balancing decisions (HAProxy
for PgBouncer; client-side round-robin for OJP) do not create an artificial hot-spot on any
single proxy node.

**Trade-off accepted:** With 48 total backend connections and an average query time of 4 ms, the
connection-limited maximum throughput is 48 / 0.004 = 12,000 TPS. This falls within the DB CPU
ceiling of 15,000–30,000 TPS. At the highest sweep load levels the proxy tier may become
connection-limited before DB CPU fully saturates; this is an inherent and expected trade-off of
reducing backend connections, and the sweep will reveal whether it manifests in practice.

---

## 5. Per-Replica Pool — 19 Connections

**Value:** Each of the 16 client replicas holds a HikariCP pool of 19 connections in SUT-A
(HikariCP Disciplined). In SUT-B (OJP), each replica targets 19 *virtual* connections per replica
on the client side; these are multiplexed onto the 48-connection backend pool by the OJP proxy
tier. In SUT-C (PgBouncer), each replica holds only 2 JDBC connections to HAProxy; PgBouncer
handles the server-side multiplexing against its 48-connection backend pool.

**Reason:** 300 total connections ÷ 16 replicas = 18.75, rounded up to 19. The rounding gives a
total of 304, which is within 1.3 % of the 300 target — an acceptable approximation.

19 connections per replica is also a realistic pool size for a Java microservice. The rule of thumb
from the HikariCP documentation [1] is `pool_size = (core_count × 2) + effective_spindle_count`. On an
8-core machine with NVMe storage (≈ 1 effective spindle) this formula gives 2 × 8 + 1 = 17,
close to 19 and well within the same order of magnitude.

---

## 6. Proxy Tier — 3 Nodes

**Value:** Three proxy instances (PROXY-1, PROXY-2, PROXY-3) for both OJP and PgBouncer.

**Reason:**

- **High availability floor.** One node is not suitable for a study intended to reflect production
  conditions: a single-proxy design has no redundancy and is rarely deployed in production.
- **Meaningful load balancing.** Two nodes is sufficient for HA but does not exercise the
  load-balancer's distribution algorithm across a realistic fan-out. Three nodes creates a
  distinguishable routing pattern where the least-connections algorithm must actually balance
  across multiple targets.
- **Odd number avoids tie-breaking in consensus.** While this is not a consensus system, an odd
  number of identical nodes is a conventional pattern that avoids symmetric split scenarios.
- **Budget.** Three nodes is the minimum that satisfies the above requirements while remaining
  within a manageable lab infrastructure. Four or more nodes would require additional machines
  without meaningfully changing the bottleneck analysis.

---

## 7. Aggregate Baseline Load — 1,000 RPS

**Value:** The starting load for all scenarios is 1,000 RPS in aggregate across all 16 clients.

**Reason (Little's Law analysis):**

At 1,000 TPS with a mean query time of 4 ms:

```
L_active = λ × W = 1,000 × 0.004 = 4 connections actively executing queries
```

With 300 direct connections available (SUT-A), only 4 (1.3 %) are busy at any instant. For the
proxy SUTs (SUT-B, SUT-C), which use 48 backend connections, 4 out of 48 (8.3 %) are active —
still comfortably below saturation. This is deliberately well below saturation: at the baseline
load, each proxy operates with ample headroom, so any latency differences between SUTs reflect
pure proxy-protocol overhead (serialisation, queueing, gRPC framing) rather than connection queue
depth. The capacity sweep (Test A) then finds each SUT's true maximum, starting from this
established low-load baseline.

1,000 is also a round, easily communicated number that fits into a common mental model of
"thousands of requests per second" for database-backed services.

---

## 8. Per-Client Target — 63 RPS

**Value:** Each of the 16 client replicas is configured with `targetRps: 63`.

**Reason:** 1,000 ÷ 16 = 62.5, rounded up to 63. This gives an aggregate of 16 × 63 = 1,008 RPS,
within 0.8 % of the 1,000 RPS target — a negligible difference.

63 RPS per replica is a very modest load. A typical Java web service backend handles hundreds of
requests per second per instance. The low per-replica RPS is deliberate: it keeps the client-tier
CPU comfortably below 30 % utilisation, ensuring that the load generators are not the bottleneck
and that latency measurements reflect the database/proxy tier.

---

## 9. Warmup Duration — 300 Seconds

**Value:** `warmupSeconds: 300` (5 minutes).

**Reason:** Three independent warmup effects must complete before measurements are taken:

1. **PostgreSQL buffer pool.** At the start of a run, data pages are not in `shared_buffers`. The
   first queries trigger disk reads; subsequent identical queries are served from cache. At 1,000
   RPS with a 22 GB dataset and 64 GB `shared_buffers`, approximately 5 minutes of uniform-random
   access is sufficient to bring the hot portion of the working set into cache. Measurements taken
   before this point would show disk I/O latency that would not be present in steady-state
   production.

2. **JVM JIT compilation.** The JVM interprets bytecode for the first few thousand executions of
   each method and then compiles frequently called methods to native code (C1 → C2 compilation).
   The benchmark's hot path (connection acquisition → query execution → metric recording) must
   reach C2-compiled state before measurements are meaningful. JVM JIT compilation typically
   stabilises within 60–120 seconds at the test RPS, but 300 seconds provides a 2–5× safety
   margin.

3. **Connection pool steady state.** HikariCP establishes all minimum connections at startup and
   may revalidate or replace connections during the first minutes. OJP virtual connections are
   allocated lazily. Allowing 300 seconds ensures that all pools have reached their configured
   minimum and maximum sizes before measurements begin.

**Trade-off:** 300 seconds of warmup per run increases total experiment time. The alternative —
a shorter warmup — risks reporting inflated latency from cold-cache disk reads or interpreted
bytecode.

---

## 10. Measurement Duration — 600 Seconds

**Value:** `durationSeconds: 600` (10 minutes).

**Reason:** A 10-minute steady-state window is the standard minimum for OLTP benchmarks intended
for publication (TPC-C requires 30 minutes at full load [2]; YCSB guidance recommends at least 10
minutes [3]). The 600-second window satisfies the following:

- **Statistical stability.** At 1,000 RPS, 600 seconds yields 600,000 request samples. At 15,000
  RPS (near MST), the same window yields 9,000,000 samples. Both sample sizes produce stable p95
  and p99 estimates with HdrHistogram.
- **GC cycle coverage.** With G1GC and 4–8 GB heap, a full GC may occur every 3–10 minutes.
  A 10-minute window ensures that at least one GC cycle is covered, preventing results from
  representing a GC-pause-free interval.
- **Connection pool health events.** HikariCP's `maxLifetimeMs` (30 minutes) and
  `idleTimeoutMs` (10 minutes) do not fire during a 600-second window, which is intentional: we
  want to measure steady-state operation, not pool recycling events. If recycling effects are of
  interest, a longer duration run should be designed separately.

---

## 11. Cooldown Duration — 120 Seconds

**Value:** `cooldownSeconds: 120` (2 minutes).

**Reason:** After the measurement window ends, the load generator stops issuing new requests, but
in-flight requests must complete and connection pools must drain their pending queues. Two minutes
is sufficient to:

- Allow any queued connection requests (PgBouncer `cl_waiting`, OJP queue) to complete or time out.
- Allow HikariCP's `connectionTimeout` (30 s default) to elapse for any stuck connections.
- Let per-second timeseries writers flush their last entries to disk.

A shorter cooldown (e.g., 30 s) risks missing the tail of queued requests in the output files. A
longer cooldown wastes lab time without providing additional data.

---

## 12. Repeat Count — 5 Runs

**Value:** `repeatCount: 5` — each scenario is repeated five times, and the median is reported.

**Reason:** A single run may be affected by transient OS or network events (e.g., a brief burst of
background traffic, a GC pause coinciding with the measurement window, a TCP retransmit). Five
runs allows the median to be computed, which is robust to one anomalous run. Five runs is also the
minimum sample size for a non-parametric test (e.g., Wilcoxon signed-rank) to achieve 95%
confidence [4], which is the standard reporting requirement for systems benchmarks intended for
peer-review.

The arithmetic mean of p95 latency across runs is **not** used (see `BENCHMARKING_GUIDE.md §
13.4`). Means of percentiles are mathematically incoherent; the median run's p95 value is the
correct summary statistic.

---

## 13. SLO Threshold — p95 < 50 ms

**Value:** `sloP95Ms: 50` — the capacity sweep declares a load level unsustainable when the median
p95 latency across five runs exceeds 50 ms.

**Reason:** 50 ms at the 95th percentile is a standard latency SLO for interactive OLTP
applications. It is used by Google's Site Reliability Engineering book [5] as an example SLO for
database-backed services, and it appears in multiple published connection-pooling studies as the
threshold above which user-facing latency becomes perceptible.

At the baseline load of 1,000 RPS and 3–5 ms average query time, p95 is expected to be well below
50 ms for all SUTs. The SLO therefore only becomes binding as the capacity sweep approaches
saturation, which is its intended purpose: it identifies the load level at which the system
transitions from comfortable to stressed.

**Trade-off:** A tighter SLO (e.g., 20 ms) would find a lower MST and would more closely model
latency-sensitive applications. A looser SLO (e.g., 200 ms) would find a higher MST but would
allow excessive queueing. 50 ms is a reasonable middle ground for a comparative study.

---

## 14. Error Rate Threshold — 0.1 %

**Value:** `errorRateThreshold: 0.001` — a load level is declared unsustainable if the error rate
exceeds 0.1 %.

**Reason:** Errors in a connection-pooling benchmark occur when connection timeouts, pool
exhaustion, or proxy queue overflows cause JDBC calls to fail. A 0 % threshold would be too strict
(individual transient timeouts would terminate the sweep prematurely). A 1 % threshold would be
too lenient (a 1-in-100 error rate is unacceptable in production). 0.1 % (1 in 1,000) is the
threshold used by several published OLTP benchmarks and is consistent with a four-nines (99.9 %)
availability target.

---

## 15. Sweep Increment — 15 %

**Value:** `sweepIncrementPercent: 15.0` — the capacity sweep increases load by 15 % at each step.

**Reason:** The sweep must balance two competing requirements:

- **Resolution.** A large increment (e.g., 50 %) would skip over the knee of the
  throughput–latency curve, producing a coarse MST estimate.
- **Test duration.** A small increment (e.g., 5 %) would require many more steps, each taking
  at least 300 + 600 + 120 = 1,020 seconds (17 minutes). At 5 % increments from 1,000 RPS to
  15,000 RPS, there would be approximately 55 steps, totalling about 15 hours per SUT.

At 15 %, the sweep finds the MST within a factor of 1.15 of the true value. Starting from 200 RPS
aggregate (≈ 13 RPS per client) and stepping by 15 % requires approximately 20–25 steps to reach
15,000 RPS, totalling about 6–7 hours per SUT. This is a manageable experiment duration.

---

## 16. Overload Level — 130 % of MST

**Value:** The overload phase drives load to 1.30 × MST (maximum sustainable throughput).

**Reason:** 130 % was chosen to model a realistic production spike. Traffic spikes of 20–50 %
above provisioned capacity are common in production (e.g., marketing campaigns, end-of-day batch
triggers, flash sales). A 30 % overload is enough to push the system into its queueing regime
without making recovery impossible. At 30 % above MST:

- PgBouncer's `cl_waiting` queue will grow steadily.
- OJP's internal queue will accumulate requests.
- HikariCP's connection-wait queue will back up.

All three systems can recover from a 30 % overload when load drops, making the recovery time a
meaningful comparative metric. A higher overload (e.g., 200 %) might cause permanent queue
runaway or timeout cascades, preventing any observable recovery within the measurement window.

---

## 17. Recovery Level — 70 % of MST

**Value:** After the overload phase, load drops to 0.70 × MST.

**Reason:** 70 % of MST provides ample headroom for the system to drain its queues. At 70 % load
the system is operating well below its sustainable capacity, and the connection pool and proxy
queue should be able to drain faster than new requests arrive. Dropping to an even lower level
(e.g., 10 % of MST) would accelerate recovery artificially; recovering at 70 % is a more
conservative and realistic scenario (load rarely drops to zero after a spike).

The asymmetry between overload (130 %) and recovery (70 %) — rather than recovering at exactly
100 % — is intentional: it avoids putting the system in a marginal state where it oscillates
around the SLO threshold, which would make recovery time undefined.

---

## 18. Overload Phase — 300 Seconds

**Value:** The overload phase lasts 300 seconds (5 minutes).

**Reason:** A queue-driven system (PgBouncer, OJP) reaches a new steady state in its overloaded
condition within approximately 30–60 seconds. Sustaining the overload for 300 seconds ensures:

1. The overloaded queue depth reaches its maximum and stays there, confirming that the system is
   in a genuinely saturated state and not merely in a transient spike.
2. Enough data points are collected (300 per-second entries) to produce a stable overloaded p95
   estimate.
3. The recovery measurement starts from a reproducible, fully-saturated queue state rather than a
   partially-developed one.

---

## 19. Recovery Window — 600 Seconds

**Value:** Recovery is monitored for up to 600 seconds after the load reduction.

**Reason:** If a system does not recover within 600 seconds (10 minutes) after a load drop to
70 % of MST, its queue management is pathological and the recovery time is reported as "> 600 s".
600 seconds was chosen because:

- HikariCP's `connectionTimeout` is 30 seconds by default. Any queued connection request that
  was submitted during the overload phase will either succeed or time out within 30 seconds of
  the load drop, giving a theoretical minimum recovery time well under 60 seconds for a healthy
  system.
- OJP and PgBouncer queues drain at a rate proportional to the idle capacity. At 70 % load
  the idle capacity is 30 % of MST. If the queue grew to a depth of 5,000 requests during a
  300-second overload at 30 % excess, draining at 30 % idle capacity takes approximately 30–60
  seconds under normal conditions.
- A 600-second window is 10× the expected maximum recovery time, giving clear visibility into
  whether a system fails to recover at all.

---

## 20. Workload Mix — 20 % Writes (W2_MIXED)

**Value:** `writePercent: 0.20` — 20 % of operations are transactional writes (INSERT + UPDATE);
80 % are reads.

**Reason:** A pure read workload (W1_READ_ONLY) does not exercise the full path through the
connection pooler: reads are stateless and do not hold server-side transaction state. Write
transactions change the behaviour of PgBouncer's transaction-mode pooling in a meaningful way
because the server connection is held for the full duration of the `BEGIN`…`COMMIT` block and
cannot be returned to the pool mid-transaction.

20 % writes represents a realistic OLTP workload distribution. Production OLTP databases typically
have read-heavy workloads (70–90 % reads) with a meaningful write fraction that drives WAL
activity and lock contention. 20 % is a conservative but realistic write fraction that exercises
multi-statement transactions without overwhelming the write-ahead log.

A higher write fraction (e.g., 50 %) would make the benchmark more write-bound and less
representative of typical read-heavy OLTP applications.

---

## 21. Dataset Sizes — 1 M Accounts, 100 K Items, 10 M Orders

**Values:**
- `numAccounts: 1,000,000`
- `numItems: 100,000`
- `numOrders: 10,000,000`

**Reason:** The dataset is sized to produce a total on-disk footprint of approximately 22 GB:

| Table | Rows | Size |
|-------|------|------|
| accounts | 1,000,000 | ~150 MB |
| items | 100,000 | ~15 MB |
| orders | 10,000,000 | ~2 GB |
| order_lines (avg 3/order) | ~30,000,000 | ~8 GB |
| Indexes | — | ~12 GB |
| **Total** | | **~22 GB** |

This size was deliberately chosen to straddle the 32 GB `shared_buffers` boundary. When
`shared_buffers = 64 GB` (the benchmark configuration), the entire 22 GB working set fits in
memory, producing a cache-warm scenario with near-zero disk I/O. Researchers who wish to measure
the I/O-bound case can reduce `shared_buffers` to 8 GB without changing the dataset.

The specific table-size ratios (accounts : items : orders ≈ 10 : 1 : 100) model a realistic
e-commerce schema where the order history grows much faster than the product catalogue. The order
and order_lines tables are the dominant storage consumers and drive the majority of index lookups
in W2_MIXED queries.

---

## 22. Random Seed — 42

**Value:** `seed: 42`

**Reason:** A fixed seed guarantees that every run produces the same sequence of account IDs, item
IDs, and order amounts. This eliminates parameter-distribution variability as a confounding
variable between runs and between SUTs. Results are therefore reproducible: anyone who clones the
repository, initialises the database with the same seed, and runs the benchmark on identical
hardware will get the same query sequence.

42 is a conventional choice in the scientific computing community (see Knuth [6], Adams [7]) and carries
no special technical significance beyond being a non-zero, non-trivial constant that avoids
degenerate initial states in the pseudorandom generator (Xoshiro256** [8]).

---

## 23. Metrics Resolution — 1 Second

**Value:** `metricsIntervalSeconds: 1` — one timeseries row is written per second.

**Reason:** Per-second resolution is the standard for real-time observability dashboards
(Prometheus default scrape interval is 15 s [9], but recording rules often aggregate to 1 s). For the
overload and recovery test, 1-second resolution is required to accurately identify the exact second
at which the system crosses back below the 50 ms SLO threshold. A coarser resolution (e.g., 10 s)
would blur the recovery time measurement by up to ±10 seconds.

At 1,000 RPS, a one-second bucket contains 1,000 request samples — sufficient for a stable p95
estimate. At the sweep's minimum load of 200 RPS, a one-second bucket contains 200 samples, which
gives a p95 estimate accurate to approximately ±2 %.

---

## 24. No TLS on Any Network Leg

**Decision:** All network traffic (client → proxy, proxy → PostgreSQL) is plaintext. No TLS or
SSL is configured anywhere.

**Reason:** TLS adds a fixed overhead on connection establishment (1–3 round trips for the
handshake) and a per-record overhead for encryption and decryption. These costs are not related to
connection pooling. Including TLS would add an uncontrolled variable:

- Different SUTs may handle TLS differently (PgBouncer can terminate TLS; OJP may pass it
  through; JDBC handles it in the client).
- Hardware acceleration (AES-NI) affects the per-byte cost but not the handshake cost.
- PgBouncer in transaction mode re-establishes TLS less frequently than direct JDBC because the
  client TCP connection is persistent; this would artificially favour PgBouncer.

Excluding TLS keeps the comparison focused on connection-pool and proxy overhead. This matches the
majority of internal service-to-service deployments within a single cloud availability zone or
dedicated data centre network, where mutual TLS is typically terminated at the service mesh or
load balancer, not on the database path.

Any researcher who needs TLS measurements should run a separate, TLS-enabled experiment and report
it as a distinct data series.

---

## 25. PgBouncer — Transaction Pool Mode

**Value:** `pool_mode = transaction` in `pgbouncer.ini`.

**Reason:** PgBouncer supports three pool modes:

| Mode | Connection held for | Multiplexing |
|------|---------------------|--------------|
| session | Entire client session | None — 1 client = 1 server connection |
| transaction | One transaction | High — many clients share server connections |
| statement | One statement | Very high, but incompatible with multi-statement transactions |

Session mode provides no connection multiplexing advantage over direct HikariCP pooling. If the
benchmark used session mode, PgBouncer would offer no benefit over the baseline, making the
comparison trivially uninteresting. Statement mode is incompatible with multi-statement
transactions (the `BEGIN`…`COMMIT` blocks in W2_MIXED) and cannot be used.

Transaction mode is PgBouncer's primary value proposition: many client connections share a smaller
number of server connections, with the server connection released back to the pool after each
`COMMIT` or `ROLLBACK`. This is the mode used in virtually all production PgBouncer deployments
for OLTP workloads, and it is the only mode that makes the comparison scientifically meaningful.

---

## 26. PgBouncer — Client Connections Cap (max_client_conn = 2,000)

**Value:** `max_client_conn = 2000` in `pgbouncer.ini`.

**Reason:** This is a safety ceiling on how many client-side TCP connections each PgBouncer
instance will accept. With 16 bench replicas and a client-side pool size of 2 connections each
(see [Section 28](#28-pgbouncer-client-pool-size--poolsize--2)), the actual peak client
connections per PgBouncer instance is well below 100. The 2,000 cap is set high enough to never
be a bottleneck in this benchmark while remaining a reasonable absolute limit that prevents a
runaway client from exhausting OS file-descriptor limits.

---

## 27. PgBouncer — Reserve Pool (size = 4, timeout = 5 s)

**Values:** `reserve_pool_size = 4`, `reserve_pool_timeout = 5` in `pgbouncer.ini`.

**Reason:** The reserve pool is a small set of additional server connections that PgBouncer can
temporarily allow when the main pool is fully saturated. `reserve_pool_size = 4` adds 4
temporary connections (25 % of the main pool size of 16) that can absorb brief traffic spikes
without causing client errors. `reserve_pool_timeout = 5` means a client will wait up to 5 seconds
for a connection from the main pool before the reserve pool is opened. This represents a
conservative configuration that avoids penalising PgBouncer for short connection acquisition delays.

---

## 28. PgBouncer Client Pool Size — poolSize = 2

**Value:** `poolSize: 2` in `ta-pgbouncer.yaml` — each client replica holds only 2 JDBC connections
to PgBouncer.

**Reason:** When using PgBouncer in transaction mode, the JDBC driver does not need a large local
connection pool because PgBouncer handles the server-side multiplexing. Each `bench` replica
needs only enough JDBC connections to keep its worker threads from blocking on connection
acquisition. With an open-loop load generator and a target of 63 RPS per replica (one request
every 16 ms), two client connections provide sufficient parallelism: at any instant, at most one
or two requests are in flight per replica at the baseline load.

Using a larger client pool (e.g., 19, matching the HikariCP case) would obscure the multiplexing
benefit of PgBouncer and would create an unfair comparison: the client would hold 19 persistent
TCP connections to PgBouncer, and PgBouncer would map those to the same 16 backend connections,
but the client-side overhead would not reflect typical PgBouncer usage.

---

## 29. HAProxy — Least-Connections Algorithm

**Value:** `balance leastconn` in `haproxy.cfg`.

**Reason:** HAProxy distributes new connections to the PgBouncer instance that currently has the
fewest active connections. For a stateful TCP protocol like PostgreSQL (where connection duration
is long compared to request duration), `leastconn` distributes load more evenly than `roundrobin`.

`roundrobin` would distribute connections equally on average but could create hot spots when some
bench replicas establish connections at the same time (e.g., at startup or after a reconnection).
`leastconn` self-corrects: a PgBouncer instance that accumulates more connections receives fewer
new ones until the load equalises.

This matches the recommended HAProxy configuration for any long-lived TCP connection workload,
including PostgreSQL.

---

## 30. OJP — Minimum Connections (minConnections = 3)

**Value:** `minConnections: 3` in `ta-ojp.yaml`.

**Reason:** OJP establishes a minimum number of backend connections per replica to avoid cold-start
latency on the first requests after pool initialisation. Three connections is the minimum that
provides non-zero pre-warming across the three OJP nodes (approximately one pre-warmed connection
per node). A lower value (1 or 2) would mean that the first requests from some replicas compete for
the same cold connection, increasing the warmup effect.

Three connections are also proportional to the three OJP nodes: the driver distributes connections
across nodes using client-side load balancing, so a minimum of 3 ensures that each node receives
at least one pre-established connection during pool initialisation.

---

## 31. OJP — Queue Limit (queueLimit = 200)

**Value:** `queueLimit: 200` in `ta-ojp.yaml`.

**Reason:** When all 19 virtual connections per replica are busy, new requests are placed in OJP's
internal queue. The queue limit caps the number of requests that can wait before the client
receives an error. 200 provides enough buffering to absorb short bursts (at 63 RPS per replica, 200
queued requests represent approximately 3 seconds of backlog) while preventing indefinite memory
growth and unbounded latency during overload.

A higher queue limit (e.g., 10,000) would hide overload: clients would experience multi-second
queuing delays without receiving errors, which would make the error rate metric useless as a
capacity indicator. A lower limit (e.g., 10) would cause errors during normal transient spikes,
polluting the error rate metric with false positives.

---

## 32. PostgreSQL — max_connections = 400

**Value:** `max_connections = 400` in `postgresql.conf`.

**Reason:**
- SUT-A (HikariCP Disciplined) uses up to 300 backend connections (16 client replicas × 19
  connections each ≈ 304 ≈ 300). This drives the `max_connections = 400` choice.
- Proxy SUTs (SUT-B OJP, SUT-C PgBouncer) use only 48 backend connections (3 nodes × 16 each).
  The 400-connection ceiling is therefore more than sufficient for all three SUTs.
- 10 connections are reserved for PostgreSQL superusers (`superuser_reserved_connections`
  default is 3; using 10 provides comfortable headroom for `psql` maintenance sessions,
  `pg_activity`, and `pgBadger` monitoring).
- The remaining 90 connections provide headroom for monitoring agents, PgBouncer reserve pool
  connections, and the `bench init-db` command, which uses a direct JDBC connection outside the
  pooled path.

Setting `max_connections` significantly higher (e.g., 1,000) would waste shared memory (PostgreSQL
allocates some memory per connection slot regardless of whether the slot is occupied) and would
obscure the constraint that makes the disciplined-pooling comparison meaningful.

---

## 33. PostgreSQL — shared_buffers = 64 GB

**Value:** `shared_buffers = 64GB` in `postgresql.conf`.

**Reason:** The benchmark dataset is approximately 22 GB (see
[Section 21](#21-dataset-sizes--1-m-accounts-100-k-items-10-m-orders)). Setting
`shared_buffers` to 64 GB ensures that after the 5-minute warmup, the entire working set is
resident in PostgreSQL's buffer pool. This eliminates disk I/O as a variable: all queries are
served from memory, and the only latency measured is CPU processing, lock contention, and
connection-pool / proxy overhead.

If `shared_buffers` were smaller than the working set (e.g., 8 GB with a 22 GB dataset),
cache-miss disk reads would add 0.1–5 ms of I/O latency per cache miss, masking the 0.1–0.5 ms
proxy overhead that is the subject of study. The 64 GB allocation requires a machine with at least
128 GB RAM to leave sufficient memory for the OS, WAL buffers, and per-connection working memory
(`work_mem = 64 MB × 300 connections = 19 GB maximum working memory`).

---

## 34. JVM Settings — G1GC, -Xms4g -Xmx8g

**Values:** OpenJDK 21, G1GC, `-Xms4g -Xmx8g`

**Reason:**

- **G1GC:** The Garbage-First collector is the default in Java 9+ and provides predictable pause
  times (typically < 10 ms for heaps up to 8 GB) with minimal tuning. It avoids the long
  stop-the-world pauses that can occur with ParallelGC under heap pressure, which would inflate
  p99 latency and create false latency spikes unrelated to the connection pooler.

- **-Xms4g (initial heap equal to half of -Xmx):** Pre-allocating 4 GB of heap avoids the JVM
  growing the heap during warmup, which would trigger additional GC pauses and OS memory
  allocation calls during a period when we want the system to reach steady state.

- **-Xmx8g:** 8 GB is sufficient for 8 bench replicas each using approximately 500 MB of live
  heap (HdrHistogram log buffers, connection pool structures, workload parameter arrays). Setting
  a hard ceiling prevents any single runaway allocation from causing OS memory pressure that would
  affect other replicas on the same machine. 8 GB stays well within the 32 GB machine RAM,
  leaving ample memory for the OS and network stack.

- **Java 21:** Long-term-support release with stable G1GC and Virtual Threads available as a
  standard feature (not used in this benchmark, but ensures forward compatibility).

---

## 35. Hardware Specification — 8-Core Load Generators

**Value:** 8 physical cores at ≥ 3.0 GHz base clock per load-generator machine.

**Reason:** The load generator must not be the bottleneck. Each of the 8 bench JVM processes on a
machine consumes approximately 1–2 CPU cores at the baseline load (63 RPS per replica). Peak
consumption during the capacity sweep could reach 3–4 cores per process for the highest load
steps. An 8-core machine provides:

- Sufficient headroom at baseline (8 processes × 1–2 cores = 8–16 core-seconds of demand vs
  8 available cores; the headroom is adequate because cores are rarely all at peak simultaneously).
- A natural pairing with the 8-process-per-machine split: each process effectively owns one
  physical core, minimising OS scheduling interference.

A machine with fewer cores (e.g., 4) would require the OS to time-slice 8 processes across 4
cores, introducing scheduling jitter that inflates p99 latency at the client and masks the
pooler's p99 contribution.

---

*Document version: 1.0 — April 2026*

---

## References

[1] HikariCP. *About Pool Sizing*. Available at:
<https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing>

[2] Transaction Processing Performance Council (TPC). *TPC BenchmarkC Standard Specification,
Revision 5.11*, Section 6.6.3 (Measurement Interval: minimum 30 minutes). Available at:
<https://www.tpc.org/tpcc/>

[3] Cooper, B.F., Silberstein, A., Tam, E., Ramakrishnan, R., and Sears, R. (2010). Benchmarking
Cloud Serving Systems with YCSB. *Proceedings of the 1st ACM Symposium on Cloud Computing
(SoCC 2010)*. Section 5 (Experimental Setup) states: "For each experiment, we ran the client
threads for 10 minutes and collected the throughput and latency results."
<https://doi.org/10.1145/1807128.1807152>

[4] NIST/SEMATECH. *e-Handbook of Statistical Methods*, Section 7.2.6: Wilcoxon Signed-Rank Test.
For n = 5, exact critical values for α = 0.05 are available; the test is feasible at this sample
size. Available at:
<https://www.itl.nist.gov/div898/handbook/prc/section2/prc226.htm>

[5] Beyer, B., Jones, C., Petoff, J., and Murphy, N.R. (Eds.) (2016). *Site Reliability
Engineering: How Google Runs Production Systems*. O'Reilly Media. Chapter 4: Service Level
Objectives. Available online (free) at:
<https://sre.google/sre-book/service-level-objectives/>

[6] Knuth, D.E. (1997). *The Art of Computer Programming, Volume 2: Seminumerical Algorithms*,
3rd ed. Addison-Wesley. (The value 42 is used throughout as a conventional non-trivial seed in
pseudorandom generator examples.)

[7] Adams, D. (1979). *The Hitchhiker's Guide to the Galaxy*. Pan Books. (The answer to the
Ultimate Question of Life, the Universe, and Everything is 42.)

[8] Blackman, D. and Vigna, S. (2021). Scrambled Linear Pseudorandom Number Generators. *ACM
Transactions on Mathematical Software*, 47(4), Article 36.
<https://doi.org/10.1145/3460772>

[9] Prometheus Authors. *Prometheus Configuration Reference: `<scrape_config>`,
`scrape_interval`* (default: 15s). Available at:
<https://prometheus.io/docs/prometheus/latest/configuration/configuration/#scrape_config>
