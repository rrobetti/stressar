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
#   ansible/scripts/run_production_comparison.sh [inventory_file] --tests hikari
#   ansible/scripts/run_production_comparison.sh [inventory_file] --tests hikari,ojp
#   ansible/scripts/run_production_comparison.sh [inventory_file] --tests ojp_sqs
#   ansible/scripts/run_production_comparison.sh [inventory_file] --tests ojp --repeat 5
#   ansible/scripts/run_production_comparison.sh [inventory_file] --debug
#   ansible/scripts/run_production_comparison.sh [inventory_file] --debug --log-file /path/to/ansible.log
#
# Default inventory file:
#   ansible/inventory.yml

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANSIBLE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${ANSIBLE_DIR}/.." && pwd)"

usage() {
  cat <<EOF
Usage:
  $(basename "$0") [inventory_file] [--tests hikari,pgbouncer,ojp,ojp_sqs] [--repeat N] [--debug [--log-file PATH]]
  $(basename "$0") --inventory <path> [--tests hikari,pgbouncer,ojp,ojp_sqs] [--repeat N] [--debug [--log-file PATH]]

Options:
  -i, --inventory PATH   Inventory file (default: ${ANSIBLE_DIR}/inventory.yml)
      --tests LIST       Comma-separated benchmarks to run: hikari, pgbouncer, ojp, ojp_sqs
      --repeat N         Number of times to run the selected benchmark sequence (default: 1)
      --repetitions N    Alias for --repeat
      --debug            Enable Ansible verbose output (-vvv) and capture to a log file.
      --log-file PATH    File to write Ansible debug output to (implies --debug).
                         Defaults to ansible-debug-<timestamp>.log in the current
                         working directory when --debug is set without --log-file.
  -h, --help             Show this help

If --tests is omitted, the script runs all benchmarks in the default order:
  hikari, pgbouncer, ojp

Debug mode is disabled by default. Pass --debug to enable -vvv verbosity and
capture ansible-playbook output to a log file.
EOF
}

INVENTORY_FILE="${ANSIBLE_DIR}/inventory.yml"
LOG_FILE=""
DEBUG_MODE=false
RUN_REPETITIONS=1
DEFAULT_BENCHMARKS=(hikari pgbouncer ojp)
BENCHMARKS_TO_RUN=("${DEFAULT_BENCHMARKS[@]}")
POSITIONAL_INVENTORY_SET=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i|--inventory)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: missing value for $1" >&2
        usage >&2
        exit 1
      fi
      INVENTORY_FILE="$2"
      POSITIONAL_INVENTORY_SET=true
      shift 2
      ;;
    --log-file)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: missing value for --log-file" >&2
        usage >&2
        exit 1
      fi
      if [[ -z "$2" ]]; then
        echo "ERROR: --log-file value cannot be empty" >&2
        usage >&2
        exit 1
      fi
      LOG_FILE="$2"
      DEBUG_MODE=true
      shift 2
      ;;
    --debug)
      DEBUG_MODE=true
      shift
      ;;
    --tests)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: missing value for --tests" >&2
        usage >&2
        exit 1
      fi
      IFS=',' read -r -a BENCHMARKS_TO_RUN <<< "$2"
      shift 2
      ;;
    --repeat|--repetitions)
      if [[ $# -lt 2 ]]; then
        echo "ERROR: missing value for $1" >&2
        usage >&2
        exit 1
      fi
      if ! [[ "$2" =~ ^[1-9][0-9]*$ ]]; then
        echo "ERROR: $1 must be a positive integer (got: $2)" >&2
        usage >&2
        exit 1
      fi
      RUN_REPETITIONS="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if [[ "${POSITIONAL_INVENTORY_SET}" == false ]]; then
        INVENTORY_FILE="$1"
        POSITIONAL_INVENTORY_SET=true
        shift
      else
        echo "ERROR: unrecognized argument: $1" >&2
        usage >&2
        exit 1
      fi
      ;;
  esac
done

NORMALIZED_BENCHMARKS=()
for benchmark in "${BENCHMARKS_TO_RUN[@]}"; do
  benchmark="$(printf '%s' "${benchmark}" | tr '[:upper:]' '[:lower:]')"
  benchmark="${benchmark//[[:space:]]/}"

  case "${benchmark}" in
    hikari|pgbouncer|ojp|ojp_sqs)
      already_present=false
      for existing in "${NORMALIZED_BENCHMARKS[@]}"; do
        if [[ "${existing}" == "${benchmark}" ]]; then
          already_present=true
          break
        fi
      done
      if [[ "${already_present}" == false ]]; then
        NORMALIZED_BENCHMARKS+=("${benchmark}")
      fi
      ;;
    "")
      ;;
    *)
      echo "ERROR: unsupported benchmark in --tests: ${benchmark}" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ ${#NORMALIZED_BENCHMARKS[@]} -eq 0 ]]; then
  echo "ERROR: --tests must select at least one benchmark" >&2
  usage >&2
  exit 1
fi

BENCHMARKS_TO_RUN=("${NORMALIZED_BENCHMARKS[@]}")
SETUP_PLAYBOOK="${ANSIBLE_DIR}/playbooks/setup.yml"
TEARDOWN_PLAYBOOK="${ANSIBLE_DIR}/playbooks/teardown.yml"
RUN_HIKARI_PLAYBOOK="${ANSIBLE_DIR}/playbooks/run_benchmarks_hikari.yml"
RUN_OJP_PLAYBOOK="${ANSIBLE_DIR}/playbooks/run_benchmarks_ojp.yml"
RUN_PGBOUNCER_PLAYBOOK="${ANSIBLE_DIR}/playbooks/run_benchmarks_pgbouncer.yml"

PROD_HIKARI_VARS="${ANSIBLE_DIR}/vars/prod-hikari.yml"
PROD_OJP_VARS="${ANSIBLE_DIR}/vars/prod-ojp.yml"
PROD_OJP_SQS_VARS="${ANSIBLE_DIR}/vars/prod-ojp-sqs.yml"
PROD_PGBOUNCER_VARS="${ANSIBLE_DIR}/vars/prod-pgbouncer.yml"

FAILURE_LOGS_DIR="${REPO_DIR}/results/failure-logs"
FAILED_STEPS=()
CURRENT_STEP=0
# Keep this aligned with the three run_step phases in each benchmark sequence:
# setup, run, and teardown.
STEPS_PER_BENCHMARK=3
# +1 accounts for the initial teardown that always runs before any benchmark.
TOTAL_STEPS=$((1 + (STEPS_PER_BENCHMARK * ${#BENCHMARKS_TO_RUN[@]} * RUN_REPETITIONS)))

if [[ ! -f "${INVENTORY_FILE}" ]]; then
  echo "ERROR: inventory file not found: ${INVENTORY_FILE}" >&2
  exit 1
fi

# Set up debug logging only when debug mode is enabled.
if [[ "${DEBUG_MODE}" == true ]]; then
  if [[ -z "${LOG_FILE}" ]]; then
    LOG_FILE="$(pwd)/ansible-debug-$(date +%Y%m%d-%H%M%S).log"
  fi
  export ANSIBLE_LOG_PATH="${LOG_FILE}"
  echo "Debug mode enabled. Ansible log: ${LOG_FILE}"
  echo ""
fi

run_playbook() {
  local playbook="$1"
  shift
  if [[ "${DEBUG_MODE}" == true ]]; then
    (cd "${REPO_DIR}" && ansible-playbook -vvv -i "${INVENTORY_FILE}" "${playbook}" "$@")
  else
    (cd "${REPO_DIR}" && ansible-playbook -i "${INVENTORY_FILE}" "${playbook}" "$@")
  fi
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
  # ansible.builtin.fetch cannot transfer directories; create a tarball on
  # each loadgen host first, fetch it, then extract locally.
  echo "  Collecting Load Generator node run directories..."
  (
    cd "${REPO_DIR}" && \
    ansible loadgen \
      -i "${INVENTORY_FILE}" \
      -m ansible.builtin.shell \
      -a "if [ -d /tmp/stressar-runs ]; then tar -czf /tmp/stressar-runs.tar.gz -C /tmp stressar-runs; else echo 'WARN: /tmp/stressar-runs not found on this host' >&2; fi" \
      2>&1 || true
  )
  (
    cd "${REPO_DIR}" && \
    ansible loadgen \
      -i "${INVENTORY_FILE}" \
      -m ansible.builtin.fetch \
      -a "src=/tmp/stressar-runs.tar.gz dest=${dest}/loadgen/ flat=no fail_on_missing=false" \
      2>&1 || true
  )
  # Extract each per-host tarball so the directory tree is human-readable.
  find "${dest}/loadgen" -name 'stressar-runs.tar.gz' | while IFS= read -r tarball; do
    if ! tar -xzf "${tarball}" -C "$(dirname "${tarball}")" 2>&1; then
      echo "  WARN: failed to extract ${tarball} — archive may be incomplete" >&2
    fi
    rm -f "${tarball}"
  done
  echo "  Load Generator logs -> ${dest}/loadgen/"

  # ── Proxy service logs (when applicable) ──────────────────────────────────
  if [[ "${proxy_type}" == "ojp" ]]; then
    echo "  Collecting OJP proxy application logs..."
    (
      cd "${REPO_DIR}" && \
      ansible ojp \
        -i "${INVENTORY_FILE}" \
        --become \
        -m ansible.builtin.fetch \
        -a "src=/var/log/ojp-server.log dest=${dest}/ojp/ flat=no fail_on_missing=false validate_checksum=false" \
        2>&1 || true
    )
    echo "  OJP proxy app logs   -> ${dest}/ojp/"

    echo "  Collecting OJP proxy journal logs..."
    (
      cd "${REPO_DIR}" && \
      ansible ojp \
        -i "${INVENTORY_FILE}" \
        --become \
        -m ansible.builtin.shell \
        -a "journalctl -u ojp-server.service --no-pager -n 5000 2>&1 || true" \
        2>&1 | tee "${dest}/ojp-server.journal.log" || true
    )
    echo "  OJP proxy journal   -> ${dest}/ojp-server.journal.log"
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

run_step() {
  local step_slug="$1"
  local step_message="$2"
  local proxy_type="$3"
  shift 3

  CURRENT_STEP=$((CURRENT_STEP + 1))
  echo "== [${CURRENT_STEP}/${TOTAL_STEPS}] ${step_message} =="

  "$@" || collect_failure_logs "step-${CURRENT_STEP}-${step_slug}" "${proxy_type}"
}

run_hikari_sequence() {
  run_step \
    "hikari-setup" \
    "Setup Hikari disciplined environment (prod-hikari: budget 300)" \
    "" \
    run_playbook "${SETUP_PLAYBOOK}" --tags db,bench,init-db -e @"${PROD_HIKARI_VARS}"

  run_step \
    "hikari-run" \
    "Run Hikari disciplined production benchmark" \
    "" \
    run_playbook "${RUN_HIKARI_PLAYBOOK}" -e @"${PROD_HIKARI_VARS}"

  run_step \
    "teardown" \
    "Teardown all services and reset DB stats" \
    "" \
    run_playbook "${TEARDOWN_PLAYBOOK}"
}

run_pgbouncer_sequence() {
  run_step \
    "pgbouncer-setup" \
    "Setup pgBouncer environment" \
    "pgbouncer" \
    run_playbook "${SETUP_PLAYBOOK}" --tags db,pgbouncer,haproxy,bench,init-db -e @"${PROD_PGBOUNCER_VARS}"

  run_step \
    "pgbouncer-run" \
    "Run pgBouncer production benchmark" \
    "pgbouncer" \
    run_playbook "${RUN_PGBOUNCER_PLAYBOOK}" -e @"${PROD_PGBOUNCER_VARS}"

  run_step \
    "teardown" \
    "Teardown all services and reset DB stats" \
    "" \
    run_playbook "${TEARDOWN_PLAYBOOK}"
}

run_ojp_sequence() {
  run_step \
    "ojp-setup" \
    "Setup OJP environment" \
    "ojp" \
    run_playbook "${SETUP_PLAYBOOK}" --tags db,ojp,bench,init-db -e @"${PROD_OJP_VARS}"

  run_step \
    "ojp-run" \
    "Run OJP production benchmark" \
    "ojp" \
    run_playbook "${RUN_OJP_PLAYBOOK}" -e @"${PROD_OJP_VARS}"

  run_step \
    "teardown" \
    "Teardown all services and reset DB stats" \
    "" \
    run_playbook "${TEARDOWN_PLAYBOOK}"
}

run_ojp_sqs_sequence() {
  run_step \
    "ojp-sqs-setup" \
    "Setup OJP environment (slow query segregation enabled)" \
    "ojp" \
    run_playbook "${SETUP_PLAYBOOK}" --tags db,ojp,bench,init-db -e @"${PROD_OJP_SQS_VARS}"

  run_step \
    "ojp-sqs-run" \
    "Run OJP production benchmark (slow query segregation enabled)" \
    "ojp" \
    run_playbook "${RUN_OJP_PLAYBOOK}" -e @"${PROD_OJP_SQS_VARS}"

  run_step \
    "teardown" \
    "Teardown all services and reset DB stats" \
    "" \
    run_playbook "${TEARDOWN_PLAYBOOK}"
}

run_step \
  "teardown" \
  "Teardown all services and reset DB stats" \
  "" \
  run_playbook "${TEARDOWN_PLAYBOOK}"

for repetition in $(seq 1 "${RUN_REPETITIONS}"); do
  if [[ "${RUN_REPETITIONS}" -gt 1 ]]; then
    echo ""
    echo "== Repetition ${repetition}/${RUN_REPETITIONS} =="
  fi

  for benchmark in "${BENCHMARKS_TO_RUN[@]}"; do
    case "${benchmark}" in
      hikari)
        run_hikari_sequence
        ;;
      pgbouncer)
        run_pgbouncer_sequence
        ;;
      ojp)
        run_ojp_sequence
        ;;
      ojp_sqs)
        run_ojp_sqs_sequence
        ;;
    esac
  done
done

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
