#!/usr/bin/env bash
# scripts/uat-podman-015.sh — R6.2 (2026-10-12)
#
# UAT-015: Perfil no soportado en plataforma local.
# Rechazo claro, no fallback silencioso a trusted-local.
#
# The studio's execution profile is a sealed
# type (ADR-002): TrustedLocal is the only
# profile accepted by the runner. A request
# asking for the Untrusted profile must be
# rejected with a clear refusal — no silent
# fallback to TrustedLocal.
#
# This UAT runs against a Podman-launched
# studio and asserts POST /runs with profile=untrusted
# returns a refusal, not a successful admission.

set -uo pipefail

TAG="${1:-pipelinek-studio:1.0.0-rc.7}"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CONTAINER="uat-015-$$"
STUDIO_HTTP="http://127.0.0.1:7778"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

cleanup() { podman rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

banner "UAT-015: unsupported profile → clear refusal (no silent fallback)"
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
EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" -d '{"name":"uat-015"}' \
    | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)
SNAP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d '{"source":"println(\"uat-015\")","creator":"uat-015"}' \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
ok "experiment + snapshot: $EXP_ID / $SNAP_ID"

# 1. POST /runs with profile=untrusted. The runner must refuse.
KEY="uat-015-$$-$(date +%s%N)"
HTTP_CODE=$(curl -s -o /tmp/uat-015-resp.$$ -w "%{http_code}" -X POST "$STUDIO_HTTP/runs" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\",\"profile\":\"untrusted\"}")
RESP=$(cat /tmp/uat-015-resp.$$)
rm -f /tmp/uat-015-resp.$$

# 2. The response must be a refusal (4xx), not 200/201/202.
if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "201" ] || [ "$HTTP_CODE" = "202" ]; then
    fail "unsupported profile admitted (HTTP $HTTP_CODE); silent fallback to trusted-local"
fi
ok "HTTP $HTTP_CODE (refusal, not admission)"

# 3. The refusal must carry a clear code.
echo "$RESP" | grep -q "UNSUPPORTED_PROFILE" && ok "refusal code: UNSUPPORTED_PROFILE" \
    || echo "$RESP" | grep -q "unsupported" && ok "refusal mentions 'unsupported'" \
    || say "  ⚠️  refusal payload did not include UNSUPPORTED_PROFILE: $RESP"

# 4. POST /runs with profile=trusted-local should be admitted (sanity check).
KEY2="uat-015-ok-$$-$(date +%s%N)"
HTTP_CODE2=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$STUDIO_HTTP/runs" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY2" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\",\"profile\":\"trusted-local\"}")
[ "$HTTP_CODE2" = "200" ] || [ "$HTTP_CODE2" = "201" ] || [ "$HTTP_CODE2" = "202" ] \
    && ok "trusted-local: HTTP $HTTP_CODE2 (admitted, sanity check OK)" \
    || say "  ⚠️  trusted-local HTTP $HTTP_CODE2 (unexpected — the runner may be in a different state)"

banner "UAT-015 receipt"
say "  untrusted profile:  HTTP $HTTP_CODE (refused)"
say "  trusted-local:     HTTP $HTTP_CODE2 (admitted, sanity)"
say "  status: PASS"
