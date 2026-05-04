#!/usr/bin/env bash
# ansible/scripts/generate_report.sh
#
# Compile OJP benchmark results into a Markdown report.
#
# Reads every summary.json found under RESULTS_DIR, extracts key metrics
# using jq, and writes a structured Markdown report to OUTPUT_FILE.
# Also parses side-car CSVs collected by collect_jvm_metrics.sh and
# collect_pg_metrics.sh when they are present under node_metrics/.
#
# Usage:
#   generate_report.sh <RESULTS_DIR> [OUTPUT_FILE]
#
# Arguments:
#   RESULTS_DIR   Directory produced by a bench run (e.g. results/ojp-run-1).
#                 May contain multiple replica-N/ subdirectories and a
#                 node_metrics/ subdirectory with side-car CSVs.
#   OUTPUT_FILE   Path to the generated report (default: RESULTS_DIR/report.md).
#
# Requirements: jq >= 1.6, awk, bash >= 4.

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

# ── Aggregate bench-client metrics across all instances ──────────────────────

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

# ── Aggregate in-process system metrics from summary.json (bench JVM) ─────────
# These are populated by SystemMetricsCollector running inside each bench JVM.

agg_app_cpu="N/A"
agg_gc_ms="N/A"

cpu_sum=0; cpu_count=0
gc_sum=0;  gc_count=0

for f in "${SUMMARY_FILES[@]}"; do
  v=$(jq -r ".appCpuMedian // empty" "${f}")
  if [[ -n "${v}" ]]; then
    cpu_sum=$(awk "BEGIN {print ${cpu_sum} + ${v}}")
    (( cpu_count++ ))
  fi
  v=$(jq -r ".gcPauseMsTotal // empty" "${f}")
  if [[ -n "${v}" ]]; then
    gc_sum=$(awk "BEGIN {print ${gc_sum} + ${v}}")
    (( gc_count++ ))
  fi
done

[[ ${cpu_count} -gt 0 ]] && agg_app_cpu=$(avg "${cpu_sum}" "${cpu_count}")"%"
[[ ${gc_count}  -gt 0 ]] && agg_gc_ms=$(awk "BEGIN {printf \"%.0f\", ${gc_sum}}")

# ── Parse OJP proxy JVM side-car CSVs (collected by collect_jvm_metrics.sh) ──
# Expected location: RESULTS_DIR/node_metrics/proxy/<host>_jvm_metrics.csv
# Columns: timestamp,heap_used_mb,heap_committed_mb,ygc_count,ygct_s,
#          fgc_count,fgct_s,gct_s

NODE_METRICS_DIR="${RESULTS_DIR}/node_metrics"
JVM_CSV_DIR="${NODE_METRICS_DIR}/proxy"

jvm_section=""
mapfile -t JVM_CSV_FILES < <(find "${JVM_CSV_DIR}" -name "*_jvm_metrics.csv" 2>/dev/null | sort)

if [[ ${#JVM_CSV_FILES[@]} -gt 0 ]]; then
  jvm_section+=$'\n## OJP Proxy — JVM Heap and GC Metrics\n\n'
  jvm_section+='> **Note:** Heap values are from `jstat -gc` (actual in-use JVM heap).'$'\n'
  jvm_section+='> OS RSS is **not** used here because the JVM does not return memory to the OS after GC.'$'\n\n'
  jvm_section+='| Proxy host | Heap used — median (MB) | Heap committed — median (MB) | Total GC time (s) | Young GC count | Full GC count |'$'\n'
  jvm_section+='|------------|------------------------|------------------------------|-------------------|----------------|---------------|'$'\n'

  for csv in "${JVM_CSV_FILES[@]}"; do
    host=$(basename "${csv}" _jvm_metrics.csv)

    # Median of heap_used_mb (col 2) — skip header, sort numerically, take middle
    heap_used_med=$(awk -F',' 'NR>1 && $2+0>0 {print $2+0}' "${csv}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%.1f", a[int((NR+1)/2)]}
          else {printf "%.1f", (a[NR/2]+a[NR/2+1])/2.0}
        }')

    # Median of heap_committed_mb (col 3)
    heap_comm_med=$(awk -F',' 'NR>1 && $3+0>0 {print $3+0}' "${csv}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%.1f", a[int((NR+1)/2)]}
          else {printf "%.1f", (a[NR/2]+a[NR/2+1])/2.0}
        }')

    # Final (last) row values for GC counters
    gct_s=$(awk -F',' 'NR>1 {gct=$8} END{printf "%.3f", gct+0}' "${csv}")
    ygc=$(awk  -F',' 'NR>1 {ygc=$4} END{printf "%d",    ygc+0}' "${csv}")
    fgc=$(awk  -F',' 'NR>1 {fgc=$6} END{printf "%d",    fgc+0}' "${csv}")

    [[ -z "${heap_used_med}" ]] && heap_used_med="N/A"
    [[ -z "${heap_comm_med}" ]] && heap_comm_med="N/A"

    jvm_section+="| ${host} | ${heap_used_med} | ${heap_comm_med} | ${gct_s} | ${ygc} | ${fgc} |"$'\n'
  done
fi

# ── Parse PostgreSQL side-car CSV (collected by collect_pg_metrics.sh) ────────
# Expected location: RESULTS_DIR/node_metrics/pg_metrics.csv
# Columns: timestamp,numbackends,xact_commit,xact_rollback,blks_hit,blks_read,
#          cache_hit_pct,temp_bytes,deadlocks,lock_waits,
#          buffers_checkpoint,checkpoint_write_ms

PG_CSV="${NODE_METRICS_DIR}/pg_metrics.csv"
pg_section=""

if [[ -f "${PG_CSV}" ]]; then
  # Use last data row for cumulative counters; median for instantaneous values
  pg_numbackends_med=$(awk -F',' 'NR>1 && $2+0>0 {print $2+0}' "${PG_CSV}" \
    | sort -n \
    | awk '{a[NR]=$0} END{
        if (NR==0) {printf "N/A"}
        else if (NR%2==1) {printf "%d", a[int((NR+1)/2)]}
        else {printf "%d", (a[NR/2]+a[NR/2+1])/2.0}
      }')

  pg_cache_hit_med=$(awk -F',' 'NR>1 && $7+0>0 {print $7+0}' "${PG_CSV}" \
    | sort -n \
    | awk '{a[NR]=$0} END{
        if (NR==0) {printf "N/A"}
        else if (NR%2==1) {printf "%.2f", a[int((NR+1)/2)]}
        else {printf "%.2f", (a[NR/2]+a[NR/2+1])/2.0}
      }')

  # Final row for cumulative counters (they grow monotonically)
  pg_xact_commit=$(awk   -F',' 'NR>1{v=$3}  END{printf "%d", v+0}' "${PG_CSV}")
  pg_xact_rb=$(awk       -F',' 'NR>1{v=$4}  END{printf "%d", v+0}' "${PG_CSV}")
  pg_temp_bytes=$(awk    -F',' 'NR>1{v=$8}  END{printf "%d", v+0}' "${PG_CSV}")
  pg_deadlocks=$(awk     -F',' 'NR>1{v=$9}  END{printf "%d", v+0}' "${PG_CSV}")
  pg_lock_waits_max=$(awk -F',' 'NR>1{if($10+0>max) max=$10+0} END{printf "%d", max+0}' "${PG_CSV}")
  pg_ckpt_bufs=$(awk     -F',' 'NR>1{v=$11} END{printf "%d", v+0}' "${PG_CSV}")
  pg_ckpt_ms=$(awk       -F',' 'NR>1{v=$12} END{printf "%.0f", v+0}' "${PG_CSV}")

  [[ -z "${pg_numbackends_med}" ]] && pg_numbackends_med="N/A"
  [[ -z "${pg_cache_hit_med}"   ]] && pg_cache_hit_med="N/A"

  pg_section=$'\n## PostgreSQL — Database Statistics\n\n'
  pg_section+='| Metric | Value | Notes |'$'\n'
  pg_section+='|--------|-------|-------|'$'\n'
  pg_section+="| Active backends (median) | ${pg_numbackends_med} | Connections visible in \`pg_stat_activity\` |"$'\n'
  pg_section+="| Buffer cache hit ratio (median) | ${pg_cache_hit_med} % | < 99 % on OLTP suggests insufficient \`shared_buffers\` |"$'\n'
  pg_section+="| Transactions committed | ${pg_xact_commit} | Cumulative since stats reset |"$'\n'
  pg_section+="| Transactions rolled back | ${pg_xact_rb} | Non-zero → contention or application errors |"$'\n'
  pg_section+="| Temp file bytes written | ${pg_temp_bytes} | Non-zero → sort/hash spills; tune \`work_mem\` |"$'\n'
  pg_section+="| Deadlocks | ${pg_deadlocks} | Should be 0 for OLTP workloads |"$'\n'
  pg_section+="| Peak ungranted lock waits | ${pg_lock_waits_max} | Instantaneous max; > 0 indicates hot-row contention |"$'\n'
  pg_section+="| Checkpoint buffers written | ${pg_ckpt_bufs} | High values → WAL/checkpoint I/O pressure |"$'\n'
  pg_section+="| Checkpoint write time (ms) | ${pg_ckpt_ms} | Cumulative; high → I/O-bound checkpoint |"$'\n'
fi

# ── SLO evaluation ────────────────────────────────────────────────────────────

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

## Bench JVM System Metrics (in-process, median across instances)

| Metric | Value | Source |
|--------|-------|--------|
| **Bench JVM CPU (median)** | ${agg_app_cpu} | \`OperatingSystemMXBean.getProcessCpuLoad()\` |
| **Bench JVM GC pause total** | ${agg_gc_ms} ms | \`GarbageCollectorMXBean.getCollectionTime()\` |

---

## SLO Evaluation

| SLO | Threshold | Result |
|-----|-----------|--------|
| p95 latency | < ${slo_p95_limit} ms | ${p95_pass} (${agg_p95} ms) |
| Error rate | < $(awk "BEGIN {printf \"%.1f%%\", ${slo_error_limit} * 100}") | ${error_pass} ($(awk "BEGIN {printf \"%.4f\", ${agg_error_rate}}")) |

---

## Per-Instance Breakdown

| Instance | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (RPS) | Error Rate | CPU (%) | GC pause (ms) |
|----------|----------|----------|----------|-----------------|------------|---------|---------------|
HEADER

for f in "${SUMMARY_FILES[@]}"; do
  inst=$(jq -r ".runInfo.instanceId // \"?\"" "${f}")
  p50=$(jq -r ".latencyMs.p50  // \"N/A\"" "${f}")
  p95=$(jq -r ".latencyMs.p95  // \"N/A\"" "${f}")
  p99=$(jq -r ".latencyMs.p99  // \"N/A\"" "${f}")
  rps=$(jq -r ".achievedThroughputRps // \"N/A\"" "${f}")
  err=$(jq -r ".errorRate // \"N/A\"" "${f}")
  cpu=$(jq -r ".appCpuMedian // \"N/A\"" "${f}")
  gc=$(jq -r  ".gcPauseMsTotal // \"N/A\"" "${f}")
  echo "| ${inst} | ${p50} | ${p95} | ${p99} | ${rps} | ${err} | ${cpu} | ${gc} |"
done

# OJP proxy JVM section (only present when side-car CSVs were fetched)
if [[ -n "${jvm_section}" ]]; then
  printf '%s' "---${jvm_section}"
fi

# PostgreSQL section (only present when side-car CSV was fetched)
if [[ -n "${pg_section}" ]]; then
  printf '%s' "---${pg_section}"
fi

cat <<FOOTER

---

*Generated by \`ansible/scripts/generate_report.sh\` on $(date -u '+%Y-%m-%dT%H:%M:%SZ')*
FOOTER
} > "${OUTPUT_FILE}"

echo "Report written to: ${OUTPUT_FILE}"

