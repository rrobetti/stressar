#!/usr/bin/env bash
# ansible/scripts/generate_report.sh
#
# Compile OJP benchmark results into a Markdown report.
#
# Reads every summary.json found under RESULTS_DIR, extracts key metrics
# using jq, and writes a structured Markdown report to OUTPUT_FILE.
#
# Usage:
#   generate_report.sh <RESULTS_DIR> [OUTPUT_FILE]
#
# Arguments:
#   RESULTS_DIR   Directory produced by a bench run (e.g. results/ojp-run-1).
#                 May contain multiple replica-N/ subdirectories.
#   OUTPUT_FILE   Path to the generated report (default: RESULTS_DIR/report.md).
#
# Requirements: jq >= 1.6, bash >= 4.

set -euo pipefail

# ── Arguments ─────────────────────────────────────────────────────────────────

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <RESULTS_DIR> [OUTPUT_FILE]" >&2
  exit 1
fi

RESULTS_DIR="${1%/}"   # strip trailing slash
OUTPUT_FILE="${2:-${RESULTS_DIR}/report.md}"

if [[ ! -d "${RESULTS_DIR}" ]]; then
  echo "ERROR: results directory '${RESULTS_DIR}' does not exist." >&2
  exit 1
fi

command -v jq >/dev/null 2>&1 || {
  echo "ERROR: jq is required but not found. Install with: brew install jq  or  sudo apt-get install -y jq" >&2
  exit 1
}

# ── Collect summary files ─────────────────────────────────────────────────────

mapfile -t SUMMARY_FILES < <(find "${RESULTS_DIR}" -name "summary.json" | sort)

if [[ ${#SUMMARY_FILES[@]} -eq 0 ]]; then
  echo "ERROR: no summary.json files found under '${RESULTS_DIR}'." >&2
  exit 1
fi

# ── Helper: extract a field (returns "N/A" if absent or null) ─────────────────

jq_field() {
  local file="$1" field="$2"
  jq -r "${field} // \"N/A\"" "${file}"
}

# ── Aggregate across all instances ───────────────────────────────────────────
# For multi-replica runs, compute mean of per-instance metrics.

total_achieved_rps=0
total_p50=0; total_p95=0; total_p99=0; total_p999=0
total_error_rate=0
total_total_requests=0
total_failed_requests=0
instance_count=0

for f in "${SUMMARY_FILES[@]}"; do
  total_achieved_rps=$(jq -r ".achievedThroughputRps // 0" "${f}" | awk -v acc="${total_achieved_rps}" '{print acc + $1}')
  total_p50=$(jq -r ".latencyMs.p50  // 0" "${f}" | awk -v acc="${total_p50}"  '{print acc + $1}')
  total_p95=$(jq -r ".latencyMs.p95  // 0" "${f}" | awk -v acc="${total_p95}"  '{print acc + $1}')
  total_p99=$(jq -r ".latencyMs.p99  // 0" "${f}" | awk -v acc="${total_p99}"  '{print acc + $1}')
  total_p999=$(jq -r ".latencyMs.p999 // 0" "${f}" | awk -v acc="${total_p999}" '{print acc + $1}')
  total_error_rate=$(jq -r ".errorRate // 0" "${f}" | awk -v acc="${total_error_rate}" '{print acc + $1}')
  total_total_requests=$(jq -r ".totalRequests // 0"  "${f}" | awk -v acc="${total_total_requests}"  '{print acc + $1}')
  total_failed_requests=$(jq -r ".failedRequests // 0" "${f}" | awk -v acc="${total_failed_requests}" '{print acc + $1}')
  (( instance_count++ ))
done

avg() { awk "BEGIN {printf \"%.2f\", $1 / $2}"; }

agg_rps=$(avg "${total_achieved_rps}" "${instance_count}")
agg_p50=$(avg "${total_p50}"  "${instance_count}")
agg_p95=$(avg "${total_p95}"  "${instance_count}")
agg_p99=$(avg "${total_p99}"  "${instance_count}")
agg_p999=$(avg "${total_p999}" "${instance_count}")
agg_error_rate=$(avg "${total_error_rate}" "${instance_count}")
total_agg_rps=$(awk "BEGIN {printf \"%.2f\", ${total_achieved_rps}}")

# ── SLO evaluation ────────────────────────────────────────────────────────────
# SLO: p95 < 50 ms, error rate < 0.1 %

slo_p95_limit=50
slo_error_limit=0.001
p95_pass=$(awk "BEGIN {print (${agg_p95} < ${slo_p95_limit}) ? \"✅ PASS\" : \"❌ FAIL\"}")
error_pass=$(awk "BEGIN {print (${agg_error_rate} < ${slo_error_limit}) ? \"✅ PASS\" : \"❌ FAIL\"}")

# ── Read run metadata from first summary ──────────────────────────────────────

first="${SUMMARY_FILES[0]}"
run_sut=$(jq_field "${first}" ".runInfo.sut")
run_workload=$(jq_field "${first}" ".runInfo.workload")
run_ts=$(jq_field "${first}" ".runInfo.timestamp")
run_duration=$(jq_field "${first}" ".runInfo.durationSeconds")
run_target_rps=$(jq_field "${first}" ".runInfo.targetRps")

# ── Write report ──────────────────────────────────────────────────────────────

mkdir -p "$(dirname "${OUTPUT_FILE}")"

{
cat <<HEADER
# OJP Benchmark Report

| Field        | Value |
|-------------|-------|
| **SUT**      | ${run_sut} |
| **Workload** | ${run_workload} |
| **Run time** | ${run_ts} |
| **Duration** | ${run_duration} s |
| **Instances**| ${instance_count} |
| **Target RPS**| ${run_target_rps} (per instance) |
| **Results dir** | \`${RESULTS_DIR}\` |

---

## Aggregate Metrics (mean across ${instance_count} instance(s))

| Metric | Value |
|--------|-------|
| **Achieved throughput** | ${agg_rps} RPS (per instance) |
| **Total throughput** | ${total_agg_rps} RPS (all instances) |
| **p50 latency** | ${agg_p50} ms |
| **p95 latency** | ${agg_p95} ms |
| **p99 latency** | ${agg_p99} ms |
| **p999 latency** | ${agg_p999} ms |
| **Error rate** | ${agg_error_rate} |
| **Total requests** | ${total_total_requests} |
| **Failed requests** | ${total_failed_requests} |

---

## SLO Evaluation

| SLO | Threshold | Result |
|-----|-----------|--------|
| p95 latency | < ${slo_p95_limit} ms | ${p95_pass} (${agg_p95} ms) |
| Error rate | < $(awk "BEGIN {printf \"%.1f%%\", ${slo_error_limit} * 100}") | ${error_pass} ($(awk "BEGIN {printf \"%.4f\", ${agg_error_rate}}")) |

---

## Per-Instance Breakdown

| Instance | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error Rate |
|----------|----------|----------|----------|-----------------|------------|
HEADER

for f in "${SUMMARY_FILES[@]}"; do
  inst=$(jq -r ".runInfo.instanceId // \"?\"" "${f}")
  p50=$(jq -r ".latencyMs.p50  // \"N/A\"" "${f}")
  p95=$(jq -r ".latencyMs.p95  // \"N/A\"" "${f}")
  p99=$(jq -r ".latencyMs.p99  // \"N/A\"" "${f}")
  rps=$(jq -r ".achievedThroughputRps // \"N/A\"" "${f}")
  err=$(jq -r ".errorRate // \"N/A\"" "${f}")
  echo "| ${inst} | ${p50} | ${p95} | ${p99} | ${rps} | ${err} |"
done

cat <<FOOTER

---

*Generated by \`ansible/scripts/generate_report.sh\` on $(date -u '+%Y-%m-%dT%H:%M:%SZ')*
FOOTER
} > "${OUTPUT_FILE}"

echo "Report written to: ${OUTPUT_FILE}"
