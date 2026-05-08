#!/usr/bin/env bash
# ansible/scripts/collect_process_metrics.sh
#
# Sample OS-level CPU% and memory (RSS, VSZ) for a systemd-managed service
# process tree (MainPID + all descendant processes). Writes a CSV to
# OUTPUT_FILE every second until killed.
#
# CPU is computed from /proc/<pid>/stat jiffie deltas — no extra packages
# needed.  A one-second wall-clock interval is measured with date(1) so the
# reported percentage is correct even when sleep drifts slightly.
#
# Memory is read from /proc/<pid>/status (VmRSS = resident, VmSize = virtual)
# and summed across the entire process tree.
#
# IMPORTANT — why /proc and not pidstat/ps:
#   /proc is available on every Linux kernel without installing sysstat.
#   pidstat reports % per-CPU; here we normalise to a single core so that
#   100% always means "one full CPU busy", regardless of the CPU count.
#
# Usage:
#   collect_process_metrics.sh <service_name> <output_csv>
#
#   <service_name>  — systemd unit name, e.g. "ojp-server", "pgbouncer",
#                     "haproxy", "postgresql@16-main"
#   <output_csv>    — path of the CSV file to write
#
# Requirements: bash >= 4, systemctl, pgrep, /proc filesystem (Linux only).
# The service MainPID is discovered automatically via systemctl; no root needed.

set -uo pipefail

SERVICE="${1:?Usage: $0 <service_name> <output_csv>}"
OUTPUT="${2:?Usage: $0 <service_name> <output_csv>}"

# ── Discover PID via systemd ──────────────────────────────────────────────────

SVC_PID=$(systemctl show -p MainPID "${SERVICE}" 2>/dev/null | cut -d= -f2)

if [[ -z "${SVC_PID}" || "${SVC_PID}" == "0" ]]; then
  echo "ERROR: could not find PID for systemd unit '${SERVICE}'." \
       "Verify the service is running: systemctl status ${SERVICE}" >&2
  exit 1
fi

echo "INFO: ${SERVICE} MainPID = ${SVC_PID}" >&2

# ── Write PID file so Ansible can kill us cleanly ────────────────────────────

echo $$ > "${OUTPUT}.pid"

cleanup() {
  rm -f "${OUTPUT}.pid"
}
trap cleanup EXIT

# ── CSV header ────────────────────────────────────────────────────────────────
# Columns:
#   timestamp   — ISO 8601 UTC sample time
#   pid         — service MainPID (root of sampled process tree)
#   cpu_pct     — CPU % since last sample (user+sys, normalised to 1 CPU = 100%)
#   rss_mb      — Resident Set Size in MiB  (/proc/<pid>/status VmRSS)
#   vsz_mb      — Virtual memory size in MiB (/proc/<pid>/status VmSize)

printf 'timestamp,pid,cpu_pct,rss_mb,vsz_mb\n' > "${OUTPUT}"

# ── Clock tick rate (usually 100 Hz on Linux) ─────────────────────────────────

CLK_TCK=$(getconf CLK_TCK 2>/dev/null || echo 100)

# ── Helpers ────────────────────────────────────────────────────────────────────

read_jiffies() {
  # Fields 14 (utime) and 15 (stime) in /proc/<pid>/stat are in clock ticks.
  awk '{print $14+$15}' "/proc/${1}/stat" 2>/dev/null || echo 0
}

read_kb_status_field() {
  local pid="${1}"
  local field="${2}"
  awk -v f="${field}" '$1 == f ":" {print $2}' "/proc/${pid}/status" 2>/dev/null || echo 0
}

collect_descendants() {
  local root_pid="${1}"
  local child_pid
  printf '%s\n' "${root_pid}"
  while IFS= read -r child_pid; do
    [[ -n "${child_pid}" ]] || continue
    collect_descendants "${child_pid}"
  done < <(pgrep -P "${root_pid}" 2>/dev/null || true)
}

collect_service_tree_pids() {
  local root_pid="${1}"
  local pid
  declare -A seen=()
  while IFS= read -r pid; do
    [[ -n "${pid}" ]] || continue
    [[ -d "/proc/${pid}" ]] || continue
    if [[ -z "${seen[${pid}]+x}" ]]; then
      seen["${pid}"]=1
      printf '%s\n' "${pid}"
    fi
  done < <(collect_descendants "${root_pid}")
}

# ── Polling loop ──────────────────────────────────────────────────────────────

declare -A prev_jiffies_by_pid=()
prev_ts=$(date +%s%N)   # nanoseconds since epoch

while true; do
  sleep 1

  # Verify root process is still alive before sampling its process tree.
  if [[ ! -f "/proc/${SVC_PID}/stat" ]]; then
    echo "INFO: PID ${SVC_PID} (${SERVICE}) no longer exists — stopping collection." >&2
    break
  fi

  mapfile -t svc_pids < <(collect_service_tree_pids "${SVC_PID}")
  if [[ ${#svc_pids[@]} -eq 0 ]]; then
    echo "INFO: no live PIDs found for ${SERVICE} process tree — stopping collection." >&2
    break
  fi

  delta_jiffies=0
  rss_kb_total=0
  vsz_kb_total=0
  declare -A next_prev_jiffies_by_pid=()

  for pid in "${svc_pids[@]}"; do
    cur_jiffies=$(read_jiffies "${pid}")
    prev_jiffies="${prev_jiffies_by_pid[${pid}]:-${cur_jiffies}}"
    delta_jiffies=$(( delta_jiffies + cur_jiffies - prev_jiffies ))
    next_prev_jiffies_by_pid["${pid}"]="${cur_jiffies}"

    rss_kb=$(read_kb_status_field "${pid}" "VmRSS")
    vsz_kb=$(read_kb_status_field "${pid}" "VmSize")
    rss_kb_total=$(( rss_kb_total + rss_kb ))
    vsz_kb_total=$(( vsz_kb_total + vsz_kb ))
  done

  unset prev_jiffies_by_pid
  declare -A prev_jiffies_by_pid=()
  for pid in "${!next_prev_jiffies_by_pid[@]}"; do
    prev_jiffies_by_pid["${pid}"]="${next_prev_jiffies_by_pid[${pid}]}"
  done

  cur_ts=$(date +%s%N)

  # Elapsed wall-clock time in seconds (float via awk)
  elapsed_s=$(awk "BEGIN {printf \"%.6f\", (${cur_ts} - ${prev_ts}) / 1e9}")

  # Delta jiffies → CPU % (normalised to one core)
  cpu_pct=$(awk "BEGIN {
      if (${elapsed_s} > 0 && ${CLK_TCK} > 0)
        printf \"%.2f\", ${delta_jiffies} / ${CLK_TCK} / ${elapsed_s} * 100;
      else
        printf \"0.00\"
    }")

  # RSS and VSZ from /proc/<pid>/status summed across all tree PIDs (kB → MiB)
  rss_mb=$(awk "BEGIN {printf \"%.2f\", ${rss_kb_total:-0} / 1024}")
  vsz_mb=$(awk "BEGIN {printf \"%.2f\", ${vsz_kb_total:-0} / 1024}")

  TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf '%s,%s,%s,%s,%s\n' \
    "${TS}" "${SVC_PID}" "${cpu_pct}" "${rss_mb}" "${vsz_mb}" \
    >> "${OUTPUT}"

  prev_ts="${cur_ts}"
done
