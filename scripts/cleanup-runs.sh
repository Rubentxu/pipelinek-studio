#!/usr/bin/env bash
# scripts/cleanup-runs.sh
#
# S5 retention cleanup. Operator-driven per D-005: this script NEVER
# runs automatically; the operator must invoke it explicitly with a
# TTL and (optionally) --apply. The default mode is DRY-RUN that
# prints what would be deleted.
#
# Policy (D-007 + R-07):
#   - Only terminal runs are eligible: state IN (COMPLETED, FAILED,
#     CANCELLED). RUNNING and QUEUED runs are NEVER touched.
#   - Only per-run DBs whose run binding is older than --ttl-days
#     AND in a terminal state are eligible.
#   - The studio.db metadata row is NEVER deleted; only the per-run
#     DB file referenced by run_db_ref is removed.
#   - A `--vacuum` step runs sqlite3 VACUUM on the per-run DB before
#     deletion is a no-op (the file is about to be removed anyway),
#     but it documents the contract.
#
# Usage:
#   ./scripts/cleanup-runs.sh --db <studio.db> --run-dbs-dir <path> [--ttl-days 30] [--apply]
#
# Default: dry-run, ttl-days=30. The operator reviews the report and
# re-runs with --apply when satisfied.

set -euo pipefail

DB=""
RUNS_DIR=""
TTL_DAYS=30
APPLY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --db) DB="$2"; shift 2 ;;
        --run-dbs-dir) RUNS_DIR="$2"; shift 2 ;;
        --ttl-days) TTL_DAYS="$2"; shift 2 ;;
        --apply) APPLY=1; shift ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *)
            echo "ERROR: unknown arg $1" >&2
            exit 1
            ;;
    esac
done

if [ -z "$DB" ] || [ -z "$RUNS_DIR" ]; then
    echo "ERROR: --db and --run-dbs-dir are required" >&2
    echo "Try: $0 --db studio.db --run-dbs-dir ./run-dbs --ttl-days 30" >&2
    exit 1
fi

if [ ! -f "$DB" ]; then
    echo "ERROR: studio.db not found: $DB" >&2
    exit 1
fi

if [ ! -d "$RUNS_DIR" ]; then
    echo "WARN: run-dbs dir does not exist: $RUNS_DIR; nothing to clean" >&2
    exit 0
fi

if ! command -v sqlite3 >/dev/null 2>&1; then
    echo "ERROR: sqlite3 CLI not on PATH (required to read $DB)" >&2
    exit 1
fi

# Query: for each terminal run older than TTL_DAYS, emit the run_db_ref.
# The cast (julianday('now') - julianday(updated_at)) is the day delta.
ROWS=$(sqlite3 "$DB" "SELECT run_id, run_db_ref, state, updated_at FROM runs WHERE state IN ('COMPLETED','FAILED','CANCELLED') AND (julianday('now') - julianday(updated_at)) > $TTL_DAYS ORDER BY updated_at ASC;" 2>/dev/null || true)

if [ -z "$ROWS" ]; then
    echo "[retention] no terminal runs older than $TTL_DAYS days" >&2
    exit 0
fi

# Count + total size for the report.
COUNT=0
TOTAL_BYTES=0
REPORT=""

while IFS='|' read -r RUN_ID REF STATE UPDATED; do
    [ -z "$REF" ] && continue
    [ -f "$REF" ] || continue
    BYTES=$(stat -c '%s' "$REF" 2>/dev/null || stat -f '%z' "$REF" 2>/dev/null || echo 0)
    REPORT+="${RUN_ID}|${STATE}|${UPDATED}|${REF}|${BYTES}"$'\n'
    COUNT=$((COUNT + 1))
    TOTAL_BYTES=$((TOTAL_BYTES + BYTES))
done <<< "$ROWS"

echo "[retention] mode: $([ $APPLY -eq 1 ] && echo APPLY || echo DRY-RUN)" >&2
echo "[retention] TTL: $TTL_DAYS days" >&2
echo "[retention] eligible: $COUNT per-run DBs, $TOTAL_BYTES bytes total" >&2
echo "[retention] run_db_ref prefix: $RUNS_DIR" >&2
echo >&2

if [ $COUNT -eq 0 ]; then
    echo "[retention] nothing to do" >&2
    exit 0
fi

if [ $APPLY -eq 0 ]; then
    echo "run_id | state | updated_at | path | bytes" >&2
    echo "$REPORT" | tr '|' ' '
    echo >&2
    echo "[retention] re-run with --apply to actually remove these files" >&2
    exit 0
fi

# APPLY mode: delete the per-run DB files (never touch studio.db).
DELETED=0
while IFS='|' read -r RUN_ID STATE UPDATED REF BYTES; do
    [ -z "$REF" ] && continue
    if rm -f "$REF"; then
        DELETED=$((DELETED + 1))
        echo "[retention] deleted $REF (run=$RUN_ID state=$STATE updated=$UPDATED bytes=$BYTES)" >&2
    else
        echo "[retention] FAILED to delete $REF (run=$RUN_ID)" >&2
    fi
done <<< "$REPORT"

echo >&2
echo "[retention] removed $DELETED per-run DBs; studio.db metadata preserved" >&2
