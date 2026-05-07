#!/usr/bin/env bash
# ansible/scripts/collect_pg_metrics.sh
#
# Poll PostgreSQL internal statistics during a benchmark run.
# Queries pg_stat_database, pg_stat_bgwriter, and pg_locks every
# INTERVAL seconds and appends rows to OUTPUT_FILE.
# Stops when killed.
#
# Usage:
#   collect_pg_metrics.sh <output_csv> <dbname> <pg_user> [interval_seconds]
#
# Requirements: psql (postgresql-client), bash >= 4.
# Must be run as a user that can connect to PostgreSQL without a password
# (typically the postgres OS user, or with a .pgpass file).

set -uo pipefail

OUTPUT="${1:-/tmp/pg_metrics.csv}"
DB="${2:-benchdb}"
PG_USER="${3:-postgres}"
INTERVAL="${4:-5}"

# ── Write PID file so Ansible can kill us cleanly ────────────────────────────

echo $$ > "${OUTPUT}.pid"

cleanup() {
  rm -f "${OUTPUT}.pid"
}
trap cleanup EXIT

# ── CSV header ────────────────────────────────────────────────────────────────
# Columns:
#   timestamp           — ISO 8601 UTC sample time
#   numbackends         — active backend connections right now
#   active_backends     — client backends in state='active'
#   idle_backends       — client backends in state='idle'
#   xact_commit         — cumulative committed transactions
#   xact_rollback       — cumulative rolled-back transactions
#   blks_hit            — cumulative shared-buffer hits
#   blks_read           — cumulative disk reads (cache misses)
#   cache_hit_pct       — blks_hit / (blks_hit + blks_read) × 100
#   temp_bytes          — cumulative bytes written to temp files (sort/hash spills)
#   deadlocks           — cumulative deadlock count
#   lock_waits          — instantaneous ungranted lock count
#   buffers_checkpoint  — cumulative buffers written by checkpointer
#   checkpoint_write_ms — cumulative ms spent writing during checkpoints

printf 'timestamp,numbackends,active_backends,idle_backends,xact_commit,xact_rollback,blks_hit,blks_read,cache_hit_pct,temp_bytes,deadlocks,lock_waits,buffers_checkpoint,checkpoint_write_ms\n' \
  > "${OUTPUT}"

# ── Polling loop ──────────────────────────────────────────────────────────────

while true; do
  TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  # pg_stat_database row for the target database.
  # 8 fields: numbackends,xact_commit,xact_rollback,blks_hit,blks_read,
  #           cache_hit_pct,temp_bytes,deadlocks
  # (Full CSV row = timestamp(1) + DB_ROW(8) + ACTIVITY_ROW(2) + LOCK_WAITS(1) + BG_ROW(2) = 14 cols)
  DB_ROW=$(psql -U "${PG_USER}" -d "${DB}" -t -A -F',' \
    -c "SELECT numbackends, xact_commit, xact_rollback, blks_hit, blks_read,
               CASE WHEN (blks_hit + blks_read) > 0
                    THEN ROUND(blks_hit * 100.0 / (blks_hit + blks_read), 2)
                    ELSE 100 END,
               temp_bytes, deadlocks
        FROM pg_stat_database
        WHERE datname = current_database();" 2>/dev/null \
    || echo "0,0,0,0,0,100,0,0")  # 8-field fallback matches DB_ROW columns above

  # Active and idle client backend counts for this database.
  ACTIVITY_ROW=$(psql -U "${PG_USER}" -d "${DB}" -t -A -F',' \
    -c "SELECT
          count(*) FILTER (WHERE state = 'active'),
          count(*) FILTER (WHERE state = 'idle')
        FROM pg_stat_activity
        WHERE datname = current_database()
          AND backend_type = 'client backend';" 2>/dev/null \
    || echo "0,0")

  # Instantaneous ungranted lock count (1 field)
  LOCK_WAITS=$(psql -U "${PG_USER}" -d "${DB}" -t -A \
    -c "SELECT count(*) FROM pg_locks WHERE NOT granted;" 2>/dev/null \
    || echo "0")

  # pg_stat_bgwriter (2 fields: buffers_checkpoint, checkpoint_write_time)
  BG_ROW=$(psql -U "${PG_USER}" -d "${DB}" -t -A -F',' \
    -c "SELECT buffers_checkpoint, checkpoint_write_time
        FROM pg_stat_bgwriter;" 2>/dev/null \
    || echo "0,0")

  # Trim whitespace
  DB_ROW="${DB_ROW//[[:space:]]/}"
  ACTIVITY_ROW="${ACTIVITY_ROW//[[:space:]]/}"
  LOCK_WAITS="${LOCK_WAITS//[[:space:]]/}"
  BG_ROW="${BG_ROW//[[:space:]]/}"

  printf '%s,%s,%s,%s,%s\n' \
    "${TS}" "${DB_ROW}" "${ACTIVITY_ROW}" "${LOCK_WAITS}" "${BG_ROW}" \
    >> "${OUTPUT}"

  sleep "${INTERVAL}"
done
