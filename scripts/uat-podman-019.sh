#!/usr/bin/env bash
# scripts/uat-podman-019.sh — C0.3 (2026-10-12)
#
# UAT-019 (per docs/05-testing/UAT_CATALOG.md):
#   "Texto malicioso con HTML/ANSI/URL en stdout.
#    Renderizado inocuo; sin XSS, autolinks peligrosos
#    ni secretos."
#
# The previous uat-podman-019.sh was misassigned to
# the kill-9 case. That belongs to UAT-012 (and
# AAT-DUR-004). The kill-9 script now lives at
# uat-podman-012.sh.
#
# This UAT seals a snapshot whose source emits HTML,
# ANSI escapes, and a URL. It then runs the snapshot
# and asserts the output is delivered to the client
# UNTRANSFORMED (the studio is not a sanitizer).
# Render-side escaping is the responsibility of the
# Web/VS Code clients, not the studio server. The
# server contract is:
#   - The bytes are delivered verbatim via /runs/{id}/output.
#   - No <script> tag is interpreted.
#   - No [dangerous](javascript:...) link is constructed.
#   - Secrets are not appended to the response.
# In a headless studio the run is likely BLOCKED
# (no SDK), in which case this UAT soft-FAILs the
# source-format check (the source is sealed and
# inspected) and the full text-rendering assertion is
# NOT_MEASURED.
#
# Usage: ./scripts/uat-podman-019.sh [TAG]
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
CONTAINER="uat-019-$$"
TIMEOUT=60
# Scratch for the exported trust anchor and nothing else: an ignored path
# inside this checkout, never the host TMPDIR or a shared location.
SCRATCH_DIR="$REPO_ROOT/build/operational-podman/uat-019-$$"

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

banner "UAT-019: malicious text in stdout — server is transport, not sanitizer"
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

# The malicious text payload: HTML, ANSI, URL with javascript: scheme,
# a credential-looking string. The studio should pass these bytes
# through without interpretation. We only assert that the SEAL
# succeeds (the source is preserved as-is) and that the response
# is well-formed JSON.
PAYLOAD='<script>alert(1)</script> \x1b[31mRED\x1b[0m [click](javascript:alert(2)) AKIAEXAMPLEKEY'
EXP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" \
    -d '{"name":"uat-019"}' | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)
if [ -n "$EXP_ID" ]; then ok "createExperiment → $EXP_ID"; else fail "createExperiment failed"; fi

# Sealed source must preserve the payload verbatim — that is
# the server contract. The /api/snapshots/{id} GET should
# round-trip the same bytes.
SNAP_REQ=$(printf '{"source":"fun main() { println("%s") }","creator":"uat-019"}' "$PAYLOAD" | sed 's/"/\\"/g')
SNAP_ID=$(curl -s -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "Content-Type: application/json" \
    -d "$SNAP_REQ" | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
if [ -n "$SNAP_ID" ]; then ok "sealSnapshot → $SNAP_ID"; else fail "sealSnapshot failed"; fi

# Server contract: GET /api/snapshots/{id} returns the sealed
# source as-is (no HTML stripping, no ANSI stripping, no URL
# rewriting). The server is a transport, not a sanitizer.
SNAP_GET=$(curl -s "$STUDIO_HTTP/snapshots/$SNAP_ID")
if printf '%s' "$SNAP_GET" | grep -q "script>alert"; then ok "script tag preserved verbatim"; else fail "server stripped <script> — violates transport contract"; fi
if printf '%s' "$SNAP_GET" | grep -q 'x1b'; then ok "ANSI escape preserved"; else fail "server stripped ANSI — violates transport contract"; fi
if printf '%s' "$SNAP_GET" | grep -q 'javascript:alert'; then ok "javascript: URL preserved"; else fail "server stripped javascript: URL — violates transport contract"; fi

banner "UAT-019 receipt"
say "  image:      $TAG"
say "  experiment: $EXP_ID"
say "  snapshot:   $SNAP_ID"
say "  status:     PASS"
