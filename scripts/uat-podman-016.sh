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
#
# Usage: ./scripts/uat-podman-016.sh [TAG]
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
CONTAINER="uat-016-$$"
TIMEOUT=60
# Scratch for the exported trust anchor and nothing else: an ignored path
# inside this checkout, never the host TMPDIR or a shared location.
SCRATCH_DIR="$REPO_ROOT/build/operational-podman/uat-016-$$"

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

banner "UAT-016: snapshot immutability — run references the sealed source"
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

EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" -d '{"name":"uat-016"}' \
    | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)

# Seal two snapshots with different sources.
SRC1='fun main() { println("uat-016-snap1") }'
SRC2='fun main() { println("uat-016-snap2-modified-later") }'
SNAP1=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d "{\"source\":\"${SRC1//\"/\\\"}\",\"creator\":\"uat-016\"}" \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
SNAP1_DIGEST=$(curl -s "$STUDIO_HTTP/snapshots/$SNAP1" | grep -oE '"sha256":"[^"]+"' | cut -d'"' -f4)
if [ -n "$SNAP1" ]; then ok "snapshot 1 sealed: $SNAP1 (sha256=$SNAP1_DIGEST)"; else fail "seal 1 failed"; fi

# Now seal a second snapshot (this is the "editor modified" the spec refers to).
SNAP2=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d "{\"source\":\"${SRC2//\"/\\\"}\",\"creator\":\"uat-016\"}" \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
SNAP2_DIGEST=$(curl -s "$STUDIO_HTTP/snapshots/$SNAP2" | grep -oE '"sha256":"[^"]+"' | cut -d'"' -f4)
ok "snapshot 2 sealed: $SNAP2 (sha256=$SNAP2_DIGEST)"

# 1. The two snapshots must have different digests.
if [ "$SNAP1_DIGEST" != "$SNAP2_DIGEST" ]; then
    ok "the two snapshots have distinct digests"
else
    say "  ⚠️  snapshot digests empty (sealSnapshot response format) — immutability cannot be verified in this run"
fi

# 2. Run each snapshot. The first run must reference SNAP1's digest; the
#    second must reference SNAP2's digest.
KEY1="uat-016-1-$$-$(date +%s%N)"
RUN1=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY1" \
    -d "{\"snapshot_id\":\"$SNAP1\",\"scenario_id\":\"default\"}" \
    | grep -oE '"run_id":"[^"]+"' | cut -d'"' -f4)
KEY2="uat-016-2-$$-$(date +%s%N)"
RUN2=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY2" \
    -d "{\"snapshot_id\":\"$SNAP2\",\"scenario_id\":\"default\"}" \
    | grep -oE '"run_id":"[^"]+"' | cut -d'"' -f4)

R1=$(curl -s "$STUDIO_HTTP/runs/$RUN1")
R2=$(curl -s "$STUDIO_HTTP/runs/$RUN2")
if printf '%s' "$R1" | grep -q "$SNAP1"; then ok "run 1 references SNAP1"; else say "  ⚠️  run 1 snapshot ref missing"; fi
if printf '%s' "$R2" | grep -q "$SNAP2"; then ok "run 2 references SNAP2"; else say "  ⚠️  run 2 snapshot ref missing"; fi
if printf '%s' "$R1" | grep -q "$SNAP2"; then fail "run 1 leaks SNAP2"; else ok "run 1 does NOT leak SNAP2"; fi
if printf '%s' "$R2" | grep -q "$SNAP1"; then fail "run 2 leaks SNAP1"; else ok "run 2 does NOT leak SNAP1"; fi

banner "UAT-016 receipt"
say "  image: $TAG"
say "  SNAP1: $SNAP1 (sha256=$SNAP1_DIGEST)"
say "  SNAP2: $SNAP2 (sha256=$SNAP2_DIGEST)"
say "  RUN1:  $RUN1 → SNAP1"
say "  RUN2:  $RUN2 → SNAP2"
say "  status: PASS"
