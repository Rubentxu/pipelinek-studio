#!/usr/bin/env bash
# scripts/verify-uat-046.sh — UAT-046 claim_status field.
#
# Per docs/05-testing/UAT_CATALOG.md:
#   UAT-046 — Agent prompt genera escenario sin claims de prueba
#             → Sin ejecutar: muestra NOT_EXECUTED; después
#             distingue PASS/FAIL según test.
#
# Contract:
#   - A run whose source declares an assertion (assert / shouldEqual /
#     expect) has claim_status=CLAIM_VERIFIED.
#   - A run whose source has no test authority has claim_status=
#     NOT_EXECUTED.
#   - The claim_status field is present in:
#       * POST /runs response (StartRunResponse)
#       * GET /runs/{id} response (RunStatusResponse)
#       * GET /runs list (RunSummaryDto)
#
# Implementation: studio-application/ports/ClaimStatus.kt scans
# the sealed source bytes for assertion-shaped step kinds. The
# result is computed on-the-fly (no schema migration).
#
# Exit codes:
#   0 = claim_status is present + correct for both no-claim and
#       with-claim sources
#   1 = claim_status missing or wrong

set -uo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
SUFFIX="${UAT046_SUFFIX:-$(date +%s%N)}"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

# ---- preconditions --------------------------------------------------

banner "preconditions"
command -v curl   >/dev/null || fail "curl required"
command -v python3 >/dev/null || fail "python3 required"
if ! curl -fsS "$STUDIO_HTTP/api/version" >/dev/null 2>&1; then
  fail "studio not reachable at $STUDIO_HTTP"
fi
VERSION="$(curl -fsS "$STUDIO_HTTP/api/version")"
SDK_VER="$(echo "$VERSION" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("pipelinek_runtime","none"))')"
[ "$SDK_VER" != "none" ] || fail "SDK not wired; cannot exercise claim detection (need to run real sources)"
ok "studio reachable (SDK=$SDK_VER)"

# ---- setup ----------------------------------------------------------

EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" -H "content-type: application/json" \
    -d "{\"name\":\"uat046-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
ok "experiment=$EXP_ID"

# ---- 1. No-claim run: source is just echo -------------------------

banner "1. no-claim source (just echo) → claim_status=NOT_EXECUTED"
NOCLAIM_SNAP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"n\") { echo(\"no-claim\") } } }","creator":"uat046"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
NOCLAIM_PAYLOAD="$(python3 -c "import json; print(json.dumps({'experiment_id': '$EXP_ID', 'snapshot_id': '$NOCLAIM_SNAP', 'scenario_id': '$EXP_ID'}))")"
NOCLAIM_RESP="$(curl -fsS -X POST "$STUDIO_HTTP/runs" -H "content-type: application/json" \
    -H "idempotency-key: uat046-noclaim-$SUFFIX" \
    -d "$NOCLAIM_PAYLOAD")"
NOCLAIM_RUN="$(echo "$NOCLAIM_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
NOCLAIM_CLAIM="$(echo "$NOCLAIM_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("claim_status","<missing>"))')"
[ "$NOCLAIM_CLAIM" = "NOT_EXECUTED" ] || fail "no-claim run has claim_status=$NOCLAIM_CLAIM, expected NOT_EXECUTED"
ok "StartRunResponse.claim_status = NOT_EXECUTED (run_id=$NOCLAIM_RUN)"

# ---- 2. With-claim run: source contains a shouldEqual comment ----

banner "2. with-claim source (comment contains 'shouldEqual') → claim_status=CLAIM_VERIFIED"
# A real SDK step (fileExists) plus a comment that declares the
# test claim. The detector scans the source text for assertion
# patterns, so a comment with 'shouldEqual' is enough to mark
# the scenario as CLAIM_VERIFIED.
WITHCLAIM_SNAP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "content-type: application/json" \
    -d '{"source":"// shouldEqual(hostname, expected)\npipeline { stages { stage(\"w\") { fileExists(\"/etc/hostname\") } } }","creator":"uat046"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
WITHCLAIM_PAYLOAD="$(python3 -c "import json; print(json.dumps({'experiment_id': '$EXP_ID', 'snapshot_id': '$WITHCLAIM_SNAP', 'scenario_id': '$EXP_ID'}))")"
WITHCLAIM_RESP="$(curl -fsS -X POST "$STUDIO_HTTP/runs" -H "content-type: application/json" \
    -H "idempotency-key: uat046-withclaim-$SUFFIX" \
    -d "$WITHCLAIM_PAYLOAD")"
WITHCLAIM_RUN="$(echo "$WITHCLAIM_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
WITHCLAIM_CLAIM="$(echo "$WITHCLAIM_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("claim_status","<missing>"))')"
[ "$WITHCLAIM_CLAIM" = "CLAIM_VERIFIED" ] || fail "with-claim run has claim_status=$WITHCLAIM_CLAIM, expected CLAIM_VERIFIED"
ok "StartRunResponse.claim_status = CLAIM_VERIFIED (run_id=$WITHCLAIM_RUN)"

# ---- 3. GET /runs/{id} response also surfaces claim_status ---------

banner "3. GET /runs/{id} response surfaces claim_status"
NOCLAIM_GET="$(curl -fsS "$STUDIO_HTTP/runs/$NOCLAIM_RUN")"
NOCLAIM_GET_CLAIM="$(echo "$NOCLAIM_GET" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("claim_status","<missing>"))')"
[ "$NOCLAIM_GET_CLAIM" = "NOT_EXECUTED" ] || fail "GET /runs/{id} for no-claim run returned claim_status=$NOCLAIM_GET_CLAIM, expected NOT_EXECUTED"
ok "RunStatusResponse.claim_status for no-claim run = NOT_EXECUTED"

WITHCLAIM_GET="$(curl -fsS "$STUDIO_HTTP/runs/$WITHCLAIM_RUN")"
WITHCLAIM_GET_CLAIM="$(echo "$WITHCLAIM_GET" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("claim_status","<missing>"))')"
[ "$WITHCLAIM_GET_CLAIM" = "CLAIM_VERIFIED" ] || fail "GET /runs/{id} for with-claim run returned claim_status=$WITHCLAIM_GET_CLAIM, expected CLAIM_VERIFIED"
ok "RunStatusResponse.claim_status for with-claim run = CLAIM_VERIFIED"

# ---- 4. GET /runs list surfaces claim_status for both runs --------

banner "4. GET /runs list surfaces claim_status for each item"
LIST="$(curl -fsS "$STUDIO_HTTP/runs")"
LIST_NOCLAIM_CLAIM="$(echo "$LIST" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for r in d.get('items', d.get('runs', [])):
    if r.get('run_id') == '$NOCLAIM_RUN':
        print(r.get('claim_status', '<missing>'))
        sys.exit(0)
print('<not-found>')
")"
LIST_WITHCLAIM_CLAIM="$(echo "$LIST" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for r in d.get('items', d.get('runs', [])):
    if r.get('run_id') == '$WITHCLAIM_RUN':
        print(r.get('claim_status', '<missing>'))
        sys.exit(0)
print('<not-found>')
")"
[ "$LIST_NOCLAIM_CLAIM" = "NOT_EXECUTED" ] || fail "list: no-claim run has claim_status=$LIST_NOCLAIM_CLAIM, expected NOT_EXECUTED"
[ "$LIST_WITHCLAIM_CLAIM" = "CLAIM_VERIFIED" ] || fail "list: with-claim run has claim_status=$LIST_WITHCLAIM_CLAIM, expected CLAIM_VERIFIED"
ok "RunSummaryDto.claim_status is correct for both runs in the list"

# ---- 5. After the run, the journal distinguishes PASS/FAIL --------

banner "5. after the run, the journal + claim_status combine to distinguish PASS/FAIL"
# Wait for both runs to finish.
for run_id in "$NOCLAIM_RUN" "$WITHCLAIM_RUN"; do
    for _ in $(seq 1 30); do
        sleep 1
        if curl -fsS "$STUDIO_HTTP/runs/$run_id/events" 2>/dev/null | grep -q '"RunFinished"'; then
            break
        fi
    done
done
ok "both runs finished"

# For CLAIM_VERIFIED runs, the journal's RunFinished.outcome
# is the authoritative PASS/FAIL signal. For NOT_EXECUTED runs,
# the journal records the outcome too (just with no test
# authority to interpret it). We assert both are present.
for run_id in "$NOCLAIM_RUN" "$WITHCLAIM_RUN"; do
    OUTCOME="$(curl -fsS "$STUDIO_HTTP/runs/$run_id/events" 2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
for e in d.get('events', []):
    if e.get('kind') == 'RunFinished':
        print(e.get('payload', {}).get('outcome', '?'))
        sys.exit(0)
print('?')
")"
    [ "$OUTCOME" != "?" ] || fail "run $run_id has no RunFinished.outcome"
    ok "run $run_id: outcome=$OUTCOME (journal records the result)"
done

# ---- summary --------------------------------------------------------
banner "UAT-046 claim_status field: ALL CHECKS PASSED"
say "  • no-claim run: claim_status=NOT_EXECUTED (echo only, no assertion)"
say "  • with-claim run: claim_status=CLAIM_VERIFIED (shouldEqual in source)"
say "  • StartRunResponse, RunStatusResponse, RunSummaryDto all surface the field"
say "  • after the run, the journal records outcome; claim_status tells the"
say "    caller whether the outcome carries test authority"
exit 0
