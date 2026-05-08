#!/usr/bin/env bash
# ansible/scripts/run_production_comparison.sh
#
# Runs the full production comparison sequence:
#  1) teardown
#  2) setup + run Hikari disciplined (300)
#  3) teardown
#  4) setup + run OJP
#  5) teardown
#  6) setup + run pgBouncer
#  7) teardown
#
# Usage:
#   ansible/scripts/run_production_comparison.sh [inventory_file]
#
# Default inventory file:
#   ansible/inventory.yml

set -euo pipefail

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

if [[ ! -f "${INVENTORY_FILE}" ]]; then
  echo "ERROR: inventory file not found: ${INVENTORY_FILE}" >&2
  exit 1
fi

run_playbook() {
  local playbook="$1"
  shift
  (cd "${REPO_DIR}" && ansible-playbook -i "${INVENTORY_FILE}" "${playbook}" "$@")
}

echo "== [1/10] Teardown all services and reset DB stats =="
run_playbook "${TEARDOWN_PLAYBOOK}"

echo "== [2/10] Setup Hikari disciplined environment (prod-hikari: budget 300) =="
run_playbook "${SETUP_PLAYBOOK}" --tags db,bench,init-db -e @"${PROD_HIKARI_VARS}"

echo "== [3/10] Run Hikari disciplined production benchmark =="
run_playbook "${RUN_HIKARI_PLAYBOOK}" -e @"${PROD_HIKARI_VARS}"

echo "== [4/10] Teardown all services and reset DB stats =="
run_playbook "${TEARDOWN_PLAYBOOK}"

echo "== [5/10] Setup OJP environment =="
run_playbook "${SETUP_PLAYBOOK}" --tags db,ojp,bench,init-db -e @"${PROD_OJP_VARS}"

echo "== [6/10] Run OJP production benchmark =="
run_playbook "${RUN_OJP_PLAYBOOK}" -e @"${PROD_OJP_VARS}"

echo "== [7/10] Teardown all services and reset DB stats =="
run_playbook "${TEARDOWN_PLAYBOOK}"

echo "== [8/10] Setup pgBouncer environment =="
run_playbook "${SETUP_PLAYBOOK}" --tags db,pgbouncer,haproxy,bench,init-db -e @"${PROD_PGBOUNCER_VARS}"

echo "== [9/10] Run pgBouncer production benchmark =="
run_playbook "${RUN_PGBOUNCER_PLAYBOOK}" -e @"${PROD_PGBOUNCER_VARS}"

echo "== [10/10] Final teardown all services and reset DB stats =="
run_playbook "${TEARDOWN_PLAYBOOK}"

echo "Production comparison sequence completed."
