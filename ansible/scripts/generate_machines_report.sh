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

# ── Helpers ───────────────────────────────────────────────────────────────────

collected_at=$(jq -r '.collected_at // "N/A"' "${NODE_INVENTORY}")
host_count=$(jq '.hosts | length' "${NODE_INVENTORY}")

# Format memory: MiB → GiB rounded to one decimal place
mem_gib() {
  # $1 = jq expression for the memory_mb field (already selected as a number or null)
  awk "BEGIN { if ($1 == \"null\" || $1 == 0) { print \"N/A\" } else { printf \"%.1f GiB\", $1 / 1024 } }"
}

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

  # One summary row per host
  jq -r '
    .hosts[] |
    {
      h:   .inventory_hostname,
      ip:  (.ansible_host // "N/A"),
      r:   ((.roles // []) | join(", ") | if . == "" then "—" else . end),
      os:  ((.os_name // "N/A") + " " + (.os_version // "") | rtrimstr(" ")),
      arc: (.architecture // "N/A"),
      cpu: (.cpu_model // "N/A"),
      skt: (if .cpu_sockets != null then (.cpu_sockets | tostring) else "N/A" end),
      cps: (if .cpu_cores_per_socket != null then (.cpu_cores_per_socket | tostring) else "N/A" end),
      vcpu:(if .cpu_vcpus != null then (.cpu_vcpus | tostring) else "N/A" end),
      mem: (if .total_memory_mb != null and .total_memory_mb > 0
            then (.total_memory_mb / 1024 * 10 | round / 10 | tostring) + " GiB"
            else "N/A" end)
    } |
    "| \(.h) | `\(.ip)` | \(.r) | \(.os) | \(.arc) | \(.cpu) | \(.skt) × \(.cps) | \(.vcpu) | \(.mem) |"
  ' "${NODE_INVENTORY}"

  echo ""
  echo "---"
  echo ""
  echo "## Detailed Specifications"
  echo ""

  # Detailed block per host
  jq -r '
    .hosts[] |
    "### " + .inventory_hostname + "\n\n" +
    "| Field | Value |\n" +
    "|-------|-------|\n" +
    "| **Inventory hostname** | `" + .inventory_hostname + "` |\n" +
    "| **IP address** | `" + (.ansible_host // "N/A") + "` |\n" +
    "| **Role(s)** | " + ((.roles // []) | join(", ") | if . == "" then "—" else . end) + " |\n" +
    "| **OS name** | " + (.os_name // "N/A") + " |\n" +
    "| **OS version** | " + (.os_version // "N/A") + " |\n" +
    "| **OS release codename** | " + (.os_release // "N/A") + " |\n" +
    "| **Kernel** | " + (.kernel // "N/A") + " |\n" +
    "| **Architecture** | " + (.architecture // "N/A") + " |\n" +
    "| **CPU model** | " + (.cpu_model // "N/A") + " |\n" +
    "| **CPU sockets** | " + (if .cpu_sockets != null then (.cpu_sockets | tostring) else "N/A" end) + " |\n" +
    "| **Cores per socket** | " + (if .cpu_cores_per_socket != null then (.cpu_cores_per_socket | tostring) else "N/A" end) + " |\n" +
    "| **Threads per core** | " + (if .cpu_threads_per_core != null then (.cpu_threads_per_core | tostring) else "N/A" end) + " |\n" +
    "| **Total vCPUs** | " + (if .cpu_vcpus != null then (.cpu_vcpus | tostring) else "N/A" end) + " |\n" +
    "| **Total memory** | " +
      (if .total_memory_mb != null and .total_memory_mb > 0
       then (.total_memory_mb / 1024 * 10 | round / 10 | tostring) + " GiB (" + (.total_memory_mb | tostring) + " MiB)"
       else "N/A" end) + " |\n"
  ' "${NODE_INVENTORY}"

cat <<FOOTER
---

*Generated by \`ansible/scripts/generate_machines_report.sh\` on $(date -u '+%Y-%m-%dT%H:%M:%SZ')*
FOOTER
} > "${OUTPUT_FILE}"

echo "Machines report written to: ${OUTPUT_FILE}"
