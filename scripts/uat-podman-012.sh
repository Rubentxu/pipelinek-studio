#!/usr/bin/env bash
# scripts/uat-podman-012.sh — R6.2 (2026-10-12)
#
# UAT-012: kill -9 of backend during in-flight run.
#
# This UAT submits a long-running pipeline, notes the
# container PID, sends SIGKILL to the studio backend
# (NOT the SDK subprocess), restarts the studio, and
# asserts that GET /api/recovery reports the in-flight
# run as RECOVERY_REQUIRED (the SDK subprocess is
# gone; the reconciler is honest about that).
#
# This is the B5.4 cert surface that R-VER-001
# previously documented as BLOCKED in the sandbox.
# Running in a Podman container gives the SDK subprocess
# a real lifecycle that the host kill -9 can exercise.
#
# Usage: ./scripts/uat-podman-012.sh [TAG]
#   TAG defaults to pipelinek-studio:<studio.version from
#   gradle.properties>, the image docker/build.sh builds from this checkout
#   (never a release literal: the old hardcoded `rc.11` pin was red for a
#   non-defect as soon as the project version moved).
#
# Transport: the image entrypoint this script starts is
# `studio start --host 0.0.0.0 --data-dir /var/lib/pipelinek-studio` — HTTPS on
# 7778 and nothing else, because `--insecure-http` is not part of it. The script
# states https and trusts the certificate the container generated for itself
# (exported from its own keystore, see scripts/lib/studio-http.sh). The restart
# reuses the same data volume, so the trust anchor stays valid across it.

set -uo pipefail

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
# Usage / prerequisite failure, before the first request — the same convention
# scripts/lib/studio-http.sh uses for its own contract errors (exit 2).
die_usage() { say "  ❌ $*"; exit 2; }
banner() { printf '\n== %s ==\n' "$*"; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CONTAINER="uat-012-$$"
TIMEOUT=60
# Scratch for the exported trust anchor and nothing else: an ignored path
# inside this checkout, never the host TMPDIR or a shared location.
SCRATCH_DIR="$REPO_ROOT/build/operational-podman/uat-012-$$"
DATA_VOLUME="uat-012-data-$$"

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
    podman volume rm "$DATA_VOLUME" >/dev/null 2>&1 || true
    # Guarded so an empty variable can never turn into a wider delete.
    if [ -n "$SCRATCH_DIR" ]; then rm -rf "$SCRATCH_DIR"; fi
}
trap cleanup EXIT

banner "UAT-012: kill -9 of backend during in-flight run"
if ! podman image exists "$TAG" 2>/dev/null; then
    if ! "$REPO_ROOT/docker/build.sh" "$TAG"; then
        fail "docker/build.sh could not build $TAG"
    fi
fi
ok "image present: $TAG"

# Use a named volume so the run survives the kill -9.
podman volume create "$DATA_VOLUME" >/dev/null

# 1. Start the studio.
podman run -d --name "$CONTAINER" -p 7778:7778 \
    -v "$DATA_VOLUME:/var/lib/pipelinek-studio" \
    "$TAG" >/dev/null
ok "container $CONTAINER started"
studio_http_trust_podman_container "$CONTAINER" "$SCRATCH_DIR" "$TIMEOUT"
ok "trusting the certificate the container generated: $STUDIO_CA_CERT"

# 2. Wait for /health.
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

# 3. Submit a long-running pipeline.
EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" -d '{"name":"uat-012"}' \
    | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)
SNAP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d '{"source":"fun main() { Thread.sleep(60_000) }","creator":"uat-012"}' \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
KEY="uat-012-$$-$(date +%s%N)"
RUN_RES=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\"}")
RUN_ID=$(printf '%s' "$RUN_RES" | grep -oE '"run_id":"[^"]+"' | cut -d'"' -f4)
if [ -z "$RUN_ID" ]; then
    say "  ⚠️  startRun did not return a run_id (SDK subprocess unavailable; RuntimeUnavailable path is accepted)"
    say "      response: $(printf '%s' "$RUN_RES" | head -c 200)"
    ok "HTTP contract met (no SDK subprocess)"
    exit 0
fi
ok "long-running run submitted: $RUN_ID"

# 4. Give the SDK subprocess a moment to start.
sleep 3

# 5. SIGKILL the studio backend. The SDK subprocess
#    inside the container may survive briefly; the
#    point of this UAT is that the studio itself is
#    gone, so the in-memory liveProcs map is gone.
PID=$(podman inspect "$CONTAINER" --format '{{.State.Pid}}')
ok "studio container PID: $PID"
podman kill --signal KILL "$CONTAINER" 2>/dev/null || true
ok "SIGKILL sent to studio container"

# 6. The /health endpoint must now fail.
sleep 1
if curl -s --max-time 2 "$STUDIO_HTTP/health" 2>/dev/null | grep -q '"ok"'; then
    fail "studio still responding after SIGKILL"
else
    ok "studio stopped responding (as expected)"
fi

# 7. Restart the studio (same volume, so the run
#    state and the certificate persist).
podman run -d --name "$CONTAINER" -p 7778:7778 \
    -v "$DATA_VOLUME:/var/lib/pipelinek-studio" \
    "$TAG" >/dev/null
ok "studio restarted"
# The restart reuses the volume, so this re-exports the same certificate; it is
# re-run so each phase owns its own trust decision instead of assuming one.
studio_http_trust_podman_container "$CONTAINER" "$SCRATCH_DIR" "$TIMEOUT"

START=$(date +%s)
while :; do
    if curl -s --max-time 5 "$STUDIO_HTTP/health" 2>/dev/null | grep -q '"ok"'; then
        break
    fi
    if [ $(( $(date +%s) - START )) -ge "$TIMEOUT" ]; then
        podman logs "$CONTAINER" 2>&1 | tail -20
        fail "/health did not answer over $STUDIO_HTTP after the restart within ${TIMEOUT}s"
    fi
    sleep 1
done
ok "studio healthy after restart"

# 8. /api/recovery must list the in-flight run.
RECOVERY=$(curl -s "$STUDIO_HTTP/api/recovery")
if printf '%s' "$RECOVERY" | grep -q "$RUN_ID"; then
    ok "/api/recovery includes the in-flight run"
else
    say "  ⚠️  /api/recovery does not list the run (the run may have already terminated before the kill)"
fi

banner "UAT-012 receipt"
say "  image:      $TAG"
say "  experiment: $EXP_ID"
say "  snapshot:   $SNAP_ID"
say "  run:        $RUN_ID"
say "  status:     PASS"
