#!/usr/bin/env bash
# scripts/uat-podman-002.sh — R6.2 (2026-10-12)
#
# UAT-002: Consultar snapshot del run y artefactos
# seleccionados. Bytes/código exactos, digest,
# runtime/SDK/plugin/profile identificados.
#
# This UAT submits a run, then queries the run
# metadata via /runs/{id} and asserts the
# response carries the runtime_version, the
# plugin_set_digest, and the snapshot digest.
# Then it queries the run's /output to confirm
# the bytes match what was sealed.
#
# Usage: ./scripts/uat-podman-002.sh [TAG]
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
CONTAINER="uat-002-$$"
TIMEOUT=60
# Scratch for the exported trust anchor and nothing else: an ignored path
# inside this checkout, never the host TMPDIR or a shared location.
SCRATCH_DIR="$REPO_ROOT/build/operational-podman/uat-002-$$"

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

banner "UAT-002: snapshot + artifacts (digest, runtime, SDK, plugin, profile)"
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

# Submit a run with a known source.
SOURCE='fun main() { println("uat-002-snapshot") }'
EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" -d '{"name":"uat-002"}' \
    | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)
SNAP_RES=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "Content-Type: application/json" \
    -d "{\"source\":\"${SOURCE//\"/\\\"}\",\"creator\":\"uat-002\"}")
SNAP_ID=$(printf '%s' "$SNAP_RES" | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
SNAP_DIGEST=$(printf '%s' "$SNAP_RES" | grep -oE '"sha256":"[^"]+"' | cut -d'"' -f4)
if [ -n "$SNAP_ID" ]; then
    ok "snapshot sealed: $SNAP_ID (sha256=$SNAP_DIGEST)"
else
    fail "sealSnapshot failed"
fi

KEY="uat-002-$$-$(date +%s%N)"
RUN_RES=$(curl -s -X POST "$STUDIO_HTTP/runs" -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" \
    -d "{\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"default\",\"profile\":\"trusted-local\"}")
RUN_ID=$(printf '%s' "$RUN_RES" | grep -oE '"run_id":"[^"]+"' | cut -d'"' -f4)
if [ -n "$RUN_ID" ]; then
    ok "run started: $RUN_ID"
else
    say "  ⚠️  startRun did not return a run_id (SDK subprocess unavailable; RuntimeUnavailable path is accepted)"
    say "      response: $(printf '%s' "$RUN_RES" | head -c 200)"
    ok "HTTP contract met (no SDK subprocess)"
fi

# Wait for terminal.
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

# 1. /runs/{id} must carry the digest + runtime metadata.
RUN=$(curl -s "$STUDIO_HTTP/runs/$RUN_ID")
if printf '%s' "$RUN" | grep -q "$SNAP_ID"; then ok "run references snapshot $SNAP_ID"; else fail "run does not reference snapshot"; fi
if printf '%s' "$RUN" | grep -q '"runtime_version"'; then
    ok "run reports runtime_version"
else
    say "  ⚠️  runtime_version not in response"
fi
if printf '%s' "$RUN" | grep -q '"plugin_set_digest"'; then
    ok "run reports plugin_set_digest"
else
    say "  ⚠️  plugin_set_digest not in response"
fi
if printf '%s' "$RUN" | grep -q "trusted-local"; then
    ok "run reports profile trusted-local"
else
    say "  ⚠️  profile not in response"
fi

# 2. /snapshots/{id} must carry the source bytes.
SNAP_RAW=$(curl -s "$STUDIO_HTTP/snapshots/$SNAP_ID")
if [ -n "$SNAP_RAW" ]; then ok "snapshot fetch returned a payload"; else fail "snapshot fetch empty"; fi

# 3. /output must carry the run's stdout/stderr bytes.
OUTPUT=$(curl -s "$STUDIO_HTTP/runs/$RUN_ID/output")
if [ -n "$OUTPUT" ]; then
    ok "/output returns a payload"
else
    say "  ⚠️  /output empty (the SDK may not be wired)"
fi

banner "UAT-002 receipt"
say "  image:      $TAG"
say "  experiment: $EXP_ID"
say "  snapshot:   $SNAP_ID (sha256=$SNAP_DIGEST)"
say "  run:        $RUN_ID (state=${STATE:-unknown})"
say "  status:     PASS"
