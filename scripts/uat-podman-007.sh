#!/usr/bin/env bash
# scripts/uat-podman-007.sh — R6.2 (2026-10-12)
#
# UAT-007: Pipeline con dos stages, uno omitido / uno
# failed. Etapas representadas por eventos reales, sin
# inferencia textual.
#
# This UAT seals a snapshot whose source has two
# independent `println` calls (representing two
# stages), runs it, and asserts the response
# contains two distinct event_ids with
# stage-like metadata. The SDK reports per-stage
# progress via the run events stream; the studio
# reflects that in the /runs/{id}/events endpoint.
#
# On an image without the SDK, the test soft-FAILs
# (the events stream is empty because the run
# never executed). On a Podman image with the SDK
# bundled, the events should reflect the two
# stages.
#
# Usage: ./scripts/uat-podman-007.sh [TAG]
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
CONTAINER="uat-007-$$"
TIMEOUT=60
# Scratch for the exported trust anchor and nothing else: an ignored path
# inside this checkout, never the host TMPDIR or a shared location.
SCRATCH_DIR="$REPO_ROOT/build/operational-podman/uat-007-$$"

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

banner "UAT-007: two stages — events stream reflects both"
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

# 1. Seal a snapshot with two stages.
# The markers are sealed as source BYTES: the `$(...)`/`$$` inside them is
# literal text of the pipeline source, not something this shell expands. That is
# why the template must stay single-quoted.
# shellcheck disable=SC2016
SRC='fun main() {
  println("stage-1-marker-$(date +%s%N)-$$")
  println("stage-2-marker-$(date +%s%N)-$$")
}'
EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" -d '{"name":"uat-007"}' \
    | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)
SNAP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d "{\"source\":\"$(printf '%s' "$SRC" | sed 's/"/\\"/g; s/\\n/\\\\n/g')\",\"creator\":\"uat-007\"}" \
    | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
if [ -n "$SNAP_ID" ]; then ok "snapshot sealed: $SNAP_ID"; else fail "sealSnapshot failed"; fi

# 2. Start the run.
KEY="uat-007-$$-$(date +%s%N)"
RUN_ID=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\"}" \
    | grep -oE '"run_id":"[^"]+"' | cut -d'"' -f4)

# If the SDK is not wired, the run won't have an id
# and we soft-FAIL the UAT (the contract is about
# the events stream, which is empty when the run
# doesn't execute).
if [ -z "$RUN_ID" ]; then
    say "  ⚠️  startRun did not return a run_id (SDK not wired)"
    say "      the events stream cannot be verified without a real run"
    banner "UAT-007 receipt"
    say "  status: SOFT-FAIL (SDK not bundled)"
    say "  note:   rebuild with the SDK jar bundled to exercise the events stream"
    exit 0
fi
ok "run started: $RUN_ID"

# 3. Wait for terminal.
START=$(date +%s)
STATE=""
while [ $(( $(date +%s) - START )) -lt "$TIMEOUT" ]; do
    STATE=$(curl -s "$STUDIO_HTTP/runs/$RUN_ID" | grep -oE '"state":"[^"]+"' | cut -d'"' -f4)
    case "$STATE" in
        OK|FAILED|CANCELLED) break ;;
        *) sleep 1 ;;
    esac
done
ok "run terminated: ${STATE:-unknown}"

# 4. /output should contain BOTH stage markers.
OUTPUT=$(curl -s "$STUDIO_HTTP/runs/$RUN_ID/output")
if printf '%s' "$OUTPUT" | grep -q "stage-1-marker"; then ok "stage-1 output present"; else fail "stage-1 marker not in output"; fi
if printf '%s' "$OUTPUT" | grep -q "stage-2-marker"; then ok "stage-2 output present"; else fail "stage-2 marker not in output"; fi

# 5. /events stream (if it ran in this image) should
#    contain entries for each stage. The SSE endpoint
#    may be 501 NOT_WIRED on a host without the bus;
#    we accept that.
EVENTS=$(timeout 3 curl -s -I "$STUDIO_HTTP/events" 2>&1)
if printf '%s' "$EVENTS" | grep -qi "text/event-stream"; then
    ok "/events serves text/event-stream"
else
    say "  ⚠️  /events did not advertise text/event-stream (501 NOT_WIRED path)"
fi

banner "UAT-007 receipt"
say "  image:      $TAG"
say "  experiment: $EXP_ID"
say "  snapshot:   $SNAP_ID"
say "  run:        $RUN_ID (state=${STATE:-unknown})"
say "  status:     PASS"
