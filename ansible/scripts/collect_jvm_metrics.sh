#!/usr/bin/env bash
# ansible/scripts/collect_jvm_metrics.sh
#
# Collect OJP Server JVM heap and GC metrics using jstat.
# Writes a CSV to OUTPUT_FILE every second until killed.
#
# IMPORTANT — why jstat, not OS RSS:
#   Java acquires memory from the OS and does NOT return it after GC, so OS
#   RSS (as reported by ps/top) significantly overstates actual heap usage.
#   jstat -gc reports actual in-use heap from the JVM's own memory pools.
#
# Usage:
#   collect_jvm_metrics.sh <output_csv>
#
# Requirements: jstat (part of the JDK installed alongside java), bash >= 4.
# The OJP server PID is discovered automatically via systemctl.

set -uo pipefail

OUTPUT="${1:-/tmp/jvm_metrics.csv}"

# ── Discover OJP Server PID via systemd ──────────────────────────────────────

OJP_PID=$(systemctl show -p MainPID ojp-server 2>/dev/null | cut -d= -f2)

if [[ -z "${OJP_PID}" || "${OJP_PID}" == "0" ]]; then
  echo "ERROR: could not find PID for the 'ojp-server' systemd unit." \
       "Verify the service is running: systemctl status ojp-server" >&2
  exit 1
fi

echo "INFO: OJP server PID = ${OJP_PID}" >&2

# ── Locate jstat next to the running java binary ─────────────────────────────

JAVA_BIN=$(readlink -f "$(which java)")
JAVA_HOME_DIR=$(dirname "$(dirname "${JAVA_BIN}")")
JSTAT="${JAVA_HOME_DIR}/bin/jstat"

if [[ ! -x "${JSTAT}" ]]; then
  echo "ERROR: jstat not found at ${JSTAT}" >&2
  exit 1
fi

# ── Write PID file so Ansible can kill us cleanly ────────────────────────────

echo $$ > "${OUTPUT}.pid"

cleanup() {
  rm -f "${OUTPUT}.pid"
}
trap cleanup EXIT

# ── CSV header ────────────────────────────────────────────────────────────────
# Columns:
#   timestamp       — ISO 8601 UTC sample time
#   heap_used_mb    — S0U + S1U + EU + OU  (actual live bytes / 1024)
#   heap_committed_mb — S0C + S1C + EC + OC (committed capacity / 1024)
#   ygc_count       — young-gen GC events since JVM start
#   ygct_s          — cumulative young-gen GC time (seconds)
#   fgc_count       — full GC events since JVM start
#   fgct_s          — cumulative full GC time (seconds)
#   gct_s           — total GC time (seconds)

printf 'timestamp,heap_used_mb,heap_committed_mb,ygc_count,ygct_s,fgc_count,fgct_s,gct_s\n' \
  > "${OUTPUT}"

# ── Stream jstat output into CSV ──────────────────────────────────────────────
# jstat -gc <pid> <interval_ms>
# Output columns: S0C S1C S0U S1U EC EU OC OU MC MU CCSC CCSU YGC YGCT FGC FGCT CGC CGCT GCT

"${JSTAT}" -gc "${OJP_PID}" 1000 | while IFS= read -r line; do
  # Skip header lines (they start with a letter, not a digit or space+digit)
  [[ "${line}" =~ ^[[:space:]]*[[:alpha:]] ]] && continue
  # Skip blank lines
  [[ -z "${line// /}" ]] && continue

  # Parse space-separated numeric fields
  read -r S0C S1C S0U S1U EC EU OC OU MC MU CCSC CCSU YGC YGCT FGC FGCT CGC CGCT GCT \
    <<< "${line}" 2>/dev/null || continue

  # Validate that at least GCT was parsed as a number
  [[ "${GCT}" =~ ^[0-9]+\.?[0-9]*$ ]] || continue

  HEAP_USED=$(awk "BEGIN {printf \"%.2f\", (${S0U}+${S1U}+${EU}+${OU})/1024}")
  HEAP_COMM=$(awk "BEGIN {printf \"%.2f\", (${S0C}+${S1C}+${EC}+${OC})/1024}")
  TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "${TS}" "${HEAP_USED}" "${HEAP_COMM}" \
    "${YGC}" "${YGCT}" "${FGC}" "${FGCT}" "${GCT}" \
    >> "${OUTPUT}"
done
