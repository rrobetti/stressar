#!/usr/bin/env bash
# ansible/scripts/generate_machines_report.sh
#
# Generate a Markdown report of every machine used in the benchmark.
#
# Reads the node_inventory.json written by the run_benchmarks playbooks before
# the test starts and produces machines_report.md with:
#   - A summary table (one row per host)
#   - A detailed per-host specification section
#
# Usage:
#   generate_machines_report.sh <RESULTS_DIR> [OUTPUT_FILE]
#
# Arguments:
#   RESULTS_DIR   Directory produced by a bench run (e.g. results/ojp-run-1).
#                 Must contain node_inventory.json.
#   OUTPUT_FILE   Path to the generated report (default: RESULTS_DIR/machines_report.md).
#
# Requirements: jq >= 1.6, bash >= 4.

set -euo pipefail

# ── Arguments ─────────────────────────────────────────────────────────────────

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <RESULTS_DIR> [OUTPUT_FILE]" >&2
  exit 1
fi

RESULTS_DIR="${1%/}"
OUTPUT_FILE="${2:-${RESULTS_DIR}/machines_report.md}"
NODE_INVENTORY="${RESULTS_DIR}/node_inventory.json"

if [[ ! -d "${RESULTS_DIR}" ]]; then
  echo "ERROR: results directory '${RESULTS_DIR}' does not exist." >&2
  exit 1
fi

if [[ ! -f "${NODE_INVENTORY}" ]]; then
  echo "ERROR: node_inventory.json not found at '${NODE_INVENTORY}'." >&2
  echo "       Ensure the run_benchmarks playbook was executed with gather_facts enabled." >&2
  exit 1
fi

command -v jq >/dev/null 2>&1 || {
  echo "ERROR: jq is required but not found. Install with: brew install jq  or  sudo apt-get install -y jq" >&2
  exit 1
}

collected_at=$(jq -r '.collected_at // "N/A"' "${NODE_INVENTORY}")
host_count=$(jq '.hosts | length' "${NODE_INVENTORY}")

mkdir -p "$(dirname "${OUTPUT_FILE}")"

# ── Report ────────────────────────────────────────────────────────────────────

{
cat <<HEADER
# Test Infrastructure — Machines Report

| Field | Value |
|-------|-------|
| **Collected** | ${collected_at} |
| **Total hosts** | ${host_count} |
| **Results dir** | \`${RESULTS_DIR}\` |

> Machine facts gathered from each host before the benchmark run started.

---

## Summary Table

| Host | IP Address | Role(s) | OS | Architecture | CPU Model | Sockets × Cores/Socket | vCPUs | Total Memory |
|------|-----------|---------|----|-----------|-----------|-----------------------|-------|-------------|
HEADER

  # Single jq pass: emit summary rows, then section divider, then detail blocks.
  # In jq the comma operator produces sequential output, so all summary rows appear
  # before the static separator strings and all detail blocks follow after them.
  jq -r '
    def nstr:       if . != null then tostring else "N/A" end;
    # mb_to_gib: MiB → GiB rounded to one decimal place.
    # Pattern: multiply by 10, round to integer, divide by 10.
    def mb_to_gib:  (. / 1024 * 10 | round) / 10;
    def mem_short:  if . != null and . > 0
                    then (mb_to_gib | tostring) + " GiB"
                    else "N/A" end;
    def mem_long:   if . != null and . > 0
                    then (mb_to_gib | tostring) + " GiB (" + (. | tostring) + " MiB)"
                    else "N/A" end;
    def roles_str:  (.roles // []) | join(", ") | if . == "" then "—" else . end;
    def os_str:     (.os_name // "N/A") + " " + (.os_version // "") | rtrimstr(" ");

    # ── Summary table rows ────────────────────────────────────────
    (.hosts[] |
      "| " + .inventory_hostname +
      " | `" + (.ansible_host // "N/A") + "`" +
      " | " + roles_str +
      " | " + os_str +
      " | " + (.architecture // "N/A") +
      " | " + (.cpu_model // "N/A") +
      " | " + (.cpu_sockets | nstr) + " × " + (.cpu_cores_per_socket | nstr) +
      " | " + (.cpu_vcpus | nstr) +
      " | " + (.total_memory_mb | mem_short) + " |"
    ),

    # ── Section break (output once, between summary and detail) ───
    "", "---", "", "## Detailed Specifications", "",

    # ── Per-host detail blocks ────────────────────────────────────
    (.hosts[] |
      "### " + .inventory_hostname + "\n\n" +
      "| Field | Value |\n" +
      "|-------|-------|\n" +
      "| **Inventory hostname** | `" + .inventory_hostname + "` |\n" +
      "| **IP address** | `" + (.ansible_host // "N/A") + "` |\n" +
      "| **Role(s)** | " + roles_str + " |\n" +
      "| **OS name** | " + (.os_name // "N/A") + " |\n" +
      "| **OS version** | " + (.os_version // "N/A") + " |\n" +
      "| **OS release codename** | " + (.os_release // "N/A") + " |\n" +
      "| **Kernel** | " + (.kernel // "N/A") + " |\n" +
      "| **Architecture** | " + (.architecture // "N/A") + " |\n" +
      "| **CPU model** | " + (.cpu_model // "N/A") + " |\n" +
      "| **CPU sockets** | " + (.cpu_sockets | nstr) + " |\n" +
      "| **Cores per socket** | " + (.cpu_cores_per_socket | nstr) + " |\n" +
      "| **Threads per core** | " + (.cpu_threads_per_core | nstr) + " |\n" +
      "| **Total vCPUs** | " + (.cpu_vcpus | nstr) + " |\n" +
      "| **Total memory** | " + (.total_memory_mb | mem_long) + " |\n"
    )
  ' "${NODE_INVENTORY}"

cat <<FOOTER
---

*Generated by \`ansible/scripts/generate_machines_report.sh\` on $(date -u '+%Y-%m-%dT%H:%M:%SZ')*
FOOTER
} > "${OUTPUT_FILE}"

echo "Machines report written to: ${OUTPUT_FILE}"
