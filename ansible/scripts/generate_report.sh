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

csv_col_idx() {
  local csv="$1" col_name="$2"
  awk -F',' -v want="${col_name}" 'NR==1{for(i=1;i<=NF;i++) if($i==want){print i; exit}}' "${csv}"
}

csv_values_stream() {
  local csv="$1" col_idx="$2" start_iso="$3" end_iso="$4"
  awk -F',' -v c="${col_idx}" -v s="${start_iso}" -v e="${end_iso}" '
    NR>1 && c>0 {
      ts=$1
      if ((s=="" || ts>=s) && (e=="" || ts<=e) && $c+0>=0) print $c+0
    }
  ' "${csv}"
}

stats_from_sorted_stream() {
  awk '
    {a[++n]=$1; sum+=$1; if(n==1 || $1>max) max=$1}
    END{
      if(n==0){print "N/A|N/A|N/A|N/A|N/A|0"; exit}
      p50i=int(0.50*n + 0.999999); if(p50i<1)p50i=1; if(p50i>n)p50i=n
      p95i=int(0.95*n + 0.999999); if(p95i<1)p95i=1; if(p95i>n)p95i=n
      p99i=int(0.99*n + 0.999999); if(p99i<1)p99i=1; if(p99i>n)p99i=n
      printf "%.1f|%.1f|%.1f|%.1f|%.1f|%d\n", sum/n, a[p50i], a[p95i], a[p99i], max, n
    }
  '
}

csv_column_stats() {
  local csv="$1" col_name="$2" start_iso="$3" end_iso="$4"
  local col_idx
  col_idx="$(csv_col_idx "${csv}" "${col_name}")"
  if [[ -z "${col_idx}" ]]; then
    echo "N/A|N/A|N/A|N/A|N/A|0"
    return
  fi
  csv_values_stream "${csv}" "${col_idx}" "${start_iso}" "${end_iso}" | sort -n | stats_from_sorted_stream
}

aligned_sum_stats() {
  local col_name="$1" start_iso="$2" end_iso="$3"
  shift 3
  local files=("$@")
  local contributing_files=0
  local line_stream=""
  local csv col_idx

  for csv in "${files[@]}"; do
    col_idx="$(csv_col_idx "${csv}" "${col_name}")"
    if [[ -z "${col_idx}" ]]; then
      continue
    fi
    (( contributing_files += 1 ))
    line_stream+=$'\n'
    line_stream+="$(awk -F',' -v c="${col_idx}" -v s="${start_iso}" -v e="${end_iso}" '
      NR>1 {
        ts=$1
        if ((s=="" || ts>=s) && (e=="" || ts<=e) && $c+0>=0) print ts "|" ($c+0)
      }
    ' "${csv}")"
  done

  if [[ ${contributing_files} -eq 0 ]]; then
    echo "N/A|N/A|N/A|N/A|N/A|0|0"
    return
  fi

  local summed_series
  summed_series="$(
    printf '%s\n' "${line_stream}" \
      | awk -F'|' 'NF==2{sum[$1]+=$2} END{for (ts in sum) print sum[ts]}' \
      | sort -n
  )"

  if [[ -z "${summed_series}" ]]; then
    echo "N/A|N/A|N/A|N/A|N/A|0|${contributing_files}"
    return
  fi

  echo "${summed_series}" | stats_from_sorted_stream | awk -v n="${contributing_files}" -F'|' '{print $0 "|" n}'
}

# ── Aggregate bench-client metrics across all instances ──────────────────────

total_achieved_rps=0
total_attempted_rps=0
total_p50=0; total_p95=0; total_p99=0; total_p999=0
total_error_rate=0
total_total_requests=0
total_failed_requests=0
total_open_loop_attempted_ops=0
total_open_loop_missed_opportunities=0
total_open_loop_scheduling_delay_ms=0
open_loop_instance_count=0
instance_count=0

for f in "${SUMMARY_FILES[@]}"; do
  total_achieved_rps=$(jq -r ".achievedThroughputRps // 0" "${f}" | awk -v acc="${total_achieved_rps}" '{print acc + $1}')
  total_attempted_rps=$(jq -r ".attemptedRps // 0" "${f}" | awk -v acc="${total_attempted_rps}" '{print acc + $1}')
  total_p50=$(jq -r ".latencyMs.p50  // 0" "${f}" | awk -v acc="${total_p50}"  '{print acc + $1}')
  total_p95=$(jq -r ".latencyMs.p95  // 0" "${f}" | awk -v acc="${total_p95}"  '{print acc + $1}')
  total_p99=$(jq -r ".latencyMs.p99  // 0" "${f}" | awk -v acc="${total_p99}"  '{print acc + $1}')
  total_p999=$(jq -r ".latencyMs.p999 // 0" "${f}" | awk -v acc="${total_p999}" '{print acc + $1}')
  total_error_rate=$(jq -r ".errorRate // 0" "${f}" | awk -v acc="${total_error_rate}" '{print acc + $1}')
  total_total_requests=$(jq -r ".totalRequests // 0"  "${f}" | awk -v acc="${total_total_requests}"  '{print acc + $1}')
  total_failed_requests=$(jq -r ".failedRequests // 0" "${f}" | awk -v acc="${total_failed_requests}" '{print acc + $1}')
  ol_attempted=$(jq -r ".runInfo.openLoopAttemptedOps // empty" "${f}")
  if [[ -n "${ol_attempted}" ]]; then
    total_open_loop_attempted_ops=$(awk -v acc="${total_open_loop_attempted_ops}" -v v="${ol_attempted}" 'BEGIN {print acc + v}')
    ol_missed=$(jq -r ".runInfo.openLoopMissedOpportunities // 0" "${f}")
    total_open_loop_missed_opportunities=$(awk -v acc="${total_open_loop_missed_opportunities}" -v v="${ol_missed}" 'BEGIN {print acc + v}')
    ol_delay=$(jq -r ".runInfo.openLoopSchedulingDelayMs // 0" "${f}")
    total_open_loop_scheduling_delay_ms=$(awk -v acc="${total_open_loop_scheduling_delay_ms}" -v v="${ol_delay}" 'BEGIN {print acc + v}')
    (( ++open_loop_instance_count ))
  fi
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
total_attempted_agg_rps=$(awk "BEGIN {printf \"%.2f\", ${total_attempted_rps}}")

# ── Open-loop sanity metrics (only when at least one instance ran open-loop) ──
# These prove the open-loop dispatcher actually stayed open: a large gap between
# attempted and achieved RPS, missed opportunities, or non-trivial scheduling
# delay all indicate the worker pool was too small for the SUT's latency and the
# load was effectively closed-loop. See workload.openLoopMaxConcurrency to tune.
open_loop_section=""
if (( open_loop_instance_count > 0 )); then
  ol_attempted_total=$(awk "BEGIN {printf \"%.0f\", ${total_open_loop_attempted_ops}}")
  ol_missed_total=$(awk "BEGIN {printf \"%.0f\", ${total_open_loop_missed_opportunities}}")
  ol_delay_total=$(awk "BEGIN {printf \"%.2f\", ${total_open_loop_scheduling_delay_ms}}")
  ol_throughput_gap_rps=$(awk "BEGIN {printf \"%.2f\", ${total_attempted_rps} - ${total_achieved_rps}}")
  ol_throughput_gap_pct="N/A"
  if awk "BEGIN {exit !(${total_attempted_rps} > 0)}"; then
    ol_throughput_gap_pct=$(awk "BEGIN {printf \"%.2f%%\", (${total_attempted_rps} - ${total_achieved_rps}) / ${total_attempted_rps} * 100}")
  fi
  open_loop_section=$(cat <<OL

## Open-Loop Sanity (${open_loop_instance_count}/${instance_count} instance(s))

> A large attempted-vs-achieved gap, non-zero missed opportunities, or high
> total scheduling delay indicate the open-loop dispatcher backlogged behind a
> too-small worker pool and the run effectively degraded to closed-loop. Tune
> via \`workload.openLoopMaxConcurrency\` if auto-sizing is insufficient.

| Metric | Value |
|--------|-------|
| **Attempted throughput** | ${total_attempted_agg_rps} RPS (all instances) |
| **Achieved throughput** | ${total_agg_rps} RPS (all instances) |
| **Attempted − achieved gap** | ${ol_throughput_gap_rps} RPS (${ol_throughput_gap_pct}) |
| **Total attempted ops** | ${ol_attempted_total} |
| **Missed opportunities** | ${ol_missed_total} |
| **Total scheduling delay** | ${ol_delay_total} ms |
OL
)
fi

first="${SUMMARY_FILES[0]}"
run_ts=$(jq_field "${first}" ".runInfo.timestamp")
run_duration=$(jq_field "${first}" ".runInfo.durationSeconds")
steady_state_start_iso=""
steady_state_end_iso=""
if [[ "${run_ts}" != "N/A" && "${run_duration}" =~ ^[0-9]+$ ]]; then
  if steady_state_start_iso="$(date -u -d "${run_ts}" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"; then
    steady_state_end_iso="$(date -u -d "${run_ts} + ${run_duration} seconds" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || true)"
  fi
fi

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
  if [[ -n "${steady_state_start_iso}" && -n "${steady_state_end_iso}" ]]; then
    jvm_section+="> Stats are restricted to steady-state window: ${steady_state_start_iso} → ${steady_state_end_iso}."$'\n\n'
  fi
  jvm_section+='| Proxy host | Heap used — median (MB) | Heap committed — median (MB) | Total GC time (s) | Young GC count | Full GC count |'$'\n'
  jvm_section+='|------------|------------------------|------------------------------|-------------------|----------------|---------------|'$'\n'

  for csv in "${JVM_CSV_FILES[@]}"; do
    host=$(basename "${csv}" _jvm_metrics.csv)

    # Median of heap_used_mb (col 2) — skip header, sort numerically, take middle
    heap_used_med=$(awk -F',' -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
        NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) && $2+0>0 {print $2+0}
      ' "${csv}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%.1f", a[int((NR+1)/2)]}
          else {printf "%.1f", (a[NR/2]+a[NR/2+1])/2.0}
        }')

    # Median of heap_committed_mb (col 3)
    heap_comm_med=$(awk -F',' -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
        NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) && $3+0>0 {print $3+0}
      ' "${csv}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%.1f", a[int((NR+1)/2)]}
          else {printf "%.1f", (a[NR/2]+a[NR/2+1])/2.0}
        }')

    # Window deltas for cumulative GC counters
    gct_s=$(awk -F',' -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {
        if (!seen) {first=$8+0; seen=1}
        last=$8+0
      }
      END{
        if (seen) printf "%.3f", (last-first);
        else printf "0.000"
      }' "${csv}")
    ygc=$(awk  -F',' -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {
        if (!seen) {first=$4+0; seen=1}
        last=$4+0
      }
      END{
        if (seen) printf "%d", (last-first);
        else printf "0"
      }' "${csv}")
    fgc=$(awk  -F',' -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {
        if (!seen) {first=$6+0; seen=1}
        last=$6+0
      }
      END{
        if (seen) printf "%d", (last-first);
        else printf "0"
      }' "${csv}")

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
    pg_numbackends_med=$(awk -F',' -v c="${pg_col_numbackends}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) && $c+0>=0 {print $c+0}
    ' "${PG_CSV}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%d", a[int((NR+1)/2)]}
          else {printf "%d", (a[NR/2]+a[NR/2+1])/2.0}
        }')
    observed_max_postgres_backends=$(awk -F',' -v c="${pg_col_numbackends}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if($c+0>max) max=$c+0}
      END{printf "%d", max+0}
    ' "${PG_CSV}")
    observed_avg_postgres_backends=$(awk -F',' -v c="${pg_col_numbackends}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {sum+=$c+0; n++}
      END{if(n>0) printf "%.2f", sum/n; else printf "N/A"}
    ' "${PG_CSV}")
    observed_median_postgres_backends="${pg_numbackends_med}"
  fi

  if [[ -n "${pg_col_active_backends}" ]]; then
    pg_active_backends_med=$(awk -F',' -v c="${pg_col_active_backends}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) && $c+0>=0 {print $c+0}
    ' "${PG_CSV}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%d", a[int((NR+1)/2)]}
          else {printf "%d", (a[NR/2]+a[NR/2+1])/2.0}
        }')
    observed_max_active_postgres_backends=$(awk -F',' -v c="${pg_col_active_backends}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if($c+0>max) max=$c+0}
      END{printf "%d", max+0}
    ' "${PG_CSV}")
    observed_median_active_postgres_backends="${pg_active_backends_med}"
  fi

  if [[ -n "${pg_col_idle_backends}" ]]; then
    pg_idle_backends_med=$(awk -F',' -v c="${pg_col_idle_backends}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) && $c+0>=0 {print $c+0}
    ' "${PG_CSV}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%d", a[int((NR+1)/2)]}
          else {printf "%d", (a[NR/2]+a[NR/2+1])/2.0}
        }')
    observed_max_idle_postgres_backends=$(awk -F',' -v c="${pg_col_idle_backends}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if($c+0>max) max=$c+0}
      END{printf "%d", max+0}
    ' "${PG_CSV}")
    observed_median_idle_postgres_backends="${pg_idle_backends_med}"
  fi

  if [[ -n "${pg_col_cache_hit_pct}" ]]; then
    pg_cache_hit_med=$(awk -F',' -v c="${pg_col_cache_hit_pct}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
      NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) && $c+0>0 {print $c+0}
    ' "${PG_CSV}" \
      | sort -n \
      | awk '{a[NR]=$0} END{
          if (NR==0) {printf "N/A"}
          else if (NR%2==1) {printf "%.2f", a[int((NR+1)/2)]}
          else {printf "%.2f", (a[NR/2]+a[NR/2+1])/2.0}
        }')
  fi

  [[ -n "${pg_col_xact_commit}" ]] && pg_xact_commit=$(awk -F',' -v c="${pg_col_xact_commit}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
    NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if(!seen){first=$c+0; seen=1} last=$c+0}
    END{if(seen) printf "%d", (last-first); else printf "N/A"}
  ' "${PG_CSV}")
  [[ -n "${pg_col_xact_rollback}" ]] && pg_xact_rb=$(awk -F',' -v c="${pg_col_xact_rollback}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
    NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if(!seen){first=$c+0; seen=1} last=$c+0}
    END{if(seen) printf "%d", (last-first); else printf "N/A"}
  ' "${PG_CSV}")
  [[ -n "${pg_col_temp_bytes}" ]] && pg_temp_bytes=$(awk -F',' -v c="${pg_col_temp_bytes}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
    NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if(!seen){first=$c+0; seen=1} last=$c+0}
    END{if(seen) printf "%d", (last-first); else printf "N/A"}
  ' "${PG_CSV}")
  [[ -n "${pg_col_deadlocks}" ]] && pg_deadlocks=$(awk -F',' -v c="${pg_col_deadlocks}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
    NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if(!seen){first=$c+0; seen=1} last=$c+0}
    END{if(seen) printf "%d", (last-first); else printf "N/A"}
  ' "${PG_CSV}")
  [[ -n "${pg_col_lock_waits}" ]] && pg_lock_waits_max=$(awk -F',' -v c="${pg_col_lock_waits}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
    NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if($c+0>max) max=$c+0}
    END{printf "%d", max+0}
  ' "${PG_CSV}")
  [[ -n "${pg_col_buffers_checkpoint}" ]] && pg_ckpt_bufs=$(awk -F',' -v c="${pg_col_buffers_checkpoint}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
    NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if(!seen){first=$c+0; seen=1} last=$c+0}
    END{if(seen) printf "%d", (last-first); else printf "N/A"}
  ' "${PG_CSV}")
  [[ -n "${pg_col_checkpoint_write_ms}" ]] && pg_ckpt_ms=$(awk -F',' -v c="${pg_col_checkpoint_write_ms}" -v s="${steady_state_start_iso}" -v e="${steady_state_end_iso}" '
    NR>1 && (s=="" || $1>=s) && (e=="" || $1<=e) {if(!seen){first=$c+0; seen=1} last=$c+0}
    END{if(seen) printf "%.0f", (last-first); else printf "N/A"}
  ' "${PG_CSV}")

  [[ -z "${pg_numbackends_med}" ]] && pg_numbackends_med="N/A"
  [[ -z "${pg_active_backends_med}" ]] && pg_active_backends_med="N/A"
  [[ -z "${pg_idle_backends_med}" ]] && pg_idle_backends_med="N/A"
  [[ -z "${pg_cache_hit_med}"   ]] && pg_cache_hit_med="N/A"

  pg_section=$'\n## PostgreSQL — Database Statistics\n\n'
  if [[ -n "${steady_state_start_iso}" && -n "${steady_state_end_iso}" ]]; then
    pg_section+="> Stats are restricted to steady-state window: ${steady_state_start_iso} → ${steady_state_end_iso}."$'\n\n'
  fi
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
# Columns: timestamp,pid,cpu_pct,host_cpu_pct,rss_mb,vsz_mb

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
proxy_files_count=0
lb_files_count=0
db_files_count=0
missing_host_cpu_column_files=0
declare -a proxy_csv_files=()
declare -a lb_csv_files=()
declare -a db_csv_files=()

for subdir in proxy db lb; do
  mapfile -t PROC_CSV_FILES < <(find "${NODE_METRICS_DIR}/${subdir}" \
    -name "*_proc_metrics.csv" 2>/dev/null | sort)

  for csv in "${PROC_CSV_FILES[@]}"; do
    case "${subdir}" in
      proxy) (( proxy_files_count += 1 )); proxy_csv_files+=("${csv}") ;;
      db)    (( db_files_count += 1 ));    db_csv_files+=("${csv}") ;;
      lb)    (( lb_files_count += 1 ));    lb_csv_files+=("${csv}") ;;
    esac

    case "${subdir}" in
      proxy) component="Proxy (OJP / pgBouncer)" ;;
      db)    component="PostgreSQL" ;;
      lb)    component="HAProxy" ;;
    esac
    host=$(basename "${csv}" _proc_metrics.csv)

    # Service CPU stats (%): avg, p50, p95, p99, peak
    service_cpu_stats="$(csv_column_stats "${csv}" "cpu_pct" "${steady_state_start_iso}" "${steady_state_end_iso}")"
    IFS='|' read -r avg_cpu p50_cpu p95_cpu p99_cpu peak_cpu _ <<< "${service_cpu_stats}"

    # Host CPU stats (%): avg, p50, p95, p99, peak (N/A for backward-compat CSVs)
    if [[ -z "$(csv_col_idx "${csv}" "host_cpu_pct")" ]]; then
      (( missing_host_cpu_column_files += 1 ))
    fi
    host_cpu_stats="$(csv_column_stats "${csv}" "host_cpu_pct" "${steady_state_start_iso}" "${steady_state_end_iso}")"
    IFS='|' read -r avg_host_cpu p50_host_cpu p95_host_cpu p99_host_cpu peak_host_cpu _ <<< "${host_cpu_stats}"

    # RSS stats (MiB): avg, peak
    rss_stats="$(csv_column_stats "${csv}" "rss_mb" "${steady_state_start_iso}" "${steady_state_end_iso}")"
    IFS='|' read -r avg_rss _ _ _ peak_rss _ <<< "${rss_stats}"

    proc_rows+="| ${component} | ${host} | ${avg_cpu} | ${p50_cpu} | ${p95_cpu} | ${p99_cpu} | ${peak_cpu} | ${avg_host_cpu} | ${p50_host_cpu} | ${p95_host_cpu} | ${p99_host_cpu} | ${peak_host_cpu} | ${avg_rss} | ${peak_rss} |"$'\n'

    if [[ "${subdir}" == "proxy" ]]; then
      if [[ "${avg_cpu}" != "N/A" ]]; then
        proxy_avg_cpu_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_avg_cpu_sum} + ${avg_cpu}}")
        (( ++proxy_avg_cpu_count ))
      fi
      if [[ "${peak_cpu}" != "N/A" ]]; then
        proxy_peak_cpu_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_peak_cpu_sum} + ${peak_cpu}}")
      fi
      if [[ "${avg_rss}" != "N/A" ]]; then
        proxy_avg_rss_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_avg_rss_sum} + ${avg_rss}}")
        (( ++proxy_avg_rss_count ))
      fi
      if [[ "${peak_rss}" != "N/A" ]]; then
        proxy_peak_rss_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_peak_rss_sum} + ${peak_rss}}")
      fi
    elif [[ "${subdir}" == "lb" ]]; then
      if [[ "${avg_cpu}" != "N/A" ]]; then
        lb_avg_cpu_sum=$(awk "BEGIN {printf \"%.2f\", ${lb_avg_cpu_sum} + ${avg_cpu}}")
        (( ++lb_avg_cpu_count ))
      fi
      if [[ "${peak_cpu}" != "N/A" ]]; then
        lb_peak_cpu_sum=$(awk "BEGIN {printf \"%.2f\", ${lb_peak_cpu_sum} + ${peak_cpu}}")
      fi
      if [[ "${avg_rss}" != "N/A" ]]; then
        lb_avg_rss_sum=$(awk "BEGIN {printf \"%.2f\", ${lb_avg_rss_sum} + ${avg_rss}}")
        (( ++lb_avg_rss_count ))
      fi
      if [[ "${peak_rss}" != "N/A" ]]; then
        lb_peak_rss_sum=$(awk "BEGIN {printf \"%.2f\", ${lb_peak_rss_sum} + ${peak_rss}}")
      fi
    fi
  done
done

if [[ -n "${proc_rows}" ]]; then
  proc_section=$'\n## Process Resource Utilization\n\n'
  proc_section+='> `service_cpu` is service-process-tree CPU normalised to a single core (100% = 1 CPU fully busy).'$'\n'
  proc_section+='> `host_cpu` is host-level busy in core-percent from `/proc/stat` (100% = 1 CPU; max ~= NCPU×100, cloud-comparable).'$'\n'
  if [[ -n "${steady_state_start_iso}" && -n "${steady_state_end_iso}" ]]; then
    proc_section+="> Stats below are restricted to steady-state window: ${steady_state_start_iso} → ${steady_state_end_iso}."$'\n'
  fi
  proc_section+='> Memory values are Resident Set Size (RSS) — physical RAM in use.'$'\n\n'
  proc_section+='| Component | Node | service_cpu avg (%) | service_cpu p50 (%) | service_cpu p95 (%) | service_cpu p99 (%) | service_cpu peak (%) | host_cpu avg (%) | host_cpu p50 (%) | host_cpu p95 (%) | host_cpu p99 (%) | host_cpu peak (%) | Avg RSS (MiB) | Peak RSS (MiB) |'$'\n'
  proc_section+='|-----------|------|---------------------|---------------------|---------------------|---------------------|----------------------|------------------|------------------|------------------|------------------|-------------------|---------------|----------------|'$'\n'
  proc_section+="${proc_rows}"
fi

# Tier-level aligned CPU (sum by timestamp across nodes/components).
proxy_service_aligned_stats="$(aligned_sum_stats "cpu_pct" "${steady_state_start_iso}" "${steady_state_end_iso}" "${proxy_csv_files[@]}")"
IFS='|' read -r proxy_service_avg proxy_service_p50 proxy_service_p95 proxy_service_p99 proxy_service_peak proxy_service_samples proxy_service_nodes <<< "${proxy_service_aligned_stats}"
proxy_host_aligned_stats="$(aligned_sum_stats "host_cpu_pct" "${steady_state_start_iso}" "${steady_state_end_iso}" "${proxy_csv_files[@]}")"
IFS='|' read -r proxy_host_avg proxy_host_p50 proxy_host_p95 proxy_host_p99 proxy_host_peak _ _ <<< "${proxy_host_aligned_stats}"

lb_service_aligned_stats="$(aligned_sum_stats "cpu_pct" "${steady_state_start_iso}" "${steady_state_end_iso}" "${lb_csv_files[@]}")"
IFS='|' read -r lb_service_avg lb_service_p50 lb_service_p95 lb_service_p99 lb_service_peak lb_service_samples lb_service_nodes <<< "${lb_service_aligned_stats}"
lb_host_aligned_stats="$(aligned_sum_stats "host_cpu_pct" "${steady_state_start_iso}" "${steady_state_end_iso}" "${lb_csv_files[@]}")"
IFS='|' read -r lb_host_avg lb_host_p50 lb_host_p95 lb_host_p99 lb_host_peak _ _ <<< "${lb_host_aligned_stats}"

declare -a proxy_tier_csv_files=("${proxy_csv_files[@]}" "${lb_csv_files[@]}")
proxy_tier_service_aligned_stats="$(aligned_sum_stats "cpu_pct" "${steady_state_start_iso}" "${steady_state_end_iso}" "${proxy_tier_csv_files[@]}")"
IFS='|' read -r proxy_tier_service_avg proxy_tier_service_p50 proxy_tier_service_p95 proxy_tier_service_p99 proxy_tier_service_peak proxy_tier_service_samples proxy_tier_service_nodes <<< "${proxy_tier_service_aligned_stats}"
proxy_tier_host_aligned_stats="$(aligned_sum_stats "host_cpu_pct" "${steady_state_start_iso}" "${steady_state_end_iso}" "${proxy_tier_csv_files[@]}")"
IFS='|' read -r proxy_tier_host_avg proxy_tier_host_p50 proxy_tier_host_p95 proxy_tier_host_p99 proxy_tier_host_peak _ _ <<< "${proxy_tier_host_aligned_stats}"


p95_pass=$(awk "BEGIN {print (${agg_p95} < ${SLO_P95_LIMIT}) ? \"✅ PASS\" : \"❌ FAIL\"}")
error_pass=$(awk "BEGIN {print (${agg_error_rate} < ${SLO_ERROR_LIMIT}) ? \"✅ PASS\" : \"❌ FAIL\"}")

ojp_proxy_tier_avg_cpu="${proxy_service_avg}"
ojp_proxy_tier_peak_cpu="${proxy_service_peak}"
ojp_proxy_tier_p50_cpu="${proxy_service_p50}"
ojp_proxy_tier_p95_cpu="${proxy_service_p95}"
ojp_proxy_tier_p99_cpu="${proxy_service_p99}"
ojp_proxy_tier_peak_cpu_legacy_sum="${proxy_peak_cpu_sum}"
ojp_proxy_tier_avg_host_cpu="${proxy_host_avg}"
ojp_proxy_tier_peak_host_cpu="${proxy_host_peak}"
if [[ ${proxy_avg_rss_count} -gt 0 ]]; then
  ojp_proxy_tier_avg_rss="${proxy_avg_rss_sum}"
else
  ojp_proxy_tier_avg_rss="N/A"
fi
ojp_proxy_tier_peak_rss="${proxy_peak_rss_sum}"
proxy_avg_cpu_display="${proxy_service_avg}"
proxy_p50_cpu_display="${proxy_service_p50}"
proxy_p95_cpu_display="${proxy_service_p95}"
proxy_p99_cpu_display="${proxy_service_p99}"
proxy_peak_cpu_display="${proxy_service_peak}"
proxy_avg_rss_display="${proxy_avg_rss_sum}"
lb_avg_cpu_display="${lb_service_avg}"
lb_p50_cpu_display="${lb_service_p50}"
lb_p95_cpu_display="${lb_service_p95}"
lb_p99_cpu_display="${lb_service_p99}"
lb_peak_cpu_display="${lb_service_peak}"
lb_avg_rss_display="${lb_avg_rss_sum}"
if [[ ${proxy_avg_cpu_count} -eq 0 ]]; then proxy_avg_cpu_display="N/A"; proxy_p50_cpu_display="N/A"; proxy_p95_cpu_display="N/A"; proxy_p99_cpu_display="N/A"; proxy_peak_cpu_display="N/A"; fi
if [[ ${proxy_avg_rss_count} -eq 0 ]]; then proxy_avg_rss_display="N/A"; fi
if [[ ${lb_avg_cpu_count} -eq 0 ]]; then lb_avg_cpu_display="N/A"; lb_p50_cpu_display="N/A"; lb_p95_cpu_display="N/A"; lb_p99_cpu_display="N/A"; lb_peak_cpu_display="N/A"; fi
if [[ ${lb_avg_rss_count} -eq 0 ]]; then lb_avg_rss_display="N/A"; fi
if [[ ${proxy_avg_cpu_count} -eq 0 && ${lb_avg_cpu_count} -eq 0 ]]; then
  pgb_proxy_tier_avg_cpu="N/A"
else
  pgb_proxy_tier_avg_cpu="${proxy_tier_service_avg}"
fi
pgb_proxy_tier_p50_cpu="${proxy_tier_service_p50}"
pgb_proxy_tier_p95_cpu="${proxy_tier_service_p95}"
pgb_proxy_tier_p99_cpu="${proxy_tier_service_p99}"
pgb_proxy_tier_peak_cpu="${proxy_tier_service_peak}"
pgb_proxy_tier_peak_cpu_legacy_sum=$(awk "BEGIN {printf \"%.2f\", ${proxy_peak_cpu_sum} + ${lb_peak_cpu_sum}}")
pgb_proxy_tier_avg_host_cpu="${proxy_tier_host_avg}"
pgb_proxy_tier_peak_host_cpu="${proxy_tier_host_peak}"
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

metrics_completeness_section=""
declare -a metrics_warnings=()
expected_proxy_files="N/A"
expected_lb_files="N/A"
expected_db_files=1

if [[ "${run_sut}" == "OJP" && "${metadata_ojp_servers}" =~ ^[0-9]+$ ]]; then
  expected_proxy_files="${metadata_ojp_servers}"
elif [[ "${run_sut}" == "PGBOUNCER" && "${metadata_pgbouncer_nodes}" =~ ^[0-9]+$ ]]; then
  expected_proxy_files="${metadata_pgbouncer_nodes}"
fi

if [[ "${run_sut}" == "PGBOUNCER" && "${metadata_haproxy_nodes}" =~ ^[0-9]+$ ]]; then
  expected_lb_files="${metadata_haproxy_nodes}"
fi

if [[ "${expected_proxy_files}" =~ ^[0-9]+$ && "${proxy_files_count}" -ne "${expected_proxy_files}" ]]; then
  metrics_warnings+=("proxy process metric files mismatch: expected ${expected_proxy_files}, found ${proxy_files_count}")
fi
if [[ "${expected_lb_files}" =~ ^[0-9]+$ && "${lb_files_count}" -ne "${expected_lb_files}" ]]; then
  metrics_warnings+=("load-balancer process metric files mismatch: expected ${expected_lb_files}, found ${lb_files_count}")
fi
if [[ "${db_files_count}" -ne "${expected_db_files}" ]]; then
  metrics_warnings+=("db process metric files mismatch: expected ${expected_db_files}, found ${db_files_count}")
fi
if [[ "${missing_host_cpu_column_files}" -gt 0 ]]; then
  metrics_warnings+=("${missing_host_cpu_column_files} process metric file(s) missing host_cpu_pct column (likely older collector output)")
fi

if [[ ${#metrics_warnings[@]} -gt 0 ]]; then
  metrics_completeness_section+=$'\n## ⚠️ Metrics Completeness Warnings\n\n'
  metrics_completeness_section+="> These warnings indicate potentially incomplete or non-comparable CPU metrics."$'\n\n'
  for w in "${metrics_warnings[@]}"; do
    metrics_completeness_section+="- ${w}"$'\n'
  done
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
| OJP proxy-tier service_cpu (avg / p50 / p95 / p99 / aligned_peak) | ${ojp_proxy_tier_avg_cpu}% / ${ojp_proxy_tier_p50_cpu}% / ${ojp_proxy_tier_p95_cpu}% / ${ojp_proxy_tier_p99_cpu}% / ${ojp_proxy_tier_peak_cpu}% |
| OJP proxy-tier host_cpu (avg / peak) | ${ojp_proxy_tier_avg_host_cpu}% / ${ojp_proxy_tier_peak_host_cpu}% |
| OJP proxy-tier service_cpu legacy_peak_sum (non-time-aligned) | ${ojp_proxy_tier_peak_cpu_legacy_sum}% |
| OJP proxy-tier RSS (avg / peak, summed) | ${ojp_proxy_tier_avg_rss} MiB / ${ojp_proxy_tier_peak_rss} MiB |
| PgBouncer nodes | ${metadata_pgbouncer_nodes} |
| PgBouncer server pool size per node | ${metadata_pgbouncer_pool_size} |
| pgbouncer_reserve_pool_size | ${metadata_pgbouncer_reserve_pool_size} |
| PgBouncer local HikariCP pool size per replica | ${metadata_pgbouncer_local_pool_size} |
| HAProxy nodes | ${metadata_haproxy_nodes} |
| PgBouncer tier service_cpu (avg / p50 / p95 / p99 / aligned_peak) | ${proxy_avg_cpu_display}% / ${proxy_p50_cpu_display}% / ${proxy_p95_cpu_display}% / ${proxy_p99_cpu_display}% / ${proxy_peak_cpu_display}% |
| PgBouncer tier RSS (avg / peak, summed) | ${proxy_avg_rss_display} MiB / ${proxy_peak_rss_sum} MiB |
| HAProxy service_cpu (avg / p50 / p95 / p99 / aligned_peak) | ${lb_avg_cpu_display}% / ${lb_p50_cpu_display}% / ${lb_p95_cpu_display}% / ${lb_p99_cpu_display}% / ${lb_peak_cpu_display}% |
| HAProxy RSS (avg / peak, summed) | ${lb_avg_rss_display} MiB / ${lb_peak_rss_sum} MiB |
| Total PgBouncer proxy-tier service_cpu (avg / p50 / p95 / p99 / aligned_peak) | ${pgb_proxy_tier_avg_cpu}% / ${pgb_proxy_tier_p50_cpu}% / ${pgb_proxy_tier_p95_cpu}% / ${pgb_proxy_tier_p99_cpu}% / ${pgb_proxy_tier_peak_cpu}% |
| Total PgBouncer proxy-tier host_cpu (avg / peak) | ${pgb_proxy_tier_avg_host_cpu}% / ${pgb_proxy_tier_peak_host_cpu}% |
| Total PgBouncer proxy-tier service_cpu legacy_peak_sum (non-time-aligned) | ${pgb_proxy_tier_peak_cpu_legacy_sum}% |
| Total PgBouncer proxy-tier RSS (avg / peak) | ${pgb_proxy_tier_avg_rss} MiB / ${pgb_proxy_tier_peak_rss} MiB |

## Bench JVM System Metrics (in-process, median across instances)

| Metric | Value | Source |
|--------|-------|--------|
| **bench_jvm_cpu (median)** | ${agg_app_cpu} | \`OperatingSystemMXBean.getProcessCpuLoad()\` (in-process) |
| **Bench JVM GC pause total** | ${agg_gc_ms} ms | \`GarbageCollectorMXBean.getCollectionTime()\` |

---

## SLO Evaluation

| SLO | Threshold | Result |
|-----|-----------|--------|
| p95 latency | < ${SLO_P95_LIMIT} ms | ${p95_pass} (${agg_p95} ms) |
| Error rate | < $(awk "BEGIN {printf \"%.1f%%\", ${SLO_ERROR_LIMIT} * 100}") | ${error_pass} ($(awk "BEGIN {printf \"%.4f\", ${agg_error_rate}}")) |
${open_loop_section}

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
if [[ -n "${metrics_completeness_section}" ]]; then
  printf '%s' "---${metrics_completeness_section}"
fi

cat <<FOOTER

---

*Generated by \`ansible/scripts/generate_report.sh\` on $(date -u '+%Y-%m-%dT%H:%M:%SZ')*
FOOTER
} > "${OUTPUT_FILE}"

echo "Report written to: ${OUTPUT_FILE}"
