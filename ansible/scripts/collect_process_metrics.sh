#!/usr/bin/env bash
# ansible/scripts/collect_process_metrics.sh
#
# Sample OS-level CPU% and memory (RSS, VSZ) for a single systemd-managed
# process.  Writes a CSV to OUTPUT_FILE every second until killed.
#
# CPU is computed from /proc/<pid>/stat jiffie deltas — no extra packages
# needed.  A one-second wall-clock interval is measured with date(1) so the
# reported percentage is correct even when sleep drifts slightly.
#
# Memory is read from /proc/<pid>/status (VmRSS = resident, VmSize = virtual).
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
# Requirements: bash >= 4, systemctl, /proc filesystem (Linux only).
# The service PID is discovered automatically via systemctl; no root needed.

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

echo "INFO: ${SERVICE} PID = ${SVC_PID}" >&2

# ── Write PID file so Ansible can kill us cleanly ────────────────────────────

echo $$ > "${OUTPUT}.pid"

cleanup() {
  rm -f "${OUTPUT}.pid"
}
trap cleanup EXIT

# ── CSV header ────────────────────────────────────────────────────────────────
# Columns:
#   timestamp   — ISO 8601 UTC sample time
#   pid         — process PID (stable within a run)
#   cpu_pct     — CPU % since last sample (user+sys, normalised to 1 CPU = 100%)
#   rss_mb      — Resident Set Size in MiB  (/proc/<pid>/status VmRSS)
#   vsz_mb      — Virtual memory size in MiB (/proc/<pid>/status VmSize)

printf 'timestamp,pid,cpu_pct,rss_mb,vsz_mb\n' > "${OUTPUT}"

# ── Clock tick rate (usually 100 Hz on Linux) ─────────────────────────────────

CLK_TCK=$(getconf CLK_TCK 2>/dev/null || echo 100)

# ── Helper: read total CPU jiffies (utime + stime) for a PID ─────────────────

read_jiffies() {
  # Fields 14 (utime) and 15 (stime) in /proc/<pid>/stat are in clock ticks.
  awk '{print $14+$15}' "/proc/${1}/stat" 2>/dev/null || echo 0
}

# ── Polling loop ──────────────────────────────────────────────────────────────

prev_jiffies=$(read_jiffies "${SVC_PID}")
prev_ts=$(date +%s%N)   # nanoseconds since epoch

while true; do
  sleep 1

  # Verify the process is still alive before reading its /proc entry.
  if [[ ! -f "/proc/${SVC_PID}/stat" ]]; then
    echo "INFO: PID ${SVC_PID} (${SERVICE}) no longer exists — stopping collection." >&2
    break
  fi

  cur_jiffies=$(read_jiffies "${SVC_PID}")
  cur_ts=$(date +%s%N)

  # Elapsed wall-clock time in seconds (float via awk)
  elapsed_s=$(awk "BEGIN {printf \"%.6f\", (${cur_ts} - ${prev_ts}) / 1e9}")

  # Delta jiffies → CPU % (normalised to one core)
  delta_jiffies=$(( cur_jiffies - prev_jiffies ))
  cpu_pct=$(awk "BEGIN {
      if (${elapsed_s} > 0 && ${CLK_TCK} > 0)
        printf \"%.2f\", ${delta_jiffies} / ${CLK_TCK} / ${elapsed_s} * 100;
      else
        printf \"0.00\"
    }")

  # RSS and VSZ from /proc/<pid>/status (values reported in kB)
  rss_kb=$(awk '/^VmRSS:/{print $2}' "/proc/${SVC_PID}/status" 2>/dev/null || echo 0)
  vsz_kb=$(awk '/^VmSize:/{print $2}' "/proc/${SVC_PID}/status" 2>/dev/null || echo 0)
  rss_mb=$(awk "BEGIN {printf \"%.2f\", ${rss_kb:-0} / 1024}")
  vsz_mb=$(awk "BEGIN {printf \"%.2f\", ${vsz_kb:-0} / 1024}")

  TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf '%s,%s,%s,%s,%s\n' \
    "${TS}" "${SVC_PID}" "${cpu_pct}" "${rss_mb}" "${vsz_mb}" \
    >> "${OUTPUT}"

  prev_jiffies="${cur_jiffies}"
  prev_ts="${cur_ts}"
done
