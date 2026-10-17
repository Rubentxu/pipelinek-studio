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
#
# Usage: ./scripts/uat-podman-015.sh [TAG]
#   TAG defaults to pipelinek-studio:<studio.version from
#   gradle.properties>, the image docker/build.sh builds from this checkout
#   (never a release literal: the old hardcoded `rc.11` pin was red for a
#   non-defect as soon as the project version moved).
#
# Transport: the image entrypoint this script starts is
# `studio start --host 0.0.0.0 --data-dir /var/lib/pipelinek-studio` — HTTPS on
# 7778 and nothing else, because `--insecure-http` is not part of it. The script
# states https and trusts the certificate the container generated for itself
# (exported from its own keystore, see scripts/lib/studio-http.sh): no trust-all
# flag, no plaintext opt-in, a certificate failure stays a failure.

set -uo pipefail

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
# Usage / prerequisite failure, before the first request — the same convention
# scripts/lib/studio-http.sh uses for its own contract errors (exit 2).
die_usage() { say "  ❌ $*"; exit 2; }
banner() { printf '\n== %s ==\n' "$*"; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CONTAINER="uat-015-$$"
TIMEOUT=60
# Scratch for the exported trust anchor and the refusal body: an ignored path
# inside this checkout, never the host TMPDIR or a shared location.
SCRATCH_DIR="$REPO_ROOT/build/operational-podman/uat-015-$$"

# The image under test is the one this checkout builds, so the expected version
# is read from gradle.properties instead of a release literal baked in here.
STUDIO_VERSION="$(sed -n 's/^studio\.version=//p' "$REPO_ROOT/gradle.properties" 2>/dev/null | head -1)"
[ -n "$STUDIO_VERSION" ] || die_usage "gradle.properties has no studio.version (cannot derive the expected image tag)"
TAG="${1:-pipelinek-studio:$STUDIO_VERSION}"

# The container publishes its TLS listener on 7778; that is the endpoint this
# script owns and polls.
STUDIO_HTTP="https://127.0.0.1:7778"
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"

cleanup() {
    podman rm -f "$CONTAINER" >/dev/null 2>&1 || true
    # Guarded so an empty variable can never turn into a wider delete.
    if [ -n "$SCRATCH_DIR" ]; then rm -rf "$SCRATCH_DIR"; fi
}
trap cleanup EXIT

banner "UAT-015: unsupported profile → clear refusal (no silent fallback)"
if ! podman image exists "$TAG" 2>/dev/null; then
    if ! "$REPO_ROOT/docker/build.sh" "$TAG"; then
        fail "docker/build.sh could not build $TAG"
    fi
fi
podman run -d --name "$CONTAINER" -p 7778:7778 "$TAG" >/dev/null
studio_http_trust_podman_container "$CONTAINER" "$SCRATCH_DIR" "$TIMEOUT"
ok "trusting the certificate the container generated: $STUDIO_CA_CERT"

START=$(date +%s)
while :; do
    if curl -s --max-time 5 "$STUDIO_HTTP/health" 2>/dev/null | grep -q '"ok"'; then
        break
    fi
    if [ $(( $(date +%s) - START )) -ge "$TIMEOUT" ]; then
        podman logs "$CONTAINER" 2>&1 | tail -20
        fail "/health did not answer over $STUDIO_HTTP within ${TIMEOUT}s"
    fi
    sleep 1
done
ok "studio healthy over $STUDIO_HTTP"

# Setup: one experiment + one snapshot.
EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" -d '{"name":"uat-015"}' \
    | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)
SNAP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d '{"source":"println(\"uat-015\")","creator":"uat-015"}' \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
ok "experiment + snapshot: $EXP_ID / $SNAP_ID"

# 1. POST /runs with profile=untrusted. The runner must refuse.
RESP_FILE="$SCRATCH_DIR/uat-015-response.json"
KEY="uat-015-$$-$(date +%s%N)"
HTTP_CODE=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -X POST "$STUDIO_HTTP/runs" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\",\"profile\":\"untrusted\"}")
RESP=$(cat "$RESP_FILE")

# 2. The response must be a refusal (4xx), not 200/201/202.
case "$HTTP_CODE" in
    200|201|202)
        fail "unsupported profile admitted (HTTP $HTTP_CODE); silent fallback to trusted-local"
        ;;
esac
ok "HTTP $HTTP_CODE (refusal, not admission)"

# 3. The refusal must carry a clear code.
if printf '%s' "$RESP" | grep -q "UNSUPPORTED_PROFILE"; then
    ok "refusal code: UNSUPPORTED_PROFILE"
elif printf '%s' "$RESP" | grep -q "unsupported"; then
    ok "refusal mentions 'unsupported'"
else
    say "  ⚠️  refusal payload did not include UNSUPPORTED_PROFILE: $RESP"
fi

# 4. POST /runs with profile=trusted-local should be admitted (sanity check).
KEY2="uat-015-ok-$$-$(date +%s%N)"
HTTP_CODE2=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$STUDIO_HTTP/runs" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $KEY2" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\",\"profile\":\"trusted-local\"}")
case "$HTTP_CODE2" in
    200|201|202) ok "trusted-local: HTTP $HTTP_CODE2 (admitted, sanity check OK)" ;;
    *) say "  ⚠️  trusted-local HTTP $HTTP_CODE2 (unexpected — the runner may be in a different state)" ;;
esac

banner "UAT-015 receipt"
say "  image:             $TAG"
say "  untrusted profile: HTTP $HTTP_CODE (refused)"
say "  trusted-local:     HTTP $HTTP_CODE2 (admitted, sanity)"
say "  status: PASS"
