# Adding a New Benchmark Test Scenario

This project already ships four runnable scenario options:
- Hikari direct: `ansible/playbooks/run_benchmarks_hikari.yml`
- OJP: `ansible/playbooks/run_benchmarks_ojp.yml`
- OJP with slow query segregation (`ojp_sqs`): `ansible/playbooks/run_benchmarks_ojp.yml` with `ansible/vars/prod-ojp-sqs.yml` (or `ansible/scripts/run_production_comparison.sh --tests ojp_sqs`)
- pgBouncer: `ansible/playbooks/run_benchmarks_pgbouncer.yml`

If you want to add a new scenario (for example ProxySQL + MySQL) and compare it to OJP, reuse the same structure.

## What must exist

1. **A scenario profile in `ansible/vars/`**
   - Follow the pattern used by `ansible/vars/prod-ojp.yml` and `ansible/vars/prod-pgbouncer.yml`.
   - Keep run parameters aligned with OJP (`bench_replica_count`, `bench_target_rps`, warmup/duration/cooldown, repetitions) so comparison is fair.

2. **Inventory groups for the new tier**
   - Start from `ansible/inventory.yml.example`.
   - Add groups/hosts required by the new topology (similar to existing `ojp`, `pgbouncer`, `haproxy` groups).

3. **Setup path for the new components**
   - Extend `ansible/playbooks/setup.yml` with a role/tag for the new proxy/database components.
   - Follow existing role patterns under `ansible/roles/` (`ojp_proxy`, `pgbouncer`, `haproxy`, `postgresql`).

4. **A benchmark run playbook**
   - Create a scenario playbook in `ansible/playbooks/`, modeled on `run_benchmarks_pgbouncer.yml` / `run_benchmarks_ojp.yml`.
   - It should render one benchmark config template, run warmup + replicas, collect metrics, and call `ansible/scripts/generate_report.sh`.

5. **A benchmark config template**
   - Add a template in `ansible/templates/` (see `hikari-benchmark.yaml.j2`, `ojp-benchmark.yaml.j2`, `pgbouncer-benchmark.yaml.j2`).
   - Template must map inventory/profile variables to the benchmark YAML consumed by `bench`.

6. **CLI connection mode support (if needed)**
   - If current modes are not enough, add a mode/provider in:
     - `src/main/java/com/bench/config/ConnectionMode.java`
     - `src/main/java/com/bench/config/ConnectionProviderFactory.java`
   - Reuse existing provider pattern (`HikariProvider`, `OjpProvider`, `PgbouncerProvider`).

7. **Database + workload compatibility**
   - Current bootstrap and metrics are PostgreSQL-oriented:
     - init path uses `jdbc:postgresql` in `ansible/playbooks/setup.yml`
     - schema/data scripts in `src/main/resources/schema/` and `src/main/resources/data/`
     - PostgreSQL metrics script `ansible/scripts/collect_pg_metrics.sh`
   - For MySQL-based tests, provide equivalent bootstrap/metrics paths and keep report inputs compatible with `ansible/scripts/generate_report.sh`.

8. **Comparison runner integration**
   - Register the scenario in `ansible/scripts/run_production_comparison.sh`:
     - accepted `--tests` value
     - vars file binding
     - setup/run/teardown sequence
   - Keep the same setup → run → teardown cycle used by existing scenarios.

## Minimal validation checklist

- New scenario runs end-to-end via its playbook.
- Existing scenarios (`hikari`, `ojp`, `pgbouncer`, `ojp_sqs`) still work.
- `run_production_comparison.sh --tests ...` accepts and executes the new scenario.
- Report generation still succeeds (`ansible/scripts/generate_report.sh`).
