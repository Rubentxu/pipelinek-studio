#!/usr/bin/env bash
# S1 E1.6 — From-distribution UAT scaffold.
#
# The Studio backend (`studio-app` per the distTar pipeline) exposes
# the full HTTP surface. This script exercises the end-to-end flow as
# a real operator would: create experiment -> seal snapshot -> start
# run -> poll events -> read output -> read diagnostics.
#
# The script is the S1 E1.6 deliverable: a single bash file that
# asserts the documented surface is reachable, returns the documented
# shapes, and never invents a passing verdict.
#
# Run against an installed distribution (a running `studio-app`):
#
#   $ STUDIO_HTTP=http://localhost:7777 ./scripts/uat-from-distribution.sh
#
# Exit codes:
#   0 = every step passed
#   1 = at least one step failed (UAT not ready)
#   2 = prerequisite missing (no studio-app, no curl, no jq)

set -euo pipefail

STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"
PRETTY=0
[ "${1:-}" = "--pretty" ] && PRETTY=1

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; exit 1; }
banner() { printf '\n=== %s ===\n' "$*"; }

need() {
    command -v "$1" >/dev/null 2>&1 || { say "❌ missing: $1"; exit 2; }
}
need curl

# 0. Liveness
banner "0. Liveness"
HEALTH="$(curl -fsS "${STUDIO_HTTP}/health" || true)"
[ "$HEALTH" = "ok" ] || fail "/health returned '$HEALTH' (expected 'ok')"
ok "/health -> ok"

# 1. Version contract
banner "1. Version contract"
VERSION_JSON="$(curl -fsS "${STUDIO_HTTP}/api/version")"
echo "$VERSION_JSON" | grep -q '"version"' || fail "/api/version missing version field: $VERSION_JSON"
STUDIO_VERSION="$(echo "$VERSION_JSON" | grep -oE '"version":"[^"]+"' | head -1 | cut -d'"' -f4)"
[ -n "$STUDIO_VERSION" ] || fail "could not parse studio version"
ok "studio version: $STUDIO_VERSION"

# 2. OpenAPI reachable + has the documented paths
banner "2. OpenAPI contract"
OPENAPI="$(curl -fsS "${STUDIO_HTTP}/openapi.json")"
echo "$OPENAPI" | grep -q '"openapi": "3.1.0"' || fail "openapi doc is not 3.1.0"
for path in /health /api/version /experiments /runs /steps; do
    echo "$OPENAPI" | grep -q "\"$path\"" || fail "openapi doc missing path $path"
done
ok "openapi.json reachable + 5/5 sampled paths present"

# 3. Create experiment
banner "3. Create experiment"
EXP_NAME="uat-$(date +%s)"
EXP_BODY="$(curl -fsS -X POST "${STUDIO_HTTP}/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"$EXP_NAME\"}")"
EXP_ID="$(echo "$EXP_BODY" | grep -oE '"experiment_id":"[^"]+"' | head -1 | cut -d'"' -f4)"
[ -n "$EXP_ID" ] || fail "create experiment returned no id: $EXP_BODY"
ok "experiment: $EXP_ID"

# 4. Seal snapshot
banner "4. Seal snapshot"
SNAP_BODY="$(curl -fsS -X POST "${STUDIO_HTTP}/experiments/${EXP_ID}/snapshots" \
    -H "content-type: application/json" \
    -d "{\"source\":\"package dev.pipelinek.studio.uat\\n\\nfun main() = println(\\\"uat\\\")\",\"creator\":\"uat-scaffold\"}")"
SNAP_ID="$(echo "$SNAP_BODY" | grep -oE '"snapshot_id":"[^"]+"' | head -1 | cut -d'"' -f4)"
SHA="$(echo "$SNAP_BODY" | grep -oE '"sha256":"[^"]+"' | head -1 | cut -d'"' -f4)"
[ -n "$SNAP_ID" ] || fail "seal snapshot returned no id: $SNAP_BODY"
ok "snapshot: $SNAP_ID  sha256: ${SHA:0:16}…"

# 5. Run start + read (idempotency-key required)
banner "5. Run submit"
IDEM_KEY="uat-$(date +%s)-$RANDOM"
RUN_BODY="$(curl -fsS -X POST "${STUDIO_HTTP}/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: $IDEM_KEY" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}")"
RUN_ID="$(echo "$RUN_BODY" | grep -oE '"run_id":"[^"]+"' | head -1 | cut -d'"' -f4)"
[ -n "$RUN_ID" ] || fail "submit run returned no id: $RUN_BODY"
ok "run: $RUN_ID  state: $(echo "$RUN_BODY" | grep -oE '"state":"[^"]+"' | head -1 | cut -d'"' -f4)"

# 6. Run inspect
banner "6. Run inspect"
INSPECT="$(curl -fsS "${STUDIO_HTTP}/runs/${RUN_ID}" || true)"
echo "$INSPECT" | grep -q "$RUN_ID" || fail "inspect returned unexpected body: $INSPECT"
ok "inspect ok"

# 7. Events list (may be empty if the SDK didn't materialize yet)
banner "7. Events list"
EVENTS="$(curl -fsS "${STUDIO_HTTP}/runs/${RUN_ID}/events" || true)"
echo "$EVENTS" | grep -qE '(\[\]|READ_PORT_UNAVAILABLE|events)' || fail "events body unexpected: $EVENTS"
ok "events list ok (body shape verified)"

# 8. Output — 200 with chunks OR 202 with documented refusal
banner "8. Output read"
OUT_CODE="$(curl -sS -o /tmp/uat-out.json -w '%{http_code}' "${STUDIO_HTTP}/runs/${RUN_ID}/output")"
case "$OUT_CODE" in
    200) ok "output: 200 (real bytes)" ;;
    202) grep -qE 'OUTPUT_REFUSED|OUTPUT_UNKNOWN_STREAM|OUTPUT_RECOVERY_REQUIRED' /tmp/uat-out.json \
              || fail "202 output body has no documented refusal code: $(cat /tmp/uat-out.json)"
          ok "output: 202 + documented refusal" ;;
    *)   fail "output returned HTTP $OUT_CODE (expected 200 or 202)" ;;
esac

# 9. Diagnostics + IR
banner "9. Diagnostics + IR"
for ep in diagnostics ir; do
    CODE="$(curl -sS -o /tmp/uat-$ep.json -w '%{http_code}' "${STUDIO_HTTP}/runs/${RUN_ID}/$ep")"
    case "$CODE" in
        200) ok "$ep: 200" ;;
        202) grep -qE 'DIAGNOSTICS_UNAVAILABLE|IR_UNAVAILABLE' /tmp/uat-$ep.json \
                  || fail "$ep 202 body has no documented refusal: $(cat /tmp/uat-$ep.json)"
              ok "$ep: 202 + documented refusal" ;;
        *)   fail "$ep returned HTTP $CODE" ;;
    esac
done

# 10. Step catalog
banner "10. Step catalog"
STEPS="$(curl -fsS "${STUDIO_HTTP}/steps" || true)"
echo "$STEPS" | grep -qE '(steps|REFUSED|code)' || fail "steps body unexpected: $STEPS"
ok "steps list ok"

banner "ALL UAT STEPS PASSED"
say "From-distribution UAT: READY"
say "Created: experiment=$EXP_ID snapshot=$SNAP_ID run=$RUN_ID"
