# Ansible Automation for OJP Benchmark

End-to-end automation for installing software, executing the benchmark suite,
collecting results, and generating a report — all from a single control node.

## What it does

| Step | Playbook / Script | What happens |
|------|------------------|--------------|
| 1 | `setup.yml` | Installs PostgreSQL 16 on the DB node and tunes it for benchmarking. Installs Java 21 + OJP Server on each proxy node (as a `systemd` service). Builds the `bench` tool on the control node. Initialises the benchmark database. |
| 2 | `run_benchmarks.yml` | Renders a parameterised bench config, runs a warmup pass, then launches `N` bench JVM replicas in parallel. Waits for all replicas, then generates a Markdown report. |
| 3 | `teardown.yml` | Stops OJP Server on all proxy nodes and resets PostgreSQL statistics for the next run. |
| — | `scripts/generate_report.sh` | Pure shell + `jq` script called automatically by `run_benchmarks.yml`; can also be run standalone. |

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

## Quick start

### 1. Create your inventory

```bash
cp ansible/inventory.yml.example ansible/inventory.yml
# Fill in your actual IPs / hostnames and SSH user
```

### 2. Set up all machines

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml
```

This installs all software, configures PostgreSQL, starts OJP Server on every
proxy node, builds the `bench` tool, and seeds the database.

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

## Customising a run

All numeric parameters have defaults in `group_vars/all.yml` and the role
`defaults/main.yml` files. Override any of them on the command line:

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/run_benchmarks.yml \
  -e run_name=ojp-tuning-1      \
  -e bench_target_rps=1000      \
  -e bench_duration_seconds=600 \
  -e bench_replica_count=8
```

---

## Selective execution with tags

`setup.yml` exposes the following tags:

| Tag | What runs |
|-----|-----------|
| `db` | PostgreSQL install + configure |
| `proxy` | Java 21 + OJP Server install |
| `bench` | Build bench tool |
| `init-db` | Seed benchmark database |

Example — re-run only the proxy setup after a server replacement:

```bash
ansible-playbook -i ansible/inventory.yml ansible/playbooks/setup.yml --tags proxy
```

---

## Report generation (standalone)

```bash
ansible/scripts/generate_report.sh results/ojp-run-1 results/ojp-run-1/report.md
```

Reads every `summary.json` under `results/ojp-run-1/`, produces aggregate
latency percentiles, throughput, error rate, and evaluates the p95 < 50 ms /
error rate < 0.1 % SLOs.

---

## Directory structure

```
ansible/
├── README.md                          # This file
├── inventory.yml.example              # Inventory template — copy and fill in IPs
├── group_vars/
│   ├── all.yml                        # Shared variables (OJP version, DB creds, …)
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
│   ├── ojp_proxy/                     # Install Java 21 + OJP Server (systemd)
│   │   ├── defaults/main.yml
│   │   ├── handlers/main.yml
│   │   ├── tasks/main.yml
│   │   └── templates/
│   │       └── ojp-server.service.j2
│   └── bench_control/                 # Build bench tool on the control node
│       ├── defaults/main.yml
│       └── tasks/main.yml
├── playbooks/
│   ├── setup.yml                      # Full infrastructure setup
│   ├── run_benchmarks.yml             # Execute benchmarks + generate report
│   └── teardown.yml                   # Stop services, reset DB stats
├── templates/
│   └── ojp-benchmark.yaml.j2          # Parameterised bench config template
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
