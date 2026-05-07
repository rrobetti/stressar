# Ansible Automation for Stressar Benchmarks

End-to-end automation for installing software, executing the benchmark suite,
collecting results, and generating a report — all from a single control node.

Three benchmark scenarios are supported:

| Scenario | Proxy Technology | Playbook |
|----------|-----------------|----------|
| **hikari-prod (SUT-A)** | Direct JDBC + local HikariCP per replica | `run_benchmarks_hikari.yml` |
| **ojp-prod (SUT-B)** | OJP server tier (no local client-side HikariCP pool) | `run_benchmarks.yml` |
| **pgbouncer-prod (SUT-C)** | Local HikariCP + PgBouncer + HAProxy | `run_benchmarks_pgbouncer.yml` |

---

## Benchmark Philosophy: Production Topology, Not Equal Knobs

This benchmark compares realistic production topologies rather than artificially identical
client-side settings or network paths.

- **hikari-prod:** larger direct DB budget (~300) intentionally models local pool multiplication in
  elastic microservices.
- **ojp-prod:** no local client-side HikariCP pool; client JDBC connections are logical and real DB
  connections are pooled in the OJP server tier (3×16=48 intended backend budget).
- **pgbouncer-prod:** realistic local HikariCP pool (`pgbouncer_local_pool_size: 20`) plus
  PgBouncer backend pooling (3×16=48) behind HAProxy.

Main pgBouncer profile sets `pgbouncer_reserve_pool_size: 0` so the advertised 48-backend DB budget
remains strict and interpretable.

---

## Inventory Group Structure (Production and Dry-Run)

Use the same logical groups in both production and dry-run inventories:

- `control` (orchestration)
- `loadgen` (benchmark JVM replicas)
- `db`
- `ojp`
- `pgbouncer`
- `haproxy`

If the `control` host is the same machine running Ansible, set `ansible_connection: local`
for that host entry in `inventory.yml`.

---

## What it does

| Step | Playbook / Script | What happens |
|------|------------------|--------------|
| 1 | `setup.yml` | Installs PostgreSQL 16 on the DB node and tunes it for benchmarking. Installs Java 24 + OJP Server on each proxy node (SUT-B). Installs pgBouncer on each proxy node and HAProxy on the LB node (SUT-C, via `--tags pgbouncer,haproxy`). Builds the `bench` tool on the control node. Initialises the benchmark database. |
| 2a | `run_benchmarks_hikari.yml` | **hikari-prod (SUT-A):** orchestrated from `control`, benchmark JVM replicas run on `loadgen` hosts, direct JDBC to PostgreSQL with local HikariCP multiplication. |
| 2b | `run_benchmarks.yml` | **ojp-prod (SUT-B):** orchestrated from `control`, benchmark JVM replicas run on `loadgen` hosts, OJP service validated on `ojp` nodes, no local client-side HikariCP pool. |
| 2c | `run_benchmarks_pgbouncer.yml` | **pgbouncer-prod (SUT-C):** orchestrated from `control`, benchmark JVM replicas run on `loadgen` hosts, traffic via `haproxy` to `pgbouncer` nodes. |
| 3 | `teardown.yml` | Stops OJP Server, pgBouncer, and HAProxy on their respective nodes and resets PostgreSQL statistics for the next run. |
| — | `scripts/generate_report.sh` | Pure shell + `jq` script called automatically by all run playbooks; can also be run standalone. |

---

## Prerequisites

On the control node (your laptop):

| Tool | Minimum version | Install |
|------|----------------|---------|
| Ansible | 2.14+ | `pip install ansible` |
| `ansible-galaxy` collection `community.postgresql` | latest | `ansible-galaxy collection install community.postgresql` |
| Java | 11+ | [docs/install/JAVA.md](../docs/install/JAVA.md) |
| `jq` | 1.6+ | `brew install jq` / `sudo apt-get install -y jq` |
| `git` | any | included on most systems |

Remote machines require only **SSH access** and **outbound internet** (for package downloads).

---

## Machine requirements

| Scenario | Machines | Hardware |
|----------|----------|----------|
| Dry-run (all SUTs) | logical groups: control + loadgen + db + ojp + pgbouncer + haproxy | 1 vCPU / 1 GB RAM each |
| SUT-A production (HikariCP Direct) | **4** — 1 control (local) + 1 DB + 2 load generators | See [Hardware Specifications](../docs/BENCHMARKING_GUIDE.md#2-hardware-specifications) |
| SUT-B production (OJP) | **7** — 1 control + 2 loadgen + 1 DB + 3 OJP nodes | See [Hardware Specifications](../docs/BENCHMARKING_GUIDE.md#2-hardware-specifications) |
| SUT-C production (pgBouncer) | **8** — 1 control + 2 loadgen + 1 DB + 1 HAProxy + 3 PgBouncer nodes | See [Hardware Specifications](../docs/BENCHMARKING_GUIDE.md#2-hardware-specifications) |

---

## Quick start — HikariCP Direct (SUT-A)

SUT-A is the **disciplined baseline**: bench replicas connect directly to PostgreSQL with no proxy
tier. The connection budget is divided equally across replicas (`HIKARI_DISCIPLINED` mode).

### 1. Create your inventory

```bash
cp ansible/inventory.yml.example ansible/inventory.yml
# Fill in DB_IP only — no proxy or LB nodes are needed for SUT-A
```

### 2. Set up the database and build the bench tool

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml \
  --tags db,bench,init-db
```

This installs PostgreSQL on the DB node, builds the `bench` tool on the control node, and seeds the
database. No proxy-tier components are installed.

### 3. Run the benchmark

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_hikari.yml
```

Results land under `results/<run-name>/` on the control node.
A Markdown report is written to `results/<run-name>/report.md`.

### 4. Tear down (before the next run)

```bash
# SUT-A has no proxy services to stop; reset PostgreSQL statistics only:
ansible-playbook -i ansible/inventory.yml ansible/playbooks/teardown.yml \
  --skip-tags ojp,pgbouncer,haproxy
```

---

## Quick start — OJP (SUT-B)

### 1. Create your inventory

```bash
cp ansible/inventory.yml.example ansible/inventory.yml
# Fill in DB_IP and PROXY1/2/3_IP; leave lb-node commented out (not needed for OJP)
```

### 2. Set up all machines

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml
```

This installs PostgreSQL, starts OJP Server on every proxy node, builds the `bench` tool, and seeds
the database.

### 3. Run the benchmark

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks.yml
```

Results land under `results/<run-name>/` on the control node.
A Markdown report is written to `results/<run-name>/report.md`.

### 4. Tear down (before the next run)

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/teardown.yml
```

---

## Quick start — pgBouncer (SUT-C)

### 1. Create your inventory

```bash
cp ansible/inventory.yml.example ansible/inventory.yml
# Fill in DB_IP, LB_IP, and PROXY1/2/3_IP
```

### 2. Install PostgreSQL and build the bench tool

```bash
# Install PostgreSQL and seed the database (ojp tag installs OJP — safe to skip for SUT-C)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml --tags db,bench,init-db
```

### 3. Install pgBouncer and HAProxy

> **pgBouncer runs on the same PROXY-1/2/3 nodes as OJP — not on a separate set of machines.**
> The `proxy` inventory group is dual-use: OJP for SUT-B and pgBouncer for SUT-C.
> The `pgbouncer` tag automatically stops the OJP service on those nodes before starting pgBouncer.

```bash
# Install and start pgBouncer on all proxy nodes + HAProxy on the LB node
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml \
  --tags pgbouncer,haproxy
```

### 4. Run the benchmark

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_pgbouncer.yml
```

Results land under `results/<run-name>/` on the control node.
A Markdown report is written to `results/<run-name>/report.md`.

### 5. Tear down (before the next run)

```bash
# Stop pgBouncer, HAProxy, and reset PostgreSQL statistics
ansible-playbook -i ansible/inventory.yml ansible/playbooks/teardown.yml --skip-tags ojp
```

To reset PostgreSQL statistics only (leaving pgBouncer and HAProxy running):

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/teardown.yml \
  --skip-tags ojp,pgbouncer,haproxy
```

---

## Switching between OJP (SUT-B) and pgBouncer (SUT-C)

**PROXY-1, PROXY-2, and PROXY-3 are shared by both scenarios.** To switch proxy services on those
nodes, stop the current service and start the other. You do not need to re-provision the machines.

SUT-C also requires **HAProxy on the LB node**. OJP (SUT-B) does not use HAProxy (it does
client-side load balancing). Use the plays below to switch between scenarios.

### OJP → pgBouncer

```bash
# 1. Install (or re-configure) pgBouncer on proxy nodes and HAProxy on the LB node.
#    The pgbouncer role automatically stops OJP Server first.
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml \
  --tags pgbouncer,haproxy

# 2. Verify HAProxy is forwarding connections through pgBouncer to PostgreSQL
ansible -i ansible/inventory.yml lb \
  -m command -a "psql -h 127.0.0.1 -p 6432 -U benchuser -d benchdb -c 'SELECT 1;'" --become

# 3. Run the pgBouncer benchmark
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_pgbouncer.yml
```

### pgBouncer → OJP

```bash
# 1. Stop HAProxy and pgBouncer, then restart OJP
ansible-playbook -i ansible/inventory.yml ansible/playbooks/teardown.yml \
  --tags pgbouncer,haproxy

ansible -i ansible/inventory.yml proxy \
  -m systemd -a "name=ojp-server state=started enabled=true" --become

# 2. Run the benchmark
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks.yml
```

> **Tip:** Always reset PostgreSQL statistics between scenario runs so metrics are not polluted by
> the previous run:
>
> ```bash
> ansible-playbook -i ansible/inventory.yml ansible/playbooks/teardown.yml \
>   --skip-tags ojp,pgbouncer,haproxy
> ```

---

## Dry-run on minimal hardware

### HikariCP Direct dry-run (2 × 1 vCPU / 1 GB RAM)

`ansible/vars/dryrun-hikari.yml` contains pre-tuned values for **2 × 1 vCPU / 1 GB RAM** machines
(1 control + 1 DB — no proxy nodes needed). Use it to verify the HikariCP Direct scripts
end-to-end before provisioning full-size hardware.
Expected run time: ≈ 5 minutes (seed + warmup + 60 s bench + report).

```bash
# Setup (DB + bench tool — no proxy components needed for SUT-A)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml \
  --tags db,bench,init-db  -e @ansible/vars/dryrun-hikari.yml

# Run (each invocation creates a new timestamped folder under results/)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_hikari.yml \
  -e @ansible/vars/dryrun-hikari.yml
```

### OJP dry-run (5 × 1 vCPU / 1 GB RAM)

`ansible/vars/dryrun-ojp.yml` contains pre-tuned values for **5 × 1 vCPU / 1 GB RAM** machines
(1 control + 1 DB + 3 proxy). Use it to verify the OJP scripts end-to-end before provisioning
full-size hardware. Expected run time: ≈ 5 minutes (seed + warmup + 60 s bench + report).

```bash
# Setup (OJP only — skips pgBouncer and HAProxy)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml \
  --tags db,ojp,bench,init-db  -e @ansible/vars/dryrun-ojp.yml

# Run (each invocation creates a new timestamped folder under results/)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks.yml \
  -e @ansible/vars/dryrun-ojp.yml
```

### pgBouncer dry-run (6 × 1 vCPU / 1 GB RAM)

`ansible/vars/dryrun-pgbouncer.yml` contains pre-tuned values for a minimal pgBouncer setup
(1 control + 1 DB + 1 LB + 3 pgBouncer proxies).

```bash
# Setup PostgreSQL + pgBouncer + HAProxy + bench tool
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml \
  --tags db,pgbouncer,haproxy,bench,init-db  -e @ansible/vars/dryrun-pgbouncer.yml

# Run (each invocation creates a new timestamped folder under results/)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_pgbouncer.yml \
  -e @ansible/vars/dryrun-pgbouncer.yml
```

Key differences between the three dry-run profiles:

| Parameter | HikariCP dry-run | OJP dry-run | pgBouncer dry-run | Default |
|-----------|------------------|-------------|-------------------|---------|
| `bench_num_accounts` | 10 000 | 10 000 | 10 000 | 1 000 000 |
| `bench_num_orders` | 100 000 | 100 000 | 100 000 | 10 000 000 |
| `bench_replica_count` | 1 | 1 | 1 | 4 |
| `bench_target_rps` | 25 | 25 | 25 | 500 |
| `bench_duration_seconds` | 60 | 60 | 60 | 300 |
| `bench_slo_p95_ms` | 300 ms | 300 ms | 300 ms | 50 ms |
| `bench_db_connection_budget` | 18 | — | — | 18 |
| `bench_hikari_max_pool_size_per_replica` | 18 | — | — | 19 |
| `pgbouncer_pool_size` | — | — | 18 | 6 |
| `pgbouncer_min_pool_size` | — | — | 18 | 6 |
| `pgbouncer_local_pool_size` | — | — | 2 | 2 |
| `pg_shared_buffers` | 128 MB | 128 MB | 128 MB | 4 GB |
| `pg_max_connections` | 50 | 50 | 50 | 400 |

> **Why 300 ms for dry runs?**  
> Dry runs are typically executed by engineers from their local machines against cloud instances
> that may be in a different region. Cross-region WAN latency alone can exceed the 50 ms
> production SLO, which would cause every dry-run sweep step to fail immediately — making the
> threshold useless for its intended purpose of catching *overload*. 300 ms is loose enough to
> tolerate typical engineer-to-cloud round-trip times while still flagging genuinely degraded
> behaviour. The 50 ms SLO remains the default for production full-hardware runs where the
> benchmark client and the SUT are co-located in the same datacenter.

---

## Production profiles

Predefined full-hardware production profiles are available under `ansible/vars/`:

- `prod-hikari.yml` (SUT-A): 16 replicas, budget 300, max per replica 19
- `prod-ojp.yml` (SUT-B): 16 replicas, OJP budget 48
- `prod-pgbouncer.yml` (SUT-C): 16 replicas, pgBouncer pool 16 per proxy node, local bench pool 20
- all production profiles set `bench_repetitions: 5` for report methodology alignment

> **TODO:** playbook-level automatic repetition loops are not yet implemented; run each profile
> multiple times and report median/min/max/variance across runs.

Usage examples:

```bash
# HikariCP Direct (SUT-A)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_hikari.yml \
  -e @ansible/vars/prod-hikari.yml

# OJP (SUT-B)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks.yml \
  -e @ansible/vars/prod-ojp.yml

# pgBouncer (SUT-C)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_pgbouncer.yml \
  -e @ansible/vars/prod-pgbouncer.yml
```

---

## Connection pool configuration by SUT (where it lives + rationale)

| SUT | Client-side pool in bench | Server-side/proxy pool | Where configured |
|-----|---------------------------|-------------------------|------------------|
| **SUT-A — Hikari Direct** | **Yes** (`poolSize`) | N/A (direct DB) | `ansible/templates/hikari-benchmark.yaml.j2` (`dbConnectionBudget`, `replicas`) → computed in `src/main/java/com/bench/config/BenchmarkConfig.java#calculateDisciplinedPoolSize` |
| **SUT-B — OJP** | **No client Hikari pool** | **Yes** (OJP server-side pool) | `ansible/templates/ojp-benchmark.yaml.j2` (`dbConnectionBudget`, `replicas`, `ojp.poolSharing`) → computed in `src/main/java/com/bench/config/BenchmarkConfig.java#calculateOjpAllocation` and applied in `src/main/java/com/bench/config/ConnectionProviderFactory.java` |
| **SUT-C — pgBouncer** | **Optional** (`poolSize`) | **Yes** (`pgbouncer_pool_size` / `pgbouncer_min_pool_size`) | Client pool: `ansible/templates/pgbouncer-benchmark.yaml.j2` (`poolSize: {{ pgbouncer_local_pool_size }}`) used by `src/main/java/com/bench/config/PgbouncerProvider.java`. Server pool: `ansible/group_vars/all.yml` or `ansible/vars/prod-pgbouncer.yml`. |

Rationale and parameter decisions:

- `docs/PARAMETER_DECISIONS.md` (especially §28 for pgBouncer client pool rationale).
- `docs/BENCHMARKING_GUIDE.md` SUT-A/B/C sections for topology and intended comparison method.

Important notes:

- For OJP, `bench_replica_count` is number of bench JVM replicas; it is **not** OJP pool size.
- In OJP `PER_INSTANCE` mode, max connections per replica are derived from `ceil(dbConnectionBudget / replicas)`.
- For pgBouncer, this repo supports both:
  - **main production profile**: `pgbouncer_local_pool_size: 20`, `pgbouncer_reserve_pool_size: 0`

---

## Customising a run

All numeric parameters have defaults in `group_vars/all.yml` and the role
`defaults/main.yml` files. Override any of them on the command line:

```bash
# HikariCP Direct (SUT-A) — production: 16 replicas × 19 connections ≈ 300 total
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_hikari.yml \
  -e run_name=hikari-tuning-1               \
  -e bench_db_connection_budget=300         \
  -e bench_replica_count=16                 \
  -e bench_hikari_max_pool_size_per_replica=19 \
  -e bench_target_rps=500                   \
  -e bench_duration_seconds=300

# OJP
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks.yml \
  -e run_name=ojp-tuning-1      \
  -e bench_target_rps=1000      \
  -e bench_duration_seconds=600 \
  -e bench_replica_count=8

# pgBouncer
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_pgbouncer.yml \
  -e run_name=pgbouncer-tuning-1 \
  -e bench_target_rps=1000       \
  -e bench_duration_seconds=600  \
  -e bench_replica_count=8       \
  -e pgbouncer_pool_size=4
```

---

## Selective execution with tags

`setup.yml` exposes the following tags:

| Tag | What runs |
|-----|-----------|
| `db` | PostgreSQL install + configure |
| `ojp` | Java 24 + OJP Server install (SUT-B only) |
| `pgbouncer` | pgBouncer install + configure on proxy nodes (SUT-C only) |
| `haproxy` | HAProxy install + configure on the LB node (SUT-C only) |
| `bench` | Build bench tool |
| `init-db` | Seed benchmark database |

`teardown.yml` exposes the following tags:

| Tag | What stops |
|-----|-----------|
| `ojp` | OJP Server on proxy nodes |
| `pgbouncer` | pgBouncer on proxy nodes |
| `haproxy` | HAProxy on the LB node |

Example — set up PostgreSQL and the bench tool only (useful before a pgBouncer run):

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml --tags db,bench,init-db
```

Example — install pgBouncer + HAProxy only (proxy nodes and LB must already exist):

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml --tags pgbouncer,haproxy
```

Example — re-run only the OJP proxy setup after a server replacement:

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml --tags ojp
```

---

## Report generation (standalone)

```bash
# HikariCP Direct run
ansible/scripts/generate_report.sh results/hikari-run-1 results/hikari-run-1/report.md

# OJP run
ansible/scripts/generate_report.sh results/ojp-run-1 results/ojp-run-1/report.md

# pgBouncer run
ansible/scripts/generate_report.sh results/pgbouncer-run-1 results/pgbouncer-run-1/report.md
```

Reads every `summary.json` under the given results directory, produces aggregate
latency percentiles, throughput, error rate, and evaluates the p95/error-rate
SLOs configured for the run.

---

## Directory structure

```
ansible/
├── README.md                          # This file
├── inventory.yml.example              # Inventory template — copy and fill in IPs
├── group_vars/
│   ├── all.yml                        # Shared variables (OJP version, DB creds, pgBouncer port, …)
│   ├── db.yml                         # PostgreSQL tuning parameters
│   └── ojp.yml                        # Java / OJP server settings
├── roles/
│   ├── postgresql/                    # Install + configure PostgreSQL 16
│   │   ├── defaults/main.yml
│   │   ├── handlers/main.yml
│   │   ├── tasks/main.yml
│   │   └── templates/
│   │       ├── postgresql.conf.j2
│   │       └── pg_hba.conf.j2
│   ├── ojp_proxy/                     # Install Java 24 + OJP Server (systemd) — SUT-B only
│   │   ├── defaults/main.yml
│   │   ├── handlers/main.yml
│   │   ├── tasks/main.yml
│   │   └── templates/
│   │       └── ojp-server.service.j2
│   ├── pgbouncer/                     # Install + configure pgBouncer on proxy nodes — SUT-C only
│   │   ├── defaults/main.yml
│   │   ├── handlers/main.yml
│   │   ├── tasks/main.yml
│   │   └── templates/
│   │       ├── pgbouncer.ini.j2
│   │       └── userlist.txt.j2
│   ├── haproxy/                       # Install + configure HAProxy on the LB node — SUT-C only
│   │   ├── defaults/main.yml
│   │   ├── handlers/main.yml
│   │   ├── tasks/main.yml
│   │   └── templates/
│   │       └── haproxy.cfg.j2
│   └── bench_control/                 # Build bench tool on the control node
│       ├── defaults/main.yml
│       └── tasks/main.yml
├── playbooks/
│   ├── setup.yml                      # Full infrastructure setup (PostgreSQL + OJP/pgBouncer/HAProxy + bench)
│   ├── run_benchmarks_hikari.yml      # Execute HikariCP Direct benchmarks (SUT-A) + generate report
│   ├── run_benchmarks.yml             # Execute benchmarks (SUT-B) + generate report
│   ├── run_benchmarks_pgbouncer.yml   # Execute pgBouncer benchmarks (SUT-C) + generate report
│   └── teardown.yml                   # Stop OJP/pgBouncer/HAProxy services, reset DB stats
├── vars/
│   ├── dryrun-hikari.yml              # Minimal-hardware overrides for HikariCP Direct (SUT-A) dry run
│   ├── dryrun-ojp.yml                 # Minimal-hardware overrides for OJP (SUT-B) dry run
│   ├── dryrun-pgbouncer.yml           # Minimal-hardware overrides for pgBouncer (SUT-C) dry run
│   ├── prod-hikari.yml                # Full-hardware production profile for HikariCP Direct (SUT-A)
│   ├── prod-ojp.yml                   # Full-hardware production profile for OJP (SUT-B)
│   └── prod-pgbouncer.yml             # Full-hardware production profile for pgBouncer (SUT-C)
├── templates/
│   ├── hikari-benchmark.yaml.j2       # Parameterised bench config template for HikariCP Direct (SUT-A)
│   ├── ojp-benchmark.yaml.j2          # Parameterised bench config template for OJP (SUT-B)
│   └── pgbouncer-benchmark.yaml.j2    # Parameterised bench config template for pgBouncer (SUT-C)
└── scripts/
    └── generate_report.sh             # jq-based Markdown report generator
```

---

## Secrets management

`db_password` defaults to `benchpass` in `group_vars/all.yml`.  
For real deployments, store it in an **Ansible Vault** file:

```bash
ansible-vault create ansible/group_vars/vault.yml
# add: vault_db_password: <strong-password>
```

Then reference it in `group_vars/all.yml`:

```yaml
db_password: "{{ vault_db_password }}"
```

And run playbooks with:

```bash
ansible-playbook ... --ask-vault-pass
```
