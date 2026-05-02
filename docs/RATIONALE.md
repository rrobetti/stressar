# Rationale for Stressar

## Abstract

This document states the technical reasons that motivated the construction of a purpose-built
benchmark harness for comparing PostgreSQL connection-pooling strategies, with particular emphasis
on PgBouncer accessed via a standard JDBC client. It explains why existing general-purpose tools
are insufficient for this comparison, and why the specific design choices made in this harness are
necessary to produce results that are valid for publication in a peer-reviewed venue.

---

## 1. Absence of Published JDBC-Client Benchmarks for PgBouncer

PgBouncer is the most widely deployed PostgreSQL connection pooler [4]. Its performance
characteristics have been discussed in PostgreSQL community blogs and conference talks, but as of
early 2026 no published study satisfies the following three conditions simultaneously:

1. The client driver is a standard JDBC driver (PostgreSQL JDBC 42.x) running on the JVM.
2. The load is generated in **open-loop** mode (arrival rate is controlled independently of service
   time), which is the only model that correctly exposes the latency–throughput trade-off curve.
3. The benchmark is **reproducible**: it uses a fixed random seed, a fully specified schema, and
   captures a machine-readable environment snapshot so that independent researchers can verify or
   extend the results.

The majority of publicly available PgBouncer measurements were produced with `pgbench`, which is a
C-language client that does not go through JDBC, uses closed-loop load generation by default, and
does not capture histogram-level latency data. The practical consequence is that Java application
developers—who represent the dominant user base of PostgreSQL in enterprise and cloud-native
settings—have no empirical basis for selecting or configuring PgBouncer in their stack. This tool
closes that gap.

### 1.1 The JDBC-Specific Cost of Connection Establishment

JDBC connection establishment involves TCP handshake, PostgreSQL authentication exchange (md5 or
SCRAM-SHA-256), and optional SSL negotiation. With HikariCP the cost is paid at pool
initialisation time and amortised across subsequent requests. With PgBouncer in transaction-mode
the situation is different: the client holds a persistent TCP connection to PgBouncer, and
PgBouncer multiplexes that onto a smaller set of server connections. The JDBC driver, however,
issues a `SET` command and potentially re-applies session-level settings each time it acquires a
backend connection from the pool. The overhead of this session-initialisation phase in
transaction-mode pooling is JDBC-driver-specific and is absent from `pgbench` measurements. Only a
JDBC-based benchmark can observe and quantify this overhead.

### 1.2 Prepared-Statement Semantics Differ Between Clients

PostgreSQL's extended query protocol supports server-side prepared statements. When a JDBC
application uses `PreparedStatement`, the driver may send a `Parse`/`Bind`/`Execute` sequence that
references a named statement on the backend. PgBouncer in transaction mode cannot propagate named
prepared statements across connections because the server connection returned to the pool after one
transaction may not be the same server connection used by the next `Bind`. PgBouncer handles this
by either refusing named prepared statements (requiring the JDBC driver to fall back to unnamed
statements) or by using its own prepared-statement tracking. This behaviour is entirely invisible
to `pgbench` and must be measured with a JDBC client.

---

## 2. Inadequacy of Closed-Loop Load Generation for Capacity Comparison

`pgbench`, JMeter, and Gatling all support a closed-loop model in which a fixed number of
concurrent threads or virtual users issue requests sequentially. In this model the achieved
throughput is bounded by `concurrency / mean_latency`. When the system slows down, the arrival
rate drops proportionally, which prevents the tool from ever observing the queueing behaviour that
occurs at realistic production loads.

A proper throughput–latency curve requires open-loop scheduling: requests are issued at a
configured arrival rate regardless of how long previous requests take. This is the model used by
production traffic and is the only model under which Little's Law [1] can be correctly applied to
derive connection-queue depth from latency observations. The harness implements open-loop
scheduling via `ScheduledExecutorService.scheduleAtFixedRate`, which drives the load generator
independently of workload completion.

---

## 3. Lack of a Common Baseline for Disciplined JDBC Pooling

When an application is horizontally scaled to K replicas, each replica maintains its own HikariCP
pool. If each pool holds P connections, the total number of backend connections is K×P. Without
explicit discipline, engineers tend to configure P to the same value regardless of K, which causes
the total backend-connection count to grow linearly with scale and eventually saturate the
PostgreSQL `max_connections` limit. The correct approach—dividing a fixed connection budget equally
among replicas—is called **disciplined pooling**, but no existing published benchmark uses this
model to measure the marginal cost of over-provisioning connections.

This tool provides a `HIKARI_DISCIPLINED` mode that enforces a configurable connection budget and
distributes it equally among replicas, making it possible to directly compare:

- `HIKARI_DIRECT` (naive scaling, budget uncontrolled)
- `HIKARI_DISCIPLINED` (budget divided among replicas)
- `PGBOUNCER` (external proxy handling budget enforcement)
- `OJP` (server-side pooler with virtual JDBC connections)

---

## 4. Absence of an Overload–Recovery Methodology for Connection Poolers

Existing benchmarks measure steady-state throughput and latency. They do not measure how quickly a
system **recovers** after a sustained overload episode. In production, traffic spikes regularly
exceed provisioned capacity. The time required for tail latency to return to SLO-compliant levels
after a spike is a critical operational metric, yet it has not been systematically measured for
PgBouncer or OJP. This harness provides an `overload` command that drives the system to 130% of
its measured capacity for a configurable duration, then reduces load to 70% of capacity, and
records per-second latency and error-rate timeseries. The recovery time—defined as the first
second after load reduction at which p95 latency falls and remains below the SLO threshold—is
directly computable from the output CSV.

---

## 5. Reproducibility Requirements for Scientific Publication

Results intended for a peer-reviewed paper must satisfy three reproducibility criteria:

1. **Controlled environment**: The benchmark tool captures hardware, OS, JVM, driver version, and
   database configuration into a machine-readable `env-snapshot.json` file so that readers can
   assess whether results are environment-specific.

2. **Deterministic data generation**: The schema and data generator use a seedable pseudorandom
   number generator (Xoshiro256** [2]). The same seed produces the same sequence of account IDs, item
   IDs, and order amounts on any platform. Results are therefore fully reproducible from the same
   seed and dataset size.

3. **Accurate latency measurement**: The tool uses HdrHistogram [3], which avoids the coordinated
   omission problem endemic to closed-loop measurements and provides accuracy to 0.1% across six
   orders of magnitude. Per-run histogram logs are written in the HdrHistogram binary format so
   that post-hoc reanalysis is possible without re-running the experiment.

---

## 6. Connection Pooler Comparison Is Not Covered by Existing OLTP Benchmarks

TPC-C, YCSB, and Sysbench are transactional benchmarks that measure the throughput of the
**database engine**, not the **connection-management layer**. They treat connection establishment as
a setup detail and do not vary pool size, pool mode, or the number of application replicas as
experimental variables. As a result, they cannot produce data that answers the question: *what is
the throughput penalty, in requests per second, of using PgBouncer in transaction mode compared
with direct JDBC pooling, when the total number of backend connections is held constant?*

This tool is specifically designed to answer that question by holding constant all factors other
than the connection mode (SUT) and measuring throughput, p95 latency, and error rate at each
load level.

---

## 7. Summary

The tool was constructed because:

| Requirement | Available Tools | Gap |
|---|---|---|
| JDBC client (not C) | pgbench, Sysbench | All use native C clients |
| Open-loop load generation | Limited in pgbench, JMeter | Not the default; often misconfigured |
| Disciplined pooling model | None | Not a concept in existing benchmarks |
| Overload and recovery measurement | None | Not implemented in any public tool |
| HdrHistogram latency accuracy | Not pgbench | pgbench uses arithmetic mean only |
| Reproducible, seedable data | Partial in YCSB | Not for PostgreSQL connection-pooling studies |
| JDBC prepared-statement interactions with PgBouncer | None | Requires JDBC client |

The combination of these gaps makes it impossible to draw scientifically defensible conclusions
about PgBouncer's fitness for Java-based applications using existing public benchmarking tools.
This harness is designed to fill all of them.

---

*Document version: 1.0 — February 2026*

---

## References

[1] Little, J.D.C. (1961). A Proof for the Queueing Formula: L = λW. *Operations Research*,
9(3), 383–387. <https://doi.org/10.1287/opre.9.3.383>

[2] Blackman, D. and Vigna, S. (2021). Scrambled Linear Pseudorandom Number Generators. *ACM
Transactions on Mathematical Software*, 47(4), Article 36.
<https://doi.org/10.1145/3460772>

[3] HdrHistogram Authors. *HdrHistogram: A High Dynamic Range Histogram*. GitHub repository:
<https://github.com/HdrHistogram/HdrHistogram>. For the coordinated-omission problem, see also:
G. Tene, "How NOT to Measure Latency", QCon San Francisco 2013.
<https://www.infoq.com/presentations/latency-response-time/>

[4] Tiger Data (formerly Timescale). *State of PostgreSQL 2024 Survey* (n = 688 respondents;
PgBouncer identified as the leading connection pooler in the PostgreSQL ecosystem). Available at:
<https://www.tigerdata.com/state-of-postgres/2024>
