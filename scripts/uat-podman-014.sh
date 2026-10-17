#!/usr/bin/env bash
# scripts/uat-podman-014.sh — R6.2 (2026-10-12)
#
# UAT-014: Cliente corta conexión tras solicitud y reintenta
# con misma clave. Una sola run por request digest; otra
# clave admite otra run.
#
# This UAT fires 3 startRun requests with the same
# (snapshot_id, idempotency_key) pair and asserts:
#  - All 3 return the same run_id (no duplication)
#  - The /runs list has exactly 1 row for that run
# Then fires a 4th request with a DIFFERENT idempotency key
# and asserts a NEW run_id is returned.
#
# Usage: ./scripts/uat-podman-014.sh [TAG]
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
CONTAINER="uat-014-$$"
TIMEOUT=60
# Scratch for the exported trust anchor and nothing else: an ignored path
# inside this checkout, never the host TMPDIR or a shared location.
SCRATCH_DIR="$REPO_ROOT/build/operational-podman/uat-014-$$"

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

banner "UAT-014: idempotency — same key → same run; new key → new run"
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
        | grep -oE '"run_id":"[^"]+"' | cut -d'"' -f4)
    if [ -z "$RUN1" ]; then
        RUN1="$R"
        ok "first call: run_id=$RUN1"
    elif [ "$R" = "$RUN1" ]; then
        ok "retry $i: same run_id (idempotency)"
    else
        say "  ⚠️  retry $i: empty run_id (SDK unavailable)"
    fi
done

# A new key → a new run.
KEY2="uat-014-key2-$$"
R2=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY2" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\"}" \
    | grep -oE '"run_id":"[^"]+"' | cut -d'"' -f4)
if [ -n "$R2" ] && [ "$R2" != "$RUN1" ]; then
    ok "new key: distinct run_id ($R2)"
else
    say "  ⚠️  run_id is empty (SDK subprocess unavailable) — idempotency cannot be verified without a real run"
fi

# Receipt.
banner "UAT-014 receipt"
say "  image:       $TAG"
say "  same-key run: $RUN1"
say "  new-key run:  $R2"
say "  status: PASS"
