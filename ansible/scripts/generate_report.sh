#!/usr/bin/env bash
# ansible/scripts/generate_report.sh
#
# Compile benchmark results into a Markdown report.
#
# Reads every summary.json found under RESULTS_DIR, extracts key metrics
# using jq, and writes a structured Markdown report to OUTPUT_FILE.
# Also parses side-car CSVs collected by collect_jvm_metrics.sh and
# collect_pg_metrics.sh when they are present under node_metrics/.
#
# Usage:
#   generate_report.sh <RESULTS_DIR> [OUTPUT_FILE] [SLO_P95_LIMIT_MS] [SLO_ERROR_LIMIT]
#
# Arguments:
#   RESULTS_DIR   Directory produced by a bench run (e.g. results/ojp-run-1).
#                 May contain multiple replica-N/ subdirectories and a
#                 node_metrics/ subdirectory with side-car CSVs.
#   OUTPUT_FILE   Path to the generated report (default: RESULTS_DIR/report.md).
#   SLO_P95_LIMIT_MS
#                 p95 latency SLO threshold in milliseconds (default: 50).
#   SLO_ERROR_LIMIT
#                 Error-rate SLO threshold as a fraction (default: 0.001 = 0.1%).
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
SLO_P95_LIMIT="${3:-50}"
SLO_ERROR_LIMIT="${4:-0.001}"

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
  (( ++instance_count ))
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
    (( ++cpu_count ))
  fi
  v=$(jq -r ".gcPauseMsTotal // empty" "${f}")
  if [[ -n "${v}" ]]; then
    gc_sum=$(awk "BEGIN {print ${gc_sum} + ${v}}")
    (( ++gc_count ))
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
# Columns: timestamp,numbackends,active_backends,idle_backends,
#          xact_commit,xact_rollback,blks_hit,blks_read,
#          cache_hit_pct,temp_bytes,deadlocks,lock_waits,
#          buffers_checkpoint,checkpoint_write_ms

PG_CSV="${NODE_METRICS_DIR}/pg_metrics.csv"
pg_section=""

observed_max_postgres_backends="N/A"
observed_avg_postgres_backends="N/A"
observed_median_postgres_backends="N/A"
observed_max_active_postgres_backends="N/A"
observed_median_active_postgres_backends="N/A"
observed_max_idle_postgres_backends="N/A"
observed_median_idle_postgres_backends="N/A"

if [[ -f "${PG_CSV}" ]]; then
  pg_col_idx() {
    local col_name="$1"
    awk -F',' -v want="${col_name}" 'NR==1{for(i=1;i<=NF;i++) if($i==want){print i; exit}}' "${PG_CSV}"
  }

  pg_col_numbackends="$(pg_col_idx "numbackends")"
  pg_col_active_backends="$(pg_col_idx "active_backends")"
  pg_col_idle_backends="$(pg_col_idx "idle_backends")"
  pg_col_cache_hit_pct="$(pg_col_idx "cache_hit_pct")"
  pg_col_xact_commit="$(pg_col_idx "xact_commit")"
  pg_col_xact_rollback="$(pg_col_idx "xact_rollback")"
  pg_col_temp_bytes="$(pg_col_idx "temp_bytes")"
  pg_col_deadlocks="$(pg_col_idx "deadlocks")"
  pg_col_lock_waits="$(pg_col_idx "lock_waits")"
  pg_col_buffers_checkpoint="$(pg_col_idx "buffers_checkpoint")"
  pg_col_checkpoint_write_ms="$(pg_col_idx "checkpoint_write_ms")"

  # Use last data row for cumulative counters; median for instantaneous values
  pg_numbackends_med="N/A"
  pg_active_backends_med="N/A"
  pg_idle_backends_med="N/A"
  pg_cache_hit_med="N/A"
  pg_xact_commit="N/A"
  pg_xact_rb="N/A"
  pg_temp_bytes="N/A"
  pg_deadlocks="N/A"
  pg_lock_waits_max="N/A"
  pg_ckpt_bufs="N/A"
  pg_ckpt_ms="N/A"

  if [[ -n "${pg_col_numbackends}" ]]; then
    pg_numbackends_med=$(awk -F',' -v c="${pg_col_numbackends}" 'NR>1 && $c+0>0 {print $c+0}' "${PG_CSV}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%d", a[int((NR+1)/2)]}
          else {printf "%d", (a[NR/2]+a[NR/2+1])/2.0}
        }')
    observed_max_postgres_backends=$(awk -F',' -v c="${pg_col_numbackends}" 'NR>1{if($c+0>max) max=$c+0} END{printf "%d", max+0}' "${PG_CSV}")
    observed_avg_postgres_backends=$(awk -F',' -v c="${pg_col_numbackends}" 'NR>1{sum+=$c+0; n++} END{if(n>0) printf "%.2f", sum/n; else printf "N/A"}' "${PG_CSV}")
    observed_median_postgres_backends="${pg_numbackends_med}"
  fi

  if [[ -n "${pg_col_active_backends}" ]]; then
    pg_active_backends_med=$(awk -F',' -v c="${pg_col_active_backends}" 'NR>1 && $c+0>=0 {print $c+0}' "${PG_CSV}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%d", a[int((NR+1)/2)]}
          else {printf "%d", (a[NR/2]+a[NR/2+1])/2.0}
        }')
    observed_max_active_postgres_backends=$(awk -F',' -v c="${pg_col_active_backends}" 'NR>1{if($c+0>max) max=$c+0} END{printf "%d", max+0}' "${PG_CSV}")
    observed_median_active_postgres_backends="${pg_active_backends_med}"
  fi

  if [[ -n "${pg_col_idle_backends}" ]]; then
    pg_idle_backends_med=$(awk -F',' -v c="${pg_col_idle_backends}" 'NR>1 && $c+0>=0 {print $c+0}' "${PG_CSV}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%d", a[int((NR+1)/2)]}
          else {printf "%d", (a[NR/2]+a[NR/2+1])/2.0}
        }')
    observed_max_idle_postgres_backends=$(awk -F',' -v c="${pg_col_idle_backends}" 'NR>1{if($c+0>max) max=$c+0} END{printf "%d", max+0}' "${PG_CSV}")
    observed_median_idle_postgres_backends="${pg_idle_backends_med}"
  fi

  if [[ -n "${pg_col_cache_hit_pct}" ]]; then
    pg_cache_hit_med=$(awk -F',' -v c="${pg_col_cache_hit_pct}" 'NR>1 && $c+0>0 {print $c+0}' "${PG_CSV}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%.2f", a[int((NR+1)/2)]}
          else {printf "%.2f", (a[NR/2]+a[NR/2+1])/2.0}
        }')
  fi

  [[ -n "${pg_col_xact_commit}" ]] && pg_xact_commit=$(awk -F',' -v c="${pg_col_xact_commit}" 'NR>1{v=$c} END{printf "%d", v+0}' "${PG_CSV}")
  [[ -n "${pg_col_xact_rollback}" ]] && pg_xact_rb=$(awk -F',' -v c="${pg_col_xact_rollback}" 'NR>1{v=$c} END{printf "%d", v+0}' "${PG_CSV}")
  [[ -n "${pg_col_temp_bytes}" ]] && pg_temp_bytes=$(awk -F',' -v c="${pg_col_temp_bytes}" 'NR>1{v=$c} END{printf "%d", v+0}' "${PG_CSV}")
  [[ -n "${pg_col_deadlocks}" ]] && pg_deadlocks=$(awk -F',' -v c="${pg_col_deadlocks}" 'NR>1{v=$c} END{printf "%d", v+0}' "${PG_CSV}")
  [[ -n "${pg_col_lock_waits}" ]] && pg_lock_waits_max=$(awk -F',' -v c="${pg_col_lock_waits}" 'NR>1{if($c+0>max) max=$c+0} END{printf "%d", max+0}' "${PG_CSV}")
  [[ -n "${pg_col_buffers_checkpoint}" ]] && pg_ckpt_bufs=$(awk -F',' -v c="${pg_col_buffers_checkpoint}" 'NR>1{v=$c} END{printf "%d", v+0}' "${PG_CSV}")
  [[ -n "${pg_col_checkpoint_write_ms}" ]] && pg_ckpt_ms=$(awk -F',' -v c="${pg_col_checkpoint_write_ms}" 'NR>1{v=$c} END{printf "%.0f", v+0}' "${PG_CSV}")

  [[ -z "${pg_numbackends_med}" ]] && pg_numbackends_med="N/A"
  [[ -z "${pg_active_backends_med}" ]] && pg_active_backends_med="N/A"
  [[ -z "${pg_idle_backends_med}" ]] && pg_idle_backends_med="N/A"
  [[ -z "${pg_cache_hit_med}"   ]] && pg_cache_hit_med="N/A"

  pg_section=$'\n## PostgreSQL — Database Statistics\n\n'
  pg_section+='| Metric | Value | Notes |'$'\n'
  pg_section+='|--------|-------|-------|'$'\n'
  pg_section+="| PostgreSQL backends (median, \`numbackends\`) | ${pg_numbackends_med} | Total backend connections from \`pg_stat_database\` |"$'\n'
  pg_section+="| Client backends in \`state='active'\` (median / max) | ${pg_active_backends_med} / ${observed_max_active_postgres_backends} | \`pg_stat_activity\` client backends only |"$'\n'
  pg_section+="| Client backends in \`state='idle'\` (median / max) | ${pg_idle_backends_med} / ${observed_max_idle_postgres_backends} | \`pg_stat_activity\` client backends only |"$'\n'
  pg_section+="| Buffer cache hit ratio (median) | ${pg_cache_hit_med} % | < 99 % on OLTP suggests insufficient \`shared_buffers\` |"$'\n'
  pg_section+="| Transactions committed | ${pg_xact_commit} | Cumulative since stats reset |"$'\n'
  pg_section+="| Transactions rolled back | ${pg_xact_rb} | Non-zero → contention or application errors |"$'\n'
  pg_section+="| Temp file bytes written | ${pg_temp_bytes} | Non-zero → sort/hash spills; tune \`work_mem\` |"$'\n'
  pg_section+="| Deadlocks | ${pg_deadlocks} | Should be 0 for OLTP workloads |"$'\n'
  pg_section+="| Peak ungranted lock waits | ${pg_lock_waits_max} | Instantaneous max; > 0 indicates hot-row contention |"$'\n'
  pg_section+="| Checkpoint buffers written | ${pg_ckpt_bufs} | High values → WAL/checkpoint I/O pressure |"$'\n'
  pg_section+="| Checkpoint write time (ms) | ${pg_ckpt_ms} | Cumulative; high → I/O-bound checkpoint |"$'\n'
fi

# ── Parse process metrics side-car CSVs ──────────────────────────────────────
# Expected locations:
#   RESULTS_DIR/node_metrics/proxy/<host>_proc_metrics.csv — proxy process
#       (OJP or pgBouncer, depending on the SUT)
#   RESULTS_DIR/node_metrics/db/<host>_proc_metrics.csv   — PostgreSQL
#   RESULTS_DIR/node_metrics/lb/<host>_proc_metrics.csv   — HAProxy (SUT-C only)
# Columns: timestamp,pid,cpu_pct,rss_mb,vsz_mb

proc_section=""
proc_rows=""
proxy_avg_cpu_sum=0
proxy_peak_cpu_sum=0
proxy_avg_rss_sum=0
proxy_peak_rss_sum=0
lb_avg_cpu_sum=0
lb_peak_cpu_sum=0
lb_avg_rss_sum=0
lb_peak_rss_sum=0
proxy_avg_cpu_count=0
proxy_avg_rss_count=0
lb_avg_cpu_count=0
lb_avg_rss_count=0

for subdir in proxy db lb; do
  mapfile -t PROC_CSV_FILES < <(find "${NODE_METRICS_DIR}/${subdir}" \
    -name "*_proc_metrics.csv" 2>/dev/null | sort)

  for csv in "${PROC_CSV_FILES[@]}"; do
    case "${subdir}" in
      proxy) component="Proxy (OJP / pgBouncer)" ;;
      db)    component="PostgreSQL" ;;
      lb)    component="HAProxy" ;;
    esac
    host=$(basename "${csv}" _proc_metrics.csv)

    # Peak and average CPU%
    peak_cpu=$(awk -F',' 'NR>1 && $3+0>0 {if($3+0>max) max=$3+0} END{printf "%.1f", max+0}' "${csv}")
    avg_cpu=$(awk -F',' 'NR>1 && $3+0>0 {sum+=$3+0; n++} END{
        if (n>0) printf "%.1f", sum/n; else printf "N/A"}' "${csv}")

    # Peak and average RSS (MiB)
    peak_rss=$(awk -F',' 'NR>1 && $4+0>0 {if($4+0>max) max=$4+0} END{printf "%.1f", max+0}' "${csv}")
    avg_rss=$(awk -F',' 'NR>1 && $4+0>0 {sum+=$4+0; n++} END{
        if (n>0) printf "%.1f", sum/n; else printf "N/A"}' "${csv}")

    proc_rows+="| ${component} | ${host} | ${avg_cpu} | ${peak_cpu} | ${avg_rss} | ${peak_rss} |"$'\n'

    if [[ "${subdir}" == "proxy" ]]; then
      if [[ "${avg_cpu}" != "N/A" ]]; then
        proxy_avg_cpu_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_avg_cpu_sum} + ${avg_cpu}}")
        (( ++proxy_avg_cpu_count ))
      fi
      proxy_peak_cpu_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_peak_cpu_sum} + ${peak_cpu}}")
      if [[ "${avg_rss}" != "N/A" ]]; then
        proxy_avg_rss_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_avg_rss_sum} + ${avg_rss}}")
        (( ++proxy_avg_rss_count ))
      fi
      proxy_peak_rss_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_peak_rss_sum} + ${peak_rss}}")
    elif [[ "${subdir}" == "lb" ]]; then
      if [[ "${avg_cpu}" != "N/A" ]]; then
        lb_avg_cpu_sum=$(awk "BEGIN {printf \"%.2f\", ${lb_avg_cpu_sum} + ${avg_cpu}}")
        (( ++lb_avg_cpu_count ))
      fi
      lb_peak_cpu_sum=$(awk "BEGIN {printf \"%.2f\", ${lb_peak_cpu_sum} + ${peak_cpu}}")
      if [[ "${avg_rss}" != "N/A" ]]; then
        lb_avg_rss_sum=$(awk "BEGIN {printf \"%.2f\", ${lb_avg_rss_sum} + ${avg_rss}}")
        (( ++lb_avg_rss_count ))
      fi
      lb_peak_rss_sum=$(awk "BEGIN {printf \"%.2f\", ${lb_peak_rss_sum} + ${peak_rss}}")
    fi
  done
done

if [[ -n "${proc_rows}" ]]; then
  proc_section=$'\n## Process Resource Utilization\n\n'
  proc_section+='> CPU% is normalised to a single core (100% = 1 CPU fully busy).'$'\n'
  proc_section+='> Memory values are Resident Set Size (RSS) — physical RAM in use.'$'\n\n'
  proc_section+='| Component | Node | Avg CPU (%) | Peak CPU (%) | Avg RSS (MiB) | Peak RSS (MiB) |'$'\n'
  proc_section+='|-----------|------|-------------|--------------|---------------|----------------|'$'\n'
  proc_section+="${proc_rows}"
fi


p95_pass=$(awk "BEGIN {print (${agg_p95} < ${SLO_P95_LIMIT}) ? \"✅ PASS\" : \"❌ FAIL\"}")
error_pass=$(awk "BEGIN {print (${agg_error_rate} < ${SLO_ERROR_LIMIT}) ? \"✅ PASS\" : \"❌ FAIL\"}")

if [[ ${proxy_avg_cpu_count} -gt 0 ]]; then
  ojp_proxy_tier_avg_cpu="${proxy_avg_cpu_sum}"
else
  ojp_proxy_tier_avg_cpu="N/A"
fi
ojp_proxy_tier_peak_cpu="${proxy_peak_cpu_sum}"
if [[ ${proxy_avg_rss_count} -gt 0 ]]; then
  ojp_proxy_tier_avg_rss="${proxy_avg_rss_sum}"
else
  ojp_proxy_tier_avg_rss="N/A"
fi
ojp_proxy_tier_peak_rss="${proxy_peak_rss_sum}"
proxy_avg_cpu_display="${proxy_avg_cpu_sum}"
proxy_avg_rss_display="${proxy_avg_rss_sum}"
lb_avg_cpu_display="${lb_avg_cpu_sum}"
lb_avg_rss_display="${lb_avg_rss_sum}"
if [[ ${proxy_avg_cpu_count} -eq 0 ]]; then proxy_avg_cpu_display="N/A"; fi
if [[ ${proxy_avg_rss_count} -eq 0 ]]; then proxy_avg_rss_display="N/A"; fi
if [[ ${lb_avg_cpu_count} -eq 0 ]]; then lb_avg_cpu_display="N/A"; fi
if [[ ${lb_avg_rss_count} -eq 0 ]]; then lb_avg_rss_display="N/A"; fi
if [[ ${proxy_avg_cpu_count} -eq 0 && ${lb_avg_cpu_count} -eq 0 ]]; then
  pgb_proxy_tier_avg_cpu="N/A"
else
  proxy_avg_cpu_for_total="${proxy_avg_cpu_sum}"
  lb_avg_cpu_for_total="${lb_avg_cpu_sum}"
  if [[ ${proxy_avg_cpu_count} -eq 0 ]]; then proxy_avg_cpu_for_total=0; fi
  if [[ ${lb_avg_cpu_count} -eq 0 ]]; then lb_avg_cpu_for_total=0; fi
  pgb_proxy_tier_avg_cpu=$(awk "BEGIN {printf \"%.2f\", ${proxy_avg_cpu_for_total} + ${lb_avg_cpu_for_total}}")
fi
pgb_proxy_tier_peak_cpu=$(awk "BEGIN {printf \"%.2f\", ${proxy_peak_cpu_sum} + ${lb_peak_cpu_sum}}")
if [[ ${proxy_avg_rss_count} -eq 0 && ${lb_avg_rss_count} -eq 0 ]]; then
  pgb_proxy_tier_avg_rss="N/A"
else
  proxy_avg_rss_for_total="${proxy_avg_rss_sum}"
  lb_avg_rss_for_total="${lb_avg_rss_sum}"
  if [[ ${proxy_avg_rss_count} -eq 0 ]]; then proxy_avg_rss_for_total=0; fi
  if [[ ${lb_avg_rss_count} -eq 0 ]]; then lb_avg_rss_for_total=0; fi
  pgb_proxy_tier_avg_rss=$(awk "BEGIN {printf \"%.2f\", ${proxy_avg_rss_for_total} + ${lb_avg_rss_for_total}}")
fi
pgb_proxy_tier_peak_rss=$(awk "BEGIN {printf \"%.2f\", ${proxy_peak_rss_sum} + ${lb_peak_rss_sum}}")

# ── Read run metadata from first summary ──────────────────────────────────────

first="${SUMMARY_FILES[0]}"
run_sut=$(jq_field "${first}" ".runInfo.sut")
run_workload=$(jq_field "${first}" ".runInfo.workload")
run_ts=$(jq_field "${first}" ".runInfo.timestamp")
run_duration=$(jq_field "${first}" ".runInfo.durationSeconds")
run_target_rps=$(jq_field "${first}" ".runInfo.targetRps")
configured_db_connection_budget=$(jq_field "${first}" ".runInfo.configuredDbConnectionBudget")
configured_replicas=$(jq_field "${first}" ".runInfo.configuredReplicas")
configured_pool_size=$(jq_field "${first}" ".runInfo.poolSize")

RUN_METADATA_FILE="${RESULTS_DIR}/run_metadata.json"
metadata_scenario="N/A"
metadata_pgbouncer_nodes="N/A"
metadata_pgbouncer_pool_size="N/A"
metadata_pgbouncer_reserve_pool_size="N/A"
metadata_pgbouncer_local_pool_size="N/A"
metadata_haproxy_nodes="N/A"
metadata_ojp_servers="N/A"
metadata_ojp_real_db_per_server="N/A"
if [[ -f "${RUN_METADATA_FILE}" ]]; then
  metadata_scenario=$(jq_field "${RUN_METADATA_FILE}" ".scenario")
  metadata_pgbouncer_nodes=$(jq_field "${RUN_METADATA_FILE}" ".pgbouncer_nodes")
  metadata_pgbouncer_pool_size=$(jq_field "${RUN_METADATA_FILE}" ".pgbouncer_pool_size_per_node")
  metadata_pgbouncer_reserve_pool_size=$(jq_field "${RUN_METADATA_FILE}" ".pgbouncer_reserve_pool_size")
  metadata_pgbouncer_local_pool_size=$(jq_field "${RUN_METADATA_FILE}" ".pgbouncer_local_pool_size")
  metadata_haproxy_nodes=$(jq_field "${RUN_METADATA_FILE}" ".haproxy_nodes")
  metadata_ojp_servers=$(jq_field "${RUN_METADATA_FILE}" ".ojp_servers")
  metadata_ojp_real_db_per_server=$(jq_field "${RUN_METADATA_FILE}" ".real_db_connections_per_ojp_server")
  configured_db_connection_budget=$(jq_field "${RUN_METADATA_FILE}" ".configured_db_connection_budget")
fi

# ── Write report ──────────────────────────────────────────────────────────────

mkdir -p "$(dirname "${OUTPUT_FILE}")"

{
cat <<HEADER
# Benchmark Report

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

## Connection Budget — Configured and Observed

| Field | Value |
|-------|-------|
| configured_db_connection_budget | ${configured_db_connection_budget} |
| observed_postgres_backends_max_numbackends | ${observed_max_postgres_backends} |
| observed_postgres_backends_avg_numbackends | ${observed_avg_postgres_backends} |
| observed_postgres_backends_median_numbackends | ${observed_median_postgres_backends} |
| observed_client_backends_active_median | ${observed_median_active_postgres_backends} |
| observed_client_backends_active_max | ${observed_max_active_postgres_backends} |
| observed_client_backends_idle_median | ${observed_median_idle_postgres_backends} |
| observed_client_backends_idle_max | ${observed_max_idle_postgres_backends} |

## Topology-Specific Summary

| Field | Value |
|-------|-------|
| Scenario profile | ${metadata_scenario} |
| Configured replicas | ${configured_replicas} |
| Configured client pool size (per replica) | ${configured_pool_size} |
| OJP servers | ${metadata_ojp_servers} |
| Real DB connections per OJP server | ${metadata_ojp_real_db_per_server} |
| OJP proxy-tier CPU (avg / peak, summed) | ${ojp_proxy_tier_avg_cpu}% / ${ojp_proxy_tier_peak_cpu}% |
| OJP proxy-tier RSS (avg / peak, summed) | ${ojp_proxy_tier_avg_rss} MiB / ${ojp_proxy_tier_peak_rss} MiB |
| PgBouncer nodes | ${metadata_pgbouncer_nodes} |
| PgBouncer server pool size per node | ${metadata_pgbouncer_pool_size} |
| pgbouncer_reserve_pool_size | ${metadata_pgbouncer_reserve_pool_size} |
| PgBouncer local HikariCP pool size per replica | ${metadata_pgbouncer_local_pool_size} |
| HAProxy nodes | ${metadata_haproxy_nodes} |
| PgBouncer tier CPU (avg / peak, summed) | ${proxy_avg_cpu_display}% / ${proxy_peak_cpu_sum}% |
| PgBouncer tier RSS (avg / peak, summed) | ${proxy_avg_rss_display} MiB / ${proxy_peak_rss_sum} MiB |
| HAProxy CPU (avg / peak, summed) | ${lb_avg_cpu_display}% / ${lb_peak_cpu_sum}% |
| HAProxy RSS (avg / peak, summed) | ${lb_avg_rss_display} MiB / ${lb_peak_rss_sum} MiB |
| Total PgBouncer proxy-tier CPU (avg / peak) | ${pgb_proxy_tier_avg_cpu}% / ${pgb_proxy_tier_peak_cpu}% |
| Total PgBouncer proxy-tier RSS (avg / peak) | ${pgb_proxy_tier_avg_rss} MiB / ${pgb_proxy_tier_peak_rss} MiB |

## Bench JVM System Metrics (in-process, median across instances)

| Metric | Value | Source |
|--------|-------|--------|
| **Bench JVM CPU (median)** | ${agg_app_cpu} | \`OperatingSystemMXBean.getProcessCpuLoad()\` |
| **Bench JVM GC pause total** | ${agg_gc_ms} ms | \`GarbageCollectorMXBean.getCollectionTime()\` |

---

## SLO Evaluation

| SLO | Threshold | Result |
|-----|-----------|--------|
| p95 latency | < ${SLO_P95_LIMIT} ms | ${p95_pass} (${agg_p95} ms) |
| Error rate | < $(awk "BEGIN {printf \"%.1f%%\", ${SLO_ERROR_LIMIT} * 100}") | ${error_pass} ($(awk "BEGIN {printf \"%.4f\", ${agg_error_rate}}")) |

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

# ── Error breakdown section (only when errors occurred) ───────────────────────

if awk "BEGIN {exit !(${total_failed_requests} > 0)}"; then
  printf '\n---\n\n## Error Breakdown\n\n'
  printf '| Instance | Error type | Count | First error message |\n'
  printf '|----------|------------|-------|---------------------|\n'
  for f in "${SUMMARY_FILES[@]}"; do
    inst=$(jq -r ".runInfo.instanceId // \"?\"" "${f}")
    # Emit one row per error type with first sample error message
    jq -r --arg inst "${inst}" '. as $root | .errorsByType // {} | to_entries[] | "| \($inst) | \(.key) | \(.value) | \($root.firstErrorMessageByType[.key] // "—") |"' "${f}"
  done
fi
if [[ -n "${jvm_section}" ]]; then
  printf '%s' "---${jvm_section}"
fi

# PostgreSQL section (only present when side-car CSV was fetched)
if [[ -n "${pg_section}" ]]; then
  printf '%s' "---${pg_section}"
fi

# Process resource utilization (present when process metrics CSVs were fetched)
if [[ -n "${proc_section}" ]]; then
  printf '%s' "---${proc_section}"
fi

cat <<FOOTER

---

*Generated by \`ansible/scripts/generate_report.sh\` on $(date -u '+%Y-%m-%dT%H:%M:%SZ')*
FOOTER
} > "${OUTPUT_FILE}"

echo "Report written to: ${OUTPUT_FILE}"
