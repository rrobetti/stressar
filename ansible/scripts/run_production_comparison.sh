#!/usr/bin/env bash
# ansible/scripts/run_production_comparison.sh
#
# Runs the full production comparison sequence:
#  1)  teardown
#  2)  setup Hikari disciplined (budget 300)
#  3)  run  Hikari disciplined benchmark
#  4)  teardown
#  5)  setup pgBouncer
#  6)  run  pgBouncer benchmark
#  7)  teardown
#  8)  setup OJP
#  9)  run  OJP benchmark
#  10) teardown
#
# If any step fails the script does NOT abort.  Instead it:
#   • collects all benchmark run directories from every Load Generator node,
#   • also collects proxy (OJP or pgBouncer) service logs when applicable,
#   • prints the local path of every collected artefact, and
#   • proceeds to the next step.
# A summary of all failed steps (with log paths) is printed at the end and the
# script exits with a non-zero code if at least one step failed.
#
# Usage:
#   ansible/scripts/run_production_comparison.sh [inventory_file]
#
# Default inventory file:
#   ansible/inventory.yml

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANSIBLE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${ANSIBLE_DIR}/.." && pwd)"

INVENTORY_FILE="${1:-${ANSIBLE_DIR}/inventory.yml}"
SETUP_PLAYBOOK="${ANSIBLE_DIR}/playbooks/setup.yml"
TEARDOWN_PLAYBOOK="${ANSIBLE_DIR}/playbooks/teardown.yml"
RUN_HIKARI_PLAYBOOK="${ANSIBLE_DIR}/playbooks/run_benchmarks_hikari.yml"
RUN_OJP_PLAYBOOK="${ANSIBLE_DIR}/playbooks/run_benchmarks.yml"
RUN_PGBOUNCER_PLAYBOOK="${ANSIBLE_DIR}/playbooks/run_benchmarks_pgbouncer.yml"

PROD_HIKARI_VARS="${ANSIBLE_DIR}/vars/prod-hikari.yml"
PROD_OJP_VARS="${ANSIBLE_DIR}/vars/prod-ojp.yml"
PROD_PGBOUNCER_VARS="${ANSIBLE_DIR}/vars/prod-pgbouncer.yml"

FAILURE_LOGS_DIR="${REPO_DIR}/results/failure-logs"
FAILED_STEPS=()

if [[ ! -f "${INVENTORY_FILE}" ]]; then
  echo "ERROR: inventory file not found: ${INVENTORY_FILE}" >&2
  exit 1
fi

run_playbook() {
  local playbook="$1"
  shift
  (cd "${REPO_DIR}" && ansible-playbook -i "${INVENTORY_FILE}" "${playbook}" "$@")
}

# collect_failure_logs STEP_LABEL [PROXY_TYPE]
#   STEP_LABEL  – human-readable name used in the log directory
#                 (e.g. "step-3-hikari-run")
#   PROXY_TYPE  – optional: "ojp" or "pgbouncer"
#
# Fetches artefacts from remote nodes into ${FAILURE_LOGS_DIR}/<STEP_LABEL>-<ts>/
# and prints the local paths so the operator knows where to look.
collect_failure_logs() {
  local step_label="$1"
  local proxy_type="${2:-}"
  local ts
  ts="$(date +%Y%m%d-%H%M%S)"
  local dest="${FAILURE_LOGS_DIR}/${step_label}-${ts}"
  mkdir -p "${dest}"

  echo ""
  echo "!! Step '${step_label}' FAILED – collecting logs into: ${dest}"
  echo ""

  # ── Load Generator node logs ──────────────────────────────────────────────
  echo "  Collecting Load Generator node run directories..."
  (
    cd "${REPO_DIR}" && \
    ansible loadgen \
      -i "${INVENTORY_FILE}" \
      -m ansible.builtin.fetch \
      -a "src=/tmp/stressar-runs dest=${dest}/loadgen flat=no fail_on_missing=false" \
      2>&1 || true
  )
  echo "  Load Generator logs -> ${dest}/loadgen/"

  # ── Proxy service logs (when applicable) ──────────────────────────────────
  if [[ "${proxy_type}" == "ojp" ]]; then
    echo "  Collecting OJP proxy service logs..."
    (
      cd "${REPO_DIR}" && \
      ansible ojp \
        -i "${INVENTORY_FILE}" \
        --become \
        -m ansible.builtin.shell \
        -a "journalctl -u ojp-server.service --no-pager -n 5000 2>&1 || true" \
        2>&1 | tee "${dest}/ojp-server.log" || true
    )
    echo "  OJP proxy logs      -> ${dest}/ojp-server.log"
  elif [[ "${proxy_type}" == "pgbouncer" ]]; then
    echo "  Collecting pgBouncer proxy service logs..."
    (
      cd "${REPO_DIR}" && \
      ansible pgbouncer \
        -i "${INVENTORY_FILE}" \
        --become \
        -m ansible.builtin.shell \
        -a "journalctl -u pgbouncer.service --no-pager -n 5000 2>&1 || true" \
        2>&1 | tee "${dest}/pgbouncer.log" || true
    )
    echo "  pgBouncer logs      -> ${dest}/pgbouncer.log"
  fi

  echo ""
  echo "!! All failure artefacts for '${step_label}' collected in: ${dest}"
  echo ""
  FAILED_STEPS+=("${step_label} -> ${dest}")
}

# ── Step 1: Initial teardown ──────────────────────────────────────────────────
echo "== [1/10] Teardown all services and reset DB stats =="
run_playbook "${TEARDOWN_PLAYBOOK}" || {
  collect_failure_logs "step-1-teardown"
}

# ── Steps 2–3: Hikari disciplined (budget 300) ────────────────────────────────
echo "== [2/10] Setup Hikari disciplined environment (prod-hikari: budget 300) =="
run_playbook "${SETUP_PLAYBOOK}" --tags db,bench,init-db -e @"${PROD_HIKARI_VARS}" || {
  collect_failure_logs "step-2-hikari-setup"
}

echo "== [3/10] Run Hikari disciplined production benchmark =="
run_playbook "${RUN_HIKARI_PLAYBOOK}" -e @"${PROD_HIKARI_VARS}" || {
  collect_failure_logs "step-3-hikari-run"
}

# ── Step 4: Teardown ──────────────────────────────────────────────────────────
echo "== [4/10] Teardown all services and reset DB stats =="
run_playbook "${TEARDOWN_PLAYBOOK}" || {
  collect_failure_logs "step-4-teardown"
}

# ── Steps 5–6: pgBouncer ──────────────────────────────────────────────────────
echo "== [5/10] Setup pgBouncer environment =="
run_playbook "${SETUP_PLAYBOOK}" --tags db,pgbouncer,haproxy,bench,init-db -e @"${PROD_PGBOUNCER_VARS}" || {
  collect_failure_logs "step-5-pgbouncer-setup" "pgbouncer"
}

echo "== [6/10] Run pgBouncer production benchmark =="
run_playbook "${RUN_PGBOUNCER_PLAYBOOK}" -e @"${PROD_PGBOUNCER_VARS}" || {
  collect_failure_logs "step-6-pgbouncer-run" "pgbouncer"
}

# ── Step 7: Teardown ──────────────────────────────────────────────────────────
echo "== [7/10] Teardown all services and reset DB stats =="
run_playbook "${TEARDOWN_PLAYBOOK}" || {
  collect_failure_logs "step-7-teardown"
}

# ── Steps 8–9: OJP ───────────────────────────────────────────────────────────
echo "== [8/10] Setup OJP environment =="
run_playbook "${SETUP_PLAYBOOK}" --tags db,ojp,bench,init-db -e @"${PROD_OJP_VARS}" || {
  collect_failure_logs "step-8-ojp-setup" "ojp"
}

echo "== [9/10] Run OJP production benchmark =="
run_playbook "${RUN_OJP_PLAYBOOK}" -e @"${PROD_OJP_VARS}" || {
  collect_failure_logs "step-9-ojp-run" "ojp"
}

# ── Step 10: Final teardown ───────────────────────────────────────────────────
echo "== [10/10] Final teardown all services and reset DB stats =="
run_playbook "${TEARDOWN_PLAYBOOK}" || {
  collect_failure_logs "step-10-teardown"
}

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
if [[ ${#FAILED_STEPS[@]} -eq 0 ]]; then
  echo "Production comparison sequence completed successfully."
else
  echo "Production comparison sequence completed with ${#FAILED_STEPS[@]} failed step(s):"
  for step in "${FAILED_STEPS[@]}"; do
    echo "  FAILED: ${step}"
  done
  exit 1
fi
