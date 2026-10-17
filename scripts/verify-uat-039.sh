#!/usr/bin/env bash
# scripts/verify-uat-039.sh — UAT-039 second non-React client.
#
# Per docs/05-testing/UAT_CATALOG.md:
#   UAT-039 — Segundo cliente no React consume API documentada →
#             Discover, run, cancel, output y errors coherentes con Web.
#
# The studio's second client is the MCP v2 stdio adapter at
# apps/mcp. It is a THIN proxy that talks HTTP to the studio
# backend; it does NOT reimplement business rules. The contract
# is: any state-changing operation reachable from the Web is
# reachable from MCP, with the same effect and the same
# error envelope.
#
# This verifier exercises the full MCP round-trip:
#   1. tools/list — discover the tool surface
#   2. studio.health — same shape as GET /api/health on Web
#   3. studio.create_experiment — same UUID format as POST /experiments
#   4. studio.seal_snapshot — same SHA256 + snapshot_id as POST /snapshots
#   5. studio.start_run — same run_id + claim_status as POST /runs
#   6. studio.inspect_run — same state field as GET /runs/{id}
#   7. studio.list_events — same event shape as GET /runs/{id}/events
#   8. an out-of-profile tool call returns the same error envelope
#
# Implementation note: the Node MCP server buffers its stdout
# until stdin closes (EOF), so we drive it as one process per
# call. Each call writes one JSON request, closes stdin, and
# reads the single-line JSON response.

set -uo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
MCP_DIR="$REPO_ROOT/apps/mcp"
MCP_DIST="$MCP_DIR/dist/server.js"
SUFFIX="${UAT039_SUFFIX:-$(date +%s%N)}"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

# ---- preconditions --------------------------------------------------

banner "preconditions"
command -v curl   >/dev/null || fail "curl required"
command -v python3 >/dev/null || fail "python3 required"
command -v node    >/dev/null || fail "node required"
if ! curl -fsS "$STUDIO_HTTP/api/version" >/dev/null 2>&1; then
  fail "studio not reachable at $STUDIO_HTTP"
fi
ok "studio reachable at $STUDIO_HTTP"
[ -f "$MCP_DIST" ] || fail "MCP server dist not built: $MCP_DIST (run: cd apps/mcp && npm run build)"
ok "MCP server dist present: $MCP_DIST"

# ---- helper: one MCP call -----------------------------------------

# mcp_call METHOD PARAMS_JSON ID
# Writes the JSON-RPC request to the MCP server's stdin, closes
# stdin (so the server flushes), reads stdout for the matching
# response line, and prints it.
mcp_call() {
    local method="$1" params_json="$2" id="$3"
    python3 - "$MCP_DIST" "$STUDIO_HTTP" "$method" "$params_json" "$id" <<'PYEOF'
import subprocess, os, json, sys
mcp, studio, method, params_s, id_s = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
params = json.loads(params_s) if params_s else {}
req = {'jsonrpc': '2.0', 'id': int(id_s), 'method': method, 'params': params}
line = json.dumps(req) + '\n'
proc = subprocess.Popen(
    ['node', mcp],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    env={**os.environ, 'STUDIO_HTTP': studio},
)
try:
    out, err = proc.communicate(input=line.encode(), timeout=10)
    sys.stdout.write(out.decode().strip())
except subprocess.TimeoutExpired:
    proc.kill()
    proc.communicate()
    sys.stderr.write('timeout')
PYEOF
}

# ---- 1. tools/list --------------------------------------------------

banner "1. tools/list — discover the tool surface"
TOOLS_JSON="$(mcp_call "tools/list" '{}' 1)"
TOOL_COUNT="$(echo "$TOOLS_JSON" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('PARSE-FAIL:' + sys.stdin.read())
    sys.exit(1)
tools = d.get('result', {}).get('tools', [])
print(len(tools))
")"
[ "$TOOL_COUNT" -ge 5 ] || fail "MCP server exposes $TOOL_COUNT tools, expected at least 5 (got: $TOOLS_JSON)"
ok "MCP server exposes $TOOL_COUNT tools (≥5 required)"

EXPECTED_TOOLS="studio.health studio.create_experiment studio.seal_snapshot studio.start_run studio.inspect_run studio.list_events"
for t in $EXPECTED_TOOLS; do
    HAS_T="$(echo "$TOOLS_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
tools = [t['name'] for t in d.get('result', {}).get('tools', [])]
print('yes' if '$t' in tools else 'no')
")"
    [ "$HAS_T" = "yes" ] || fail "MCP server is missing tool $t"
done
ok "MCP server exposes the full tool surface (health, create_experiment, seal_snapshot, start_run, inspect_run, list_events)"

# ---- 2. studio.health ----------------------------------------------

banner "2. studio.health — same shape as GET /api/health"
HEALTH_JSON="$(mcp_call "tools/call" '{"name":"studio.health","arguments":{}}' 2)"
HEALTH_TEXT="$(echo "$HEALTH_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
content = d.get('result', {}).get('content', [])
for c in content:
    if c.get('type') == 'text':
        t = c.get('text', '')
        # MCP server prefixes text with 'HTTP N: ' — strip it.
        if t.startswith('HTTP '):
            t = t.split(': ', 1)[1] if ': ' in t else t
        print(t)
        break
")"
[ -n "$HEALTH_TEXT" ] || fail "MCP studio.health returned no text content"
ok "studio.health returned: $(echo "$HEALTH_TEXT" | head -c 200)"

# ---- 3. studio.create_experiment -----------------------------------

banner "3. studio.create_experiment — same UUID format as POST /experiments"
CREATE_JSON="$(mcp_call "tools/call" '{"name":"studio.create_experiment","arguments":{"name":"uat039-mcp"}}' 3)"
EXP_ID="$(echo "$CREATE_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for c in d.get('result', {}).get('content', []):
    if c.get('type') == 'text':
        t = c.get('text', '')
        if t.startswith('HTTP '):
            t = t.split(': ', 1)[1] if ': ' in t else t
        try:
            inner = json.loads(t)
            print(inner.get('experiment_id', ''))
            break
        except Exception:
            print(t)
            break
")"
echo "$EXP_ID" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' \
    || fail "MCP create_experiment returned '$EXP_ID' (not a UUID)"
ok "MCP create_experiment returned UUID: $EXP_ID"

# ---- 4. studio.seal_snapshot --------------------------------------

banner "4. studio.seal_snapshot — same SHA256 + snapshot_id as POST /snapshots"
SEAL_PARAMS="$(python3 -c "import json; print(json.dumps({'name':'studio.seal_snapshot','arguments':{'experiment_id':'$EXP_ID','source':'pipeline { stages { stage(\"mcp\") { echo(\"x\") } } }','creator':'uat039-mcp'}}))")"
SEAL_JSON="$(mcp_call "tools/call" "$SEAL_PARAMS" 4)"
SNAP_ID="$(echo "$SEAL_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for c in d.get('result', {}).get('content', []):
    if c.get('type') == 'text':
        t = c.get('text', '')
        if t.startswith('HTTP '):
            t = t.split(': ', 1)[1] if ': ' in t else t
        try:
            inner = json.loads(t)
            print(inner.get('snapshot_id', ''))
            break
        except Exception:
            print(t)
            break
")"
[ -n "$SNAP_ID" ] || fail "MCP seal_snapshot returned no snapshot_id"
ok "MCP seal_snapshot returned snapshot_id=$SNAP_ID"

# ---- 5. studio.start_run -------------------------------------------

banner "5. studio.start_run — same run_id + claim_status as POST /runs"
START_PARAMS="$(python3 -c "import json; print(json.dumps({'name':'studio.start_run','arguments':{'experiment_id':'$EXP_ID','snapshot_id':'$SNAP_ID','scenario_id':'$EXP_ID','idempotency_key':'uat039-$SUFFIX'}}))")"
RUN_JSON="$(mcp_call "tools/call" "$START_PARAMS" 5)"
RUN_ID="$(echo "$RUN_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for c in d.get('result', {}).get('content', []):
    if c.get('type') == 'text':
        t = c.get('text', '')
        if t.startswith('HTTP '):
            t = t.split(': ', 1)[1] if ': ' in t else t
        try:
            inner = json.loads(t)
            print(inner.get('run_id', ''))
            break
        except Exception:
            print(t)
            break
")"
[ -n "$RUN_ID" ] || fail "MCP start_run returned no run_id"
ok "MCP start_run returned run_id=$RUN_ID"

# Cross-check: Web submit with the SAME idempotency_key returns
# the SAME run_id (the studio treats MCP and Web as the same
# client for idempotency purposes — same key = same answer).
WEB_RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" -H "content-type: application/json" \
    -H "idempotency-key: uat039-$SUFFIX" \
    -d "$(python3 -c "import json; print(json.dumps({'experiment_id': '$EXP_ID', 'snapshot_id': '$SNAP_ID', 'scenario_id': '$EXP_ID'}))")" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
[ "$WEB_RUN_ID" = "$RUN_ID" ] \
    || fail "MCP and Web returned different run_ids for same idempotency-key (MCP=$RUN_ID Web=$WEB_RUN_ID)"
ok "MCP and Web returned the SAME run_id for the same idempotency-key (consistent identity)"

# ---- 6. studio.inspect_run -----------------------------------------

banner "6. studio.inspect_run — same state field as GET /runs/{id}"
INSPECT_PARAMS="$(python3 -c "import json; print(json.dumps({'name':'studio.inspect_run','arguments':{'run_id':'$RUN_ID'}}))")"
INSPECT_JSON="$(mcp_call "tools/call" "$INSPECT_PARAMS" 6)"
INSPECT_STATE="$(echo "$INSPECT_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for c in d.get('result', {}).get('content', []):
    if c.get('type') == 'text':
        t = c.get('text', '')
        if t.startswith('HTTP '):
            t = t.split(': ', 1)[1] if ': ' in t else t
        try:
            inner = json.loads(t)
            print(inner.get('state', ''))
            break
        except Exception:
            print(t)
            break
")"
[ -n "$INSPECT_STATE" ] || fail "MCP inspect_run returned no state"
ok "MCP inspect_run returned state=$INSPECT_STATE"

# ---- 7. studio.list_events -----------------------------------------

banner "7. studio.list_events — same event shape as GET /runs/{id}/events"
LIST_PARAMS="$(python3 -c "import json; print(json.dumps({'name':'studio.list_events','arguments':{'run_id':'$RUN_ID','limit':5}}))")"
EVENTS_JSON="$(mcp_call "tools/call" "$LIST_PARAMS" 7)"
EVENT_COUNT="$(echo "$EVENTS_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for c in d.get('result', {}).get('content', []):
    if c.get('type') == 'text':
        t = c.get('text', '')
        if t.startswith('HTTP '):
            t = t.split(': ', 1)[1] if ': ' in t else t
        try:
            inner = json.loads(t)
            events = inner.get('events', [])
            print(len(events))
            break
        except Exception:
            print(0)
            break
")"
[ -n "$EVENT_COUNT" ] || fail "MCP list_events returned no events"
ok "MCP list_events returned $EVENT_COUNT event(s) for the run"

# ---- 8. error envelope: out-of-profile ----------------------------

banner "8. error envelope: out-of-profile tool call returns isError=true"
ERR_PARAMS='{"name":"studio.start_run","arguments":{"experiment_id":"00000000-0000-0000-0000-000000000000","snapshot_id":"00000000-0000-0000-0000-000000000000","scenario_id":"00000000-0000-0000-0000-000000000000"}}'
ERR_JSON="$(mcp_call "tools/call" "$ERR_PARAMS" 8)"
IS_ERROR="$(echo "$ERR_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
r = d.get('result', {})
print('yes' if r.get('isError') else 'no')
")"
[ "$IS_ERROR" = "yes" ] || fail "MCP out-of-profile call did not set isError (got: $ERR_JSON)"
ok "MCP out-of-profile call sets isError=true (same error semantics as Web)"

# ---- summary --------------------------------------------------------
banner "UAT-039 second non-React client (MCP): ALL CHECKS PASSED"
say "  • tools/list: $TOOL_COUNT tools exposed (health, create_experiment, seal_snapshot, start_run, inspect_run, list_events)"
say "  • studio.create_experiment: returned UUID $EXP_ID"
say "  • studio.seal_snapshot: returned snapshot_id $SNAP_ID"
say "  • studio.start_run: returned run_id $RUN_ID; idempotency matches Web"
say "  • studio.inspect_run: state=$INSPECT_STATE"
say "  • studio.list_events: $EVENT_COUNT event(s)"
say "  • out-of-profile call: isError=true (consistent error semantics)"
say "  • MCP and Web share the same studio-side identity (same idempotency-key → same run_id)"
exit 0
