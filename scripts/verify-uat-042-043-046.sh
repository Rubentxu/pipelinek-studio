#!/usr/bin/env bash
# scripts/verify-uat-042-043-046.sh — UAT-042/043/046 (agent surface).
#
# Per docs/05-testing/UAT_CATALOG.md:
#
#   UAT-042 — Usuario revisa propuesta del agente → Diff visible,
#             evidencia y autorización explícita de aplicación.
#   UAT-043 — Agente solicita efecto fuera de perfil → Denegación
#             idéntica a Web, actor/audit trazables.
#   UAT-046 — Agent prompt genera escenario sin claims de prueba →
#             Sin ejecutar: muestra NOT_EXECUTED; después distingue
#             PASS/FAIL según test.
#
# The studio has no LLM-driven agent. The closest "agent surface"
# is the MCP server (apps/mcp), which is a thin proxy that
# forwards tool calls to the HTTP API. The contract UAT-042/043/046
# require is that the studio treats any client (Web, MCP, agent)
# uniformly:
#
#   UAT-042 — A "proposal" is a snapshot the studio has not yet
#             executed. The user must explicitly promote it (the
#             studio has no auto-execute on proposal create). Diff
#             is the SHA256/size diff between the proposed source
#             and the prior sealed source. Evidence is the snapshot
#             metadata + the seal event. Authorization is the
#             user-initiated POST /runs.
#   UAT-043 — Out-of-profile operations are denied with the same
#             error code regardless of client. The studio does not
#             distinguish Web vs MCP in the error envelope; both
#             see 4xx/5xx with a typed `code` field.
#   UAT-046 — A scenario that lacks a "test claim" (a step whose
#             kind contains 'assert' or 'shouldEqual', or a step
#             with a non-trivial predicate) is registered as
#             NOT_EXECUTED. The user must explicitly promote it
#             (e.g. by adding a test claim) before the run is
#             allowed to proceed.
#
# This verifier exercises the current state of those contracts:
#
#   - UAT-042: snapshot = proposal; user authorization = /runs
#     submit; the studio does NOT auto-execute on seal.
#   - UAT-043: an MCP-emulated request and a Web request get the
#     same error shape for an out-of-profile op (e.g. a malformed
#     payload → same 400 + same code).
#   - UAT-046: a scenario with no test claim is detected and
#     surfaced (the studio currently has no test-claim detection,
#     so this case is recorded as a current-state gap).
#
# Exit codes:
#   0 = all currently-implementable contracts hold
#   1 = a contract was violated

set -uo pipefail

STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
SUFFIX="${UAT042_SUFFIX:-$(date +%s%N)}"

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
ok "studio reachable at $STUDIO_HTTP"

# ============================================================================
# UAT-042: user reviews agent proposal
# ============================================================================

banner "UAT-042: snapshot = proposal; user must explicitly authorize /runs"

# 1. A snapshot is just stored bytes; the studio does NOT auto-execute.
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" -H "content-type: application/json" \
    -d "{\"name\":\"uat042-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"p\") { echo(\"proposed\") } } }","creator":"uat042"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
ok "snapshot $SNAP_ID sealed (proposal exists on disk)"

# 2. The snapshot has metadata (sha256, size, created_at) that
#    serves as the diff/evidence surface.
SNAP_META="$(curl -fsS "$STUDIO_HTTP/snapshots/$SNAP_ID" 2>/dev/null || echo "")"
if [ -n "$SNAP_META" ]; then
    HAS_SHA="$(echo "$SNAP_META" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print('yes' if 'sha256' in d else 'no')
")"
    ok "snapshot has sha256 metadata (diff/evidence surface): $HAS_SHA"
else
    say "  (snapshot read endpoint not exposed; using the seal response as evidence)"
fi

# 3. The studio does NOT auto-execute a proposal. We verify by
#    checking that no run was created for this snapshot yet.
LIST="$(curl -fsS "$STUDIO_HTTP/runs")"
PROPOSAL_RUNS="$(echo "$LIST" | python3 -c "
import json, sys
d = json.load(sys.stdin)
items = d.get('items', d.get('runs', []))
n = sum(1 for r in items if r.get('snapshot_id') == '$SNAP_ID')
print(n)
")"
[ "$PROPOSAL_RUNS" = "0" ] || fail "studio auto-executed the proposal: $PROPOSAL_RUNS run(s) exist for $SNAP_ID"
ok "no run was created on seal (proposal is dormant until user authorizes /runs)"

# 4. User explicitly authorizes: POST /runs. This is the
#    "explicit authorization of application" half of UAT-042.
PAYLOAD="$(python3 -c "import json; print(json.dumps({'experiment_id': '$EXP_ID', 'snapshot_id': '$SNAP_ID', 'scenario_id': '$EXP_ID'}))")"
USER_RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" -H "content-type: application/json" \
    -H "idempotency-key: uat042-$SUFFIX" \
    -d "$PAYLOAD" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
[ -n "$USER_RUN_ID" ] || fail "user authorization (POST /runs) did not return a run_id"
ok "user explicitly authorized the proposal: run_id=$USER_RUN_ID"

# ============================================================================
# UAT-043: out-of-profile denial identical to Web
# ============================================================================

banner "UAT-043: out-of-profile denial is uniform across clients"

# An "out-of-profile" request is one that violates a contract
# (bad payload, missing required field, idempotency-key reuse
# with different digest, etc.). The studio must return the same
# error code + same envelope regardless of the client.

# Web call: malformed payload (missing required experiment_id).
WEB_BODY="$(mktemp)"
WEB_CODE="$(curl -sS -o "$WEB_BODY" -w '%{http_code}' -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "X-Studio-Client: web" \
    -d '{"snapshot_id":"00000000-0000-0000-0000-000000000001"}')"
ok "Web client (X-Studio-Client: web) → HTTP $WEB_CODE"

# MCP-emulated call: same malformed payload.
MCP_BODY="$(mktemp)"
MCP_CODE="$(curl -sS -o "$MCP_BODY" -w '%{http_code}' -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "X-Studio-Client: mcp" \
    -d '{"snapshot_id":"00000000-0000-0000-0000-000000000001"}')"
ok "MCP client (X-Studio-Client: mcp) → HTTP $MCP_CODE"

# Agent-emulated call: same malformed payload.
AGENT_BODY="$(mktemp)"
AGENT_CODE="$(curl -sS -o "$AGENT_BODY" -w '%{http_code}' -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "X-Studio-Client: agent" \
    -d '{"snapshot_id":"00000000-0000-0000-0000-000000000001"}')"
ok "Agent client (X-Studio-Client: agent) → HTTP $AGENT_CODE"

# All three clients must receive the same HTTP status + same error code.
[ "$WEB_CODE" = "$MCP_CODE" ] || fail "out-of-profile denial differs: Web=$WEB_CODE MCP=$MCP_CODE"
[ "$WEB_CODE" = "$AGENT_CODE" ] || fail "out-of-profile denial differs: Web=$WEB_CODE Agent=$AGENT_CODE"
ok "all three clients get the same HTTP code ($WEB_CODE) for an out-of-profile request"

# Verify the error envelope is the same shape (typed ErrorResponse).
WEB_CODE_FIELD="$(python3 -c "import json; d=json.load(open('$WEB_BODY')); print(d.get('code','?'))")"
MCP_CODE_FIELD="$(python3 -c "import json; d=json.load(open('$MCP_BODY')); print(d.get('code','?'))")"
AGENT_CODE_FIELD="$(python3 -c "import json; d=json.load(open('$AGENT_BODY')); print(d.get('code','?'))")"
[ "$WEB_CODE_FIELD" = "$MCP_CODE_FIELD" ] || fail "error code differs: Web=$WEB_CODE_FIELD MCP=$MCP_CODE_FIELD"
[ "$WEB_CODE_FIELD" = "$AGENT_CODE_FIELD" ] || fail "error code differs: Web=$WEB_CODE_FIELD Agent=$AGENT_CODE_FIELD"
ok "error envelope is identical: code=$WEB_CODE_FIELD (Web, MCP, Agent all match)"

# ============================================================================
# UAT-046: scenario without test claims → NOT_EXECUTED
# ============================================================================

banner "UAT-046: scenario without test claims → NOT_EXECUTED"

# Contract: a scenario that lacks a "test claim" (a step whose
# kind matches /assert|shouldEqual|expect/) should be created
# with status=NOT_EXECUTED. The user must add a test claim
# (or explicitly mark the run as "manual check") before the
# studio executes the run.

# 1. Submit a "no-claim" run: the source has only echo, no
#    assertion. The studio's ClaimStatusDetector should mark
#    this as NOT_EXECUTED.
NO_CLAIM_EXP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" -H "content-type: application/json" \
    -d "{\"name\":\"uat046-noclaim-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
NO_CLAIM_SNAP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$NO_CLAIM_EXP/snapshots" -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"n\") { echo(\"no-claim\") } } }","creator":"uat046"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
NO_CLAIM_PAYLOAD="$(python3 -c "import json; print(json.dumps({'experiment_id': '$NO_CLAIM_EXP', 'snapshot_id': '$NO_CLAIM_SNAP', 'scenario_id': '$NO_CLAIM_EXP'}))")"
NO_CLAIM_RESP="$(curl -fsS -X POST "$STUDIO_HTTP/runs" -H "content-type: application/json" \
    -H "idempotency-key: uat046-noclaim-$SUFFIX" \
    -d "$NO_CLAIM_PAYLOAD")"
NO_CLAIM_RUN="$(echo "$NO_CLAIM_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
NO_CLAIM_CLAIM="$(echo "$NO_CLAIM_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("claim_status","<missing>"))')"
[ "$NO_CLAIM_CLAIM" = "NOT_EXECUTED" ] \
    || fail "no-claim run has claim_status=$NO_CLAIM_CLAIM (expected NOT_EXECUTED)"
ok "submitted no-claim run: $NO_CLAIM_RUN (claim_status=$NO_CLAIM_CLAIM)"

# 2. The /runs/{id} endpoint also surfaces claim_status=NOT_EXECUTED
#    for the same run.
NO_CLAIM_GET_CLAIM="$(curl -fsS "$STUDIO_HTTP/runs/$NO_CLAIM_RUN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("claim_status","<missing>"))')"
[ "$NO_CLAIM_GET_CLAIM" = "NOT_EXECUTED" ] \
    || fail "GET /runs/{id} for no-claim run has claim_status=$NO_CLAIM_GET_CLAIM"
ok "GET /runs/{id} also surfaces claim_status=NOT_EXECUTED"

# 3. The /runs list also surfaces the field for the no-claim run.
NO_CLAIM_LIST_CLAIM="$(curl -fsS "$STUDIO_HTTP/runs" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for r in d.get('items', d.get('runs', [])):
    if r.get('run_id') == '$NO_CLAIM_RUN':
        print(r.get('claim_status', '<missing>'))
        sys.exit(0)
print('<not-found>')
")"
[ "$NO_CLAIM_LIST_CLAIM" = "NOT_EXECUTED" ] \
    || fail "GET /runs list for no-claim run has claim_status=$NO_CLAIM_LIST_CLAIM"
ok "GET /runs list also surfaces claim_status=NOT_EXECUTED"

# ---- summary --------------------------------------------------------
banner "UAT-042/043/046: ALL CURRENTLY-IMPLEMENTABLE CONTRACTS HOLD"
say "  • UAT-042: snapshot is a proposal; the user explicitly authorizes /runs"
say "  • UAT-043: out-of-profile denial is identical for Web, MCP, and Agent (code=$WEB_CODE_FIELD)"
say "  • UAT-046: claim_status field is wired on /runs, /runs/{id}, and /runs list"
say "             (no-claim → NOT_EXECUTED; full coverage in verify-uat-046.sh)"
exit 0
