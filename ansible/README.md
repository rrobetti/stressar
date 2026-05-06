# Ansible Automation for Stressar Benchmarks

End-to-end automation for installing software, executing the benchmark suite,
collecting results, and generating a report — all from a single control node.

Two benchmark scenarios are supported:

| Scenario | Proxy Technology | Playbook |
|----------|-----------------|----------|
| **SUT-B — OJP** | OJP Server (client-side JDBC load balancing, no dedicated LB) | `run_benchmarks.yml` |
| **SUT-C — pgBouncer** | pgBouncer + HAProxy load balancer | `run_benchmarks_pgbouncer.yml` |

---

## What it does

| Step | Playbook / Script | What happens |
|------|------------------|--------------|
| 1 | `setup.yml` | Installs PostgreSQL 16 on the DB node and tunes it for benchmarking. Installs Java 24 + OJP Server on each proxy node (as a `systemd` service). Builds the `bench` tool on the control node. Initialises the benchmark database. |
| 2a | `run_benchmarks.yml` | **OJP (SUT-B):** Renders a parameterised bench config, runs a warmup pass, then launches `N` bench JVM replicas in parallel. Collects OJP JVM metrics and PostgreSQL metrics. Generates a Markdown report. |
| 2b | `run_benchmarks_pgbouncer.yml` | **pgBouncer (SUT-C):** Same as above but connects through HAProxy → pgBouncer instead of OJP. Collects PostgreSQL metrics only (pgBouncer has no JVM). |
| 3 | `teardown.yml` | Stops OJP Server on all proxy nodes and resets PostgreSQL statistics for the next run. |
| — | `scripts/generate_report.sh` | Pure shell + `jq` script called automatically by both run playbooks; can also be run standalone. |

> **pgBouncer infrastructure note:** `setup.yml` installs OJP Server on proxy nodes. pgBouncer and
> HAProxy must be installed and configured manually before running `run_benchmarks_pgbouncer.yml`.
> **pgBouncer runs on the same PROXY-1/2/3 machines as OJP** — stop the OJP service first, then
> install and start pgBouncer on those same nodes. See
> [docs/BENCHMARKING_GUIDE.md §§ 3.3–5](../docs/BENCHMARKING_GUIDE.md#33-install-haproxy-on-lb-t3-only)
> for step-by-step installation and configuration instructions.

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
| SUT-B dry-run (OJP) | **5** — 1 control (local) + 1 DB + 3 proxy | 1 vCPU / 1 GB RAM each |
| SUT-C dry-run (pgBouncer) | **4** — 1 control (local) + 1 DB + 1 LB + 1 proxy | 1 vCPU / 1 GB RAM each |
| SUT-B production (OJP) | **7** — 1 control (local) + 2 load generators + 1 DB + 3 proxy | See [Hardware Specifications](../docs/BENCHMARKING_GUIDE.md#2-hardware-specifications) |
| SUT-C production (pgBouncer) | **8** — 1 control (local) + 2 load generators + 1 DB + 1 LB + 3 proxy | See [Hardware Specifications](../docs/BENCHMARKING_GUIDE.md#2-hardware-specifications) |

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
# Install PostgreSQL and seed the database (proxy tag installs OJP — safe to skip for SUT-C)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml --tags db,bench,init-db
```

### 3. Install pgBouncer and HAProxy manually

> **pgBouncer runs on the same PROXY-1/2/3 nodes as OJP — not on a separate set of machines.**
> The `proxy` inventory group is dual-use: OJP for SUT-B and pgBouncer for SUT-C.
> Before installing pgBouncer, stop the OJP service on those nodes:
>
> ```bash
> ansible -i ansible/inventory.yml proxy -m systemd -a "name=ojp-server state=stopped" --become
> ```

Automated Ansible roles for pgBouncer and HAProxy are not included.
Follow the manual installation steps in the deployment guide:

- **HAProxy** on the LB node → [docs/BENCHMARKING_GUIDE.md § 3.3](../docs/BENCHMARKING_GUIDE.md#33-install-haproxy-on-lb-t3-only)
- **pgBouncer** on each proxy node (PROXY-1/2/3) → [docs/BENCHMARKING_GUIDE.md § 5](../docs/BENCHMARKING_GUIDE.md#5-pgbouncer-configuration)
- Full installation reference → [docs/install/PGBOUNCER.md](../docs/install/PGBOUNCER.md)

### 4. Run the benchmark

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_pgbouncer.yml
```

Results land under `results/<run-name>/` on the control node.
A Markdown report is written to `results/<run-name>/report.md`.

### 5. Tear down (before the next run)

pgBouncer has no Ansible-managed service, so only reset the PostgreSQL statistics:

```bash
# Reset PostgreSQL statistics only (pgBouncer service management is manual)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/teardown.yml --skip-tags ojp
```

---

## Switching between OJP (SUT-B) and pgBouncer (SUT-C)

**PROXY-1, PROXY-2, and PROXY-3 are shared by both scenarios.** To switch proxy services on those
nodes, stop the current service and start the other. You do not need to re-provision the machines.

SUT-C also requires **HAProxy on the LB node**. OJP (SUT-B) does not use HAProxy (it does
client-side load balancing). Follow the steps below to install, start, and stop it when switching.

### OJP → pgBouncer

```bash
# 1. Stop OJP on all proxy nodes
ansible -i ansible/inventory.yml proxy \
  -m systemd -a "name=ojp-server state=stopped enabled=false" --become

# 2. Install pgBouncer on all proxy nodes (if not already installed)
ansible -i ansible/inventory.yml proxy \
  -m apt -a "name=pgbouncer state=present update_cache=yes" --become

# 3. Deploy pgBouncer config (see docs/BENCHMARKING_GUIDE.md § 5 for the file contents)
#    Copy your pgbouncer.ini and userlist.txt to each proxy node, then:
ansible -i ansible/inventory.yml proxy \
  -m systemd -a "name=pgbouncer state=started enabled=true" --become

# 4. Install HAProxy on the LB node (if not already installed)
ansible -i ansible/inventory.yml lb \
  -m apt -a "name=haproxy state=present update_cache=yes" --become

# 5. Deploy HAProxy config to the LB node
#    Config template (replace PROXY1/2/3_IP with actual IPs):
#
#      global
#          maxconn 10000
#      defaults
#          mode    tcp
#          timeout connect 5s
#          timeout client  300s
#          timeout server  300s
#      frontend pgbouncer_front
#          bind *:6432
#          default_backend pgbouncer_back
#      backend pgbouncer_back
#          balance leastconn
#          server proxy1 <PROXY1_IP>:6432 check inter 2s
#          server proxy2 <PROXY2_IP>:6432 check inter 2s
#          server proxy3 <PROXY3_IP>:6432 check inter 2s
#
#    Full reference: docs/install/HAPROXY.md
#    After copying /etc/haproxy/haproxy.cfg to the LB node:
ansible -i ansible/inventory.yml lb \
  -m systemd -a "name=haproxy state=started enabled=true" --become

# 6. Verify HAProxy is forwarding connections through pgBouncer to PostgreSQL
#    (run from the control node)
ansible -i ansible/inventory.yml lb \
  -m command -a "psql -h 127.0.0.1 -p 6432 -U benchuser -d benchdb -c 'SELECT 1;'" --become

# 7. Run the pgBouncer benchmark
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_pgbouncer.yml
```

### pgBouncer → OJP

```bash
# 1. Stop HAProxy on the LB node (not needed for OJP)
ansible -i ansible/inventory.yml lb \
  -m systemd -a "name=haproxy state=stopped" --become

# 2. Stop pgBouncer on all proxy nodes
ansible -i ansible/inventory.yml proxy \
  -m systemd -a "name=pgbouncer state=stopped" --become

# 3. Start OJP on all proxy nodes
ansible -i ansible/inventory.yml proxy \
  -m systemd -a "name=ojp-server state=started enabled=true" --become

# 4. Run the OJP benchmark
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks.yml
```

> **Tip:** Always reset PostgreSQL statistics between scenario runs so metrics are not polluted by
> the previous run:
>
> ```bash
> ansible-playbook -i ansible/inventory.yml ansible/playbooks/teardown.yml --skip-tags ojp
> ```

---

## Dry-run on minimal hardware

### OJP dry-run (5 × 1 vCPU / 1 GB RAM)

`ansible/vars/dryrun.yml` contains pre-tuned values for **5 × 1 vCPU / 1 GB RAM** machines
(1 control + 1 DB + 3 proxy). Use it to verify the scripts end-to-end before provisioning
full-size hardware. Expected run time: ≈ 5 minutes (seed + warmup + 60 s bench + report).

```bash
# Setup
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml \
  -e @ansible/vars/dryrun.yml

# Run
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks.yml \
  -e @ansible/vars/dryrun.yml  -e run_name=dryrun-ojp-1
```

### pgBouncer dry-run (4 × 1 vCPU / 1 GB RAM)

`ansible/vars/dryrun-pgbouncer.yml` contains pre-tuned values for a minimal pgBouncer setup
(1 control + 1 DB + 1 LB + 1 pgBouncer proxy). pgBouncer and HAProxy must be installed manually
first (see [Quick start — pgBouncer](#quick-start--pgbouncer-sut-c) step 3 above).

```bash
# Setup PostgreSQL + bench tool
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml \
  --tags db,bench,init-db  -e @ansible/vars/dryrun-pgbouncer.yml

# Run (assumes pgBouncer and HAProxy are already running)
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks_pgbouncer.yml \
  -e @ansible/vars/dryrun-pgbouncer.yml  -e run_name=dryrun-pgbouncer-1
```

Key differences between the two dry-run profiles:

| Parameter | OJP dry-run | pgBouncer dry-run | Default |
|-----------|-------------|-------------------|---------|
| `bench_num_accounts` | 10 000 | 10 000 | 1 000 000 |
| `bench_num_orders` | 100 000 | 100 000 | 10 000 000 |
| `bench_replica_count` | 1 | 1 | 4 |
| `bench_target_rps` | 50 | 50 | 500 |
| `bench_duration_seconds` | 60 | 60 | 300 |
| `pgbouncer_pool_size` | — | 2 | 2 |
| `pg_shared_buffers` | 128 MB | 128 MB | 4 GB |
| `pg_max_connections` | 50 | 50 | 400 |

---

## Customising a run

All numeric parameters have defaults in `group_vars/all.yml` and the role
`defaults/main.yml` files. Override any of them on the command line:

```bash
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
| `proxy` | Java 24 + OJP Server install (SUT-B only) |
| `bench` | Build bench tool |
| `init-db` | Seed benchmark database |

Example — set up PostgreSQL and the bench tool only (useful before a pgBouncer run):

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml --tags db,bench,init-db
```

Example — re-run only the OJP proxy setup after a server replacement:

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml --tags proxy
```

---

## Report generation (standalone)

```bash
# OJP run
ansible/scripts/generate_report.sh results/ojp-run-1 results/ojp-run-1/report.md

# pgBouncer run
ansible/scripts/generate_report.sh results/pgbouncer-run-1 results/pgbouncer-run-1/report.md
```

Reads every `summary.json` under the given results directory, produces aggregate
latency percentiles, throughput, error rate, and evaluates the p95 < 50 ms /
error rate < 0.1 % SLOs.

---

## Directory structure

```
ansible/
├── README.md                          # This file
├── inventory.yml.example              # Inventory template — copy and fill in IPs
├── group_vars/
│   ├── all.yml                        # Shared variables (OJP version, DB creds, pgBouncer port, …)
│   ├── db.yml                         # PostgreSQL tuning parameters
│   └── proxy.yml                      # Java / OJP proxy settings
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
│   └── bench_control/                 # Build bench tool on the control node
│       ├── defaults/main.yml
│       └── tasks/main.yml
├── playbooks/
│   ├── setup.yml                      # Full infrastructure setup (PostgreSQL + OJP proxy + bench)
│   ├── run_benchmarks.yml             # Execute OJP benchmarks (SUT-B) + generate report
│   ├── run_benchmarks_pgbouncer.yml   # Execute pgBouncer benchmarks (SUT-C) + generate report
│   └── teardown.yml                   # Stop OJP services, reset DB stats
├── vars/
│   ├── dryrun.yml                     # Minimal-hardware overrides for OJP dry run
│   └── dryrun-pgbouncer.yml           # Minimal-hardware overrides for pgBouncer dry run
├── templates/
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
