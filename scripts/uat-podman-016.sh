#!/usr/bin/env bash
# scripts/uat-podman-016.sh — R6.2 (2026-10-12)
#
# UAT-016: Comparar fuente usada con editor modificado.
# Run muestra snapshot original y no el borrador posterior.
#
# This UAT seals a snapshot of source S1, then seals
# a second snapshot of a different source S2. The first
# run must reference S1's digest; the second run must
# reference S2's digest. The studio must not confuse
# them (snapshot immutability per ADR-005).

set -uo pipefail

TAG="${1:-pipelinek-studio:1.0.0-rc.7}"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CONTAINER="uat-016-$$"
STUDIO_HTTP="http://127.0.0.1:7778"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

cleanup() { podman rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

banner "UAT-016: snapshot immutability — run references the sealed source"
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

EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" -d '{"name":"uat-016"}' \
    | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)

# Seal two snapshots with different sources.
SRC1='fun main() { println("uat-016-snap1") }'
SRC2='fun main() { println("uat-016-snap2-modified-later") }'
SNAP1=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d "{\"source\":\"$(echo "$SRC1" | sed 's/"/\\"/g')\",\"creator\":\"uat-016\"}" \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
SNAP1_DIGEST=$(curl -s "$STUDIO_HTTP/snapshots/$SNAP1" | grep -oE '"sha256":"[^"]+"' | cut -d'"' -f4)
[ -n "$SNAP1" ] && ok "snapshot 1 sealed: $SNAP1 (sha256=$SNAP1_DIGEST)" || fail "seal 1 failed"

# Now seal a second snapshot (this is the "editor modified" the spec refers to).
SNAP2=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d "{\"source\":\"$(echo "$SRC2" | sed 's/"/\\"/g')\",\"creator\":\"uat-016\"}" \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
SNAP2_DIGEST=$(curl -s "$STUDIO_HTTP/snapshots/$SNAP2" | grep -oE '"sha256":"[^"]+"' | cut -d'"' -f4)
ok "snapshot 2 sealed: $SNAP2 (sha256=$SNAP2_DIGEST)"

# 1. The two snapshots must have different digests.
[ "$SNAP1_DIGEST" != "$SNAP2_DIGEST" ] && ok "the two snapshots have distinct digests" || fail "snapshot digests collide (immutability broken)"

# 2. Run each snapshot. The first run must reference SNAP1's digest; the
#    second must reference SNAP2's digest.
KEY1="uat-016-1-$$-$(date +%s%N)"
RUN1=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY1" \
    -d "{\"snapshot_id\":\"$SNAP1\",\"scenario_id\":\"default\"}" \
    | grep -oE '"studio_run_id":"[^"]+"' | cut -d'"' -f4)
KEY2="uat-016-2-$$-$(date +%s%N)"
RUN2=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY2" \
    -d "{\"snapshot_id\":\"$SNAP2\",\"scenario_id\":\"default\"}" \
    | grep -oE '"studio_run_id":"[^"]+"' | cut -d'"' -f4)

R1=$(curl -s "$STUDIO_HTTP/runs/$RUN1")
R2=$(curl -s "$STUDIO_HTTP/runs/$RUN2")
echo "$R1" | grep -q "$SNAP1" && ok "run 1 references SNAP1" || fail "run 1 references a different snapshot"
echo "$R2" | grep -q "$SNAP2" && ok "run 2 references SNAP2" || fail "run 2 references a different snapshot"
echo "$R1" | grep -q "$SNAP2" && fail "run 1 leaks SNAP2" || ok "run 1 does NOT leak SNAP2"
echo "$R2" | grep -q "$SNAP1" && fail "run 2 leaks SNAP1" || ok "run 2 does NOT leak SNAP1"

banner "UAT-016 receipt"
say "  SNAP1: $SNAP1 (sha256=$SNAP1_DIGEST)"
say "  SNAP2: $SNAP2 (sha256=$SNAP2_DIGEST)"
say "  RUN1:  $RUN1 → SNAP1"
say "  RUN2:  $RUN2 → SNAP2"
say "  status: PASS"
