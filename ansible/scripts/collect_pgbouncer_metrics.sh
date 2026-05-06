#!/usr/bin/env bash
# ansible/scripts/collect_pgbouncer_metrics.sh
#
# Poll the pgBouncer admin console (SHOW STATS and SHOW POOLS) during a
# benchmark run.  Writes one CSV row per database per sample interval.
# Stops when killed.
#
# Usage:
#   collect_pgbouncer_metrics.sh <output_csv> [admin_host] [admin_port] [interval_seconds] [admin_user]
#
# Defaults:
#   admin_host      127.0.0.1
#   admin_port      6432
#   interval_seconds  5
#   admin_user      postgres
#
# Requirements: psql (postgresql-client), bash >= 4.
#
# pgBouncer admin access notes:
#   By default pgBouncer allows only the OS user that owns the pgBouncer process
#   (typically 'postgres' on Ubuntu) to connect to the 'pgbouncer' admin database.
#   Run this script as the 'postgres' OS user (become_user: postgres in Ansible)
#   or add the target user to admin_users/stats_users in pgbouncer.ini.

set -uo pipefail

OUTPUT="${1:-/tmp/pgbouncer_admin_metrics.csv}"
ADMIN_HOST="${2:-127.0.0.1}"
ADMIN_PORT="${3:-6432}"
INTERVAL="${4:-5}"
ADMIN_USER="${5:-postgres}"

# ── Write PID file so Ansible can kill us cleanly ────────────────────────────

echo $$ > "${OUTPUT}.pid"

cleanup() {
  rm -f "${OUTPUT}.pid"
}
trap cleanup EXIT

# ── CSV header ────────────────────────────────────────────────────────────────
# Columns:
#   timestamp         — ISO 8601 UTC sample time
#   database          — pgBouncer logical database name
#   total_xact_count  — cumulative transactions since pgBouncer start
#   total_query_count — cumulative queries since pgBouncer start
#   avg_xact_time_us  — rolling avg transaction duration (microseconds)
#   avg_query_time_us — rolling avg query duration (microseconds)
#   avg_wait_time_us  — rolling avg time waiting for a free server conn (µs)
#   cl_active         — clients currently executing a transaction
#   cl_waiting        — clients queued waiting for a free server connection
#   sv_active         — server connections currently in use by a client
#   sv_idle           — server connections idle and available
#   maxwait_s         — longest current client wait (seconds, from SHOW POOLS)

printf 'timestamp,database,total_xact_count,total_query_count,avg_xact_time_us,avg_query_time_us,avg_wait_time_us,cl_active,cl_waiting,sv_active,sv_idle,maxwait_s\n' \
  > "${OUTPUT}"

# ── psql helper ───────────────────────────────────────────────────────────────
# PGPASSWORD="" matches the empty-password entry for "postgres" in userlist.txt.
# The -w flag suppresses any interactive password prompt so the script can run
# unattended as a background process.

pgb_query() {
  PGPASSWORD="" psql -h "${ADMIN_HOST}" -p "${ADMIN_PORT}" -U "${ADMIN_USER}" pgbouncer \
    -w -t -A -F',' -c "$1" 2>/dev/null || true
}

# ── Polling loop ──────────────────────────────────────────────────────────────

while true; do
  TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  # Snapshot SHOW POOLS once per interval (used for all per-db lookups below)
  pools_snapshot=$(pgb_query "SHOW POOLS;")

  # Process SHOW STATS — one row per logical database; skip admin pseudo-db
  # SHOW STATS columns (pgBouncer >= 1.18):
  #   database, total_xact_count, total_query_count, total_received, total_sent,
  #   total_xact_time, total_query_time, total_wait_time,
  #   avg_xact_count, avg_query_count, avg_recv, avg_sent,
  #   avg_xact_time, avg_query_time, avg_wait_time
  while IFS=',' read -r db txact tqry _recv _sent _txact_time _qry_time _wait_time \
                           _avg_xc _avg_qc _avg_recv _avg_sent \
                           avg_xact_us avg_query_us avg_wait_us _rest; do
    # Skip header, the internal pgbouncer pseudo-database, and blank lines
    [[ "${db}" == "pgbouncer" || "${db}" == "database" || -z "${db}" ]] && continue

    # Look up pool occupancy for this database from the cached snapshot
    # SHOW POOLS columns: database, user, cl_active, cl_waiting, sv_active,
    #                     sv_idle, sv_used, sv_tested, sv_login, maxwait, maxwait_us, pool_mode
    pool_row=$(echo "${pools_snapshot}" \
      | awk -F',' -v d="${db}" '$1==d {print; exit}')

    cl_active=0; cl_waiting=0; sv_active=0; sv_idle=0; maxwait_s=0
    if [[ -n "${pool_row}" ]]; then
      IFS=',' read -r _pdb _puser cl_active cl_waiting sv_active sv_idle \
                      _sv_used _sv_tested _sv_login maxwait_s _rest2 \
        <<< "${pool_row}" 2>/dev/null || true
    fi

    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "${TS}" "${db}" \
      "${txact:-0}" "${tqry:-0}" \
      "${avg_xact_us:-0}" "${avg_query_us:-0}" "${avg_wait_us:-0}" \
      "${cl_active:-0}" "${cl_waiting:-0}" \
      "${sv_active:-0}" "${sv_idle:-0}" "${maxwait_s:-0}" \
      >> "${OUTPUT}"
  done < <(pgb_query "SHOW STATS;")

  sleep "${INTERVAL}"
done
