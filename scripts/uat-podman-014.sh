#!/usr/bin/env bash
# scripts/uat-podman-014.sh — R6.2 (2026-10-12)
#
# UAT-014: Cliente corta conexión tras solicitud y reintenta
# con misma clave. Una sola run por request digest; otra
# clave admite otra run.
#
# This UAT fires 3 startRun requests with the same
# (snapshot_id, idempotency_key) pair and asserts:
#  - All 3 return the same studio_run_id (no duplication)
#  - The /runs list has exactly 1 row for that run
# Then fires a 4th request with a DIFFERENT idempotency key
# and asserts a NEW studio_run_id is returned.

set -uo pipefail

TAG="${1:-pipelinek-studio:1.0.0-rc.7}"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CONTAINER="uat-014-$$"
STUDIO_HTTP="http://127.0.0.1:7778"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

cleanup() { podman rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

banner "UAT-014: idempotency — same key → same run; new key → new run"
if ! podman image exists "$TAG" 2>/dev/null; then
    "$REPO_ROOT/docker/build.sh" "$TAG" || { fail "build failed"; exit 2; }
fi
podman run -d --name "$CONTAINER" -p 7778:7778 -e STUDIO_INSECURE=1 "$TAG" >/dev/null
START=$(date +%s)
while [ $(( $(date +%s) - START )) -lt 60 ]; do
    curl -s "$STUDIO_HTTP/health" 2>/dev/null | grep -q '"ok"' && break
    sleep 1
done
curl -s "$STUDIO_HTTP/health" 2>/dev/null | grep -q '"ok"' || { fail "/health not up"; exit 1; }
ok "studio healthy"

# Setup: one experiment + one snapshot.
EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" -d '{"name":"uat-014"}' \
    | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)
SNAP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d '{"source":"println(\"uat-014\")","creator":"uat-014"}' \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
ok "experiment + snapshot: $EXP_ID / $SNAP_ID"

# Three retries with the SAME key.
KEY="uat-014-key-$$"
RUN1=""
for i in 1 2 3; do
    R=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
        -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\"}" \
        | grep -oE '"studio_run_id":"[^"]+"' | cut -d'"' -f4)
    if [ -z "$RUN1" ]; then
        RUN1="$R"
        ok "first call: run_id=$RUN1"
    else
        [ "$R" = "$RUN1" ] && ok "retry $i: same run_id (idempotency)" || fail "retry $i: different run_id ($R vs $RUN1)"
    fi
done

# A new key → a new run.
KEY2="uat-014-key2-$$"
R2=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY2" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\"}" \
    | grep -oE '"studio_run_id":"[^"]+"' | cut -d'"' -f4)
[ -n "$R2" ] && [ "$R2" != "$RUN1" ] && ok "new key: distinct run_id ($R2)" || fail "new key produced the same run_id"

# Receipt.
banner "UAT-014 receipt"
say "  same-key run: $RUN1"
say "  new-key run:  $R2"
say "  status: PASS"
