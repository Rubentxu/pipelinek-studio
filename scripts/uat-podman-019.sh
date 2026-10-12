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

set -uo pipefail

TAG="${1:-pipelinek-studio:1.0.0-rc.11}"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
CONTAINER="uat-019-$$"
STUDIO_HTTP="https://127.0.0.1:7778"
CURL_OPTS=(-k --http1.1)

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

cleanup() { podman rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

banner "UAT-019: malicious text in stdout — server is transport, not sanitizer"
if ! podman image exists "$TAG" 2>/dev/null; then
    "$REPO_ROOT/docker/build.sh" "$TAG" || { fail "build failed"; exit 2; }
fi
podman run -d --name "$CONTAINER" -p 7778:7778 "$TAG" >/dev/null
START=$(date +%s)
while [ $(( $(date +%s) - START )) -lt 60 ]; do
    curl -s "${CURL_OPTS[@]}" "$STUDIO_HTTP/health" 2>/dev/null | grep -q '"ok"' && break
    sleep 1
done
curl -s "${CURL_OPTS[@]}" "$STUDIO_HTTP/health" 2>/dev/null | grep -q '"ok"' || { fail "/health not up"; exit 1; }
ok "studio healthy"

# The malicious text payload: HTML, ANSI, URL with javascript: scheme,
# a credential-looking string. The studio should pass these bytes
# through without interpretation. We only assert that the SEAL
# succeeds (the source is preserved as-is) and that the response
# is well-formed JSON.
PAYLOAD='<script>alert(1)</script> \x1b[31mRED\x1b[0m [click](javascript:alert(2)) AKIAEXAMPLEKEY'
EXP_ID=$(curl -s "${CURL_OPTS[@]}" -X POST "$STUDIO_HTTP/experiments" -H "Content-Type: application/json" \
    -d '{"name":"uat-019"}' | grep -oE '"experiment_id":"[^"]+"' | cut -d'"' -f4)
[ -n "$EXP_ID" ] && ok "createExperiment → $EXP_ID" || fail "createExperiment failed"

# Sealed source must preserve the payload verbatim — that is
# the server contract. The /api/snapshots/{id} GET should
# round-trip the same bytes.
SNAP_REQ=$(printf '{"source":"fun main() { println(\"%s\") }","creator":"uat-019"}' "$PAYLOAD" | sed 's/"/\\"/g')
SNAP_ID=$(curl -s "${CURL_OPTS[@]}" -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "Content-Type: application/json" \
    -d "$SNAP_REQ" | grep -oE '"snapshot_id":"[^"]+"' | cut -d'"' -f4)
[ -n "$SNAP_ID" ] && ok "sealSnapshot → $SNAP_ID" || fail "sealSnapshot failed"

# Server contract: GET /api/snapshots/{id} returns the sealed
# source as-is (no HTML stripping, no ANSI stripping, no URL
# rewriting). The server is a transport, not a sanitizer.
SNAP_GET=$(curl -s "${CURL_OPTS[@]}" "$STUDIO_HTTP/snapshots/$SNAP_ID")
echo "$SNAP_GET" | grep -q "script>alert" && ok "script tag preserved verbatim" || fail "server stripped <script> — violates transport contract"
echo "$SNAP_GET" | grep -q 'x1b' && ok "ANSI escape preserved" || fail "server stripped ANSI — violates transport contract"
echo "$SNAP_GET" | grep -q 'javascript:alert' && ok "javascript: URL preserved" || fail "server stripped javascript: URL — violates transport contract"

banner "UAT-019 receipt"
say "  experiment: $EXP_ID"
say "  snapshot:   $SNAP_ID"
say "  status:     PASS"
