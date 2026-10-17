#!/usr/bin/env bash
# scripts/verify-aat-mcp-008.sh — AAT-MCP-008 Tasks extension surface.
#
# Per docs/05-testing/UAT_CATALOG.md (S4 — Mutación adversaria y
# agentes) and AAT_AND_FITNESS.md:
#   AAT-MCP-008 — Tasks extension. Manageable task lists with
#                 real progress, distinguishable from
#                 "started but never reported".
#
# The MCP protocol's Tasks extension (revision 2025-11-25) is
# not yet supported by the SDK pinned in apps/mcp (1.0.4).
# Until the SDK is upgraded, the studio exposes a tool-level
# equivalent: studio.list_tasks surfaces the studio's
# run_bindings as task records (id, kind, state, created_at,
# updated_at, claim_status). This gives MCP clients a way
# to poll for long-running operations without knowing the
# studio's run_id space.
#
# Contract:
#   1. tools/list exposes studio.list_tasks
#   2. studio.list_tasks returns an array of task records
#      with the expected fields
#   3. each task has a real state (CREATED/QUEUED/RUNNING/
#      SUCCEEDED/FAILED/CANCELLED) — no "UNKNOWN" fabrication
#   4. tasks are ordered by created_at DESC (most recent
#      first), matching the studio's /runs contract
#
# Exit codes:
#   0 = task list is exposed + the wire shape holds
#   1 = a contract was violated

set -uo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
MCP_DIR="$REPO_ROOT/apps/mcp"
MCP_DIST="$MCP_DIR/dist/server.js"

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

# ---- helper --------------------------------------------------------

mcp_call() {
    python3 - "$MCP_DIST" "$STUDIO_HTTP" "$1" "$2" "$3" <<'PYEOF'
import subprocess, os, json, sys
mcp, studio, method, params_s, id_s = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
params = json.loads(params_s) if params_s else {}
req = {'jsonrpc': '2.0', 'id': int(id_s), 'method': method, 'params': params}
line = json.dumps(req) + '\n'
proc = subprocess.Popen(['node', mcp], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    env={**os.environ, 'STUDIO_HTTP': studio})
try:
    out, err = proc.communicate(input=line.encode(), timeout=10)
    sys.stdout.write(out.decode().strip())
except subprocess.TimeoutExpired:
    proc.kill()
    proc.communicate()
    sys.stderr.write('timeout')
PYEOF
}

# ---- 1. tools/list exposes studio.list_tasks ------------------------

banner "1. tools/list exposes studio.list_tasks"
TOOLS_JSON="$(mcp_call "tools/list" '{}' 1)"
HAS_TASK_TOOL="$(echo "$TOOLS_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
tools = [t['name'] for t in d.get('result', {}).get('tools', [])]
print('yes' if 'studio.list_tasks' in tools else 'no')
")"
[ "$HAS_TASK_TOOL" = "yes" ] || fail "MCP server is missing studio.list_tasks (AAT-MCP-008 surface)"
ok "MCP server exposes studio.list_tasks"

# ---- 2. list_tasks returns a task array -----------------------------

banner "2. studio.list_tasks returns a task array with the expected fields"
LIST_JSON="$(mcp_call "tools/call" '{"name":"studio.list_tasks","arguments":{"limit":5}}' 2)"
TASK_PAYLOAD="$(echo "$LIST_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for c in d.get('result', {}).get('content', []):
    if c.get('type') == 'text':
        t = c.get('text', '')
        if t.startswith('HTTP '):
            t = t.split(': ', 1)[1] if ': ' in t else t
        print(t)
        break
")"
TASK_COUNT="$(echo "$TASK_PAYLOAD" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(len(d.get('tasks', [])))
")"
[ "$TASK_COUNT" -ge 1 ] || fail "studio.list_tasks returned 0 tasks (expected at least 1)"
ok "studio.list_tasks returned $TASK_COUNT task record(s)"

# ---- 3. each task has the expected fields ---------------------------

banner "3. each task has the expected fields (id, kind, state, created_at, updated_at, claim_status)"
TASK_FIELDS_OK="$(echo "$TASK_PAYLOAD" | python3 -c "
import json, sys
d = json.load(sys.stdin)
required = ('id', 'kind', 'state', 'created_at', 'updated_at', 'claim_status')
problems = []
for t in d.get('tasks', []):
    for f in required:
        if f not in t:
            problems.append(f\"{t.get('id','?')}: missing {f}\")
print('none' if not problems else '\n'.join(problems))
")"
[ "$TASK_FIELDS_OK" = "none" ] || fail "task records missing required fields: $TASK_FIELDS_OK"
ok "every task record has all required fields (id, kind, state, created_at, updated_at, claim_status)"

# ---- 4. task states are real, not fabricated -----------------------

banner "4. task states are real (not 'UNKNOWN' / fabricated)"
ALLOWED_STATES="CREATED QUEUED STARTING RUNNING SUCCEEDED FAILED CANCELLED REFUSED RECOVERY_REQUIRED LOST"
UNREAL="$(echo "$TASK_PAYLOAD" | python3 -c "
import json, sys
d = json.load(sys.stdin)
allowed = set('${ALLOWED_STATES}'.split())
bad = []
for t in d.get('tasks', []):
    s = t.get('state', '?')
    if s not in allowed:
        bad.append(f\"{t.get('id','?')}: {s}\")
print('none' if not bad else '\n'.join(bad))
")"
[ "$UNREAL" = "none" ] || fail "task records carry unreal / fabricated states: $UNREAL"
ok "every task state is a real RunState (no fabrication)"

# ---- 5. task list is ordered by created_at DESC --------------------

banner "5. task list is ordered by created_at DESC (most recent first)"
ORDERED="$(echo "$TASK_PAYLOAD" | python3 -c "
import json, sys
d = json.load(sys.stdin)
ts = [t.get('created_at','') for t in d.get('tasks', [])]
sorted_desc = sorted(ts, reverse=True)
print('yes' if ts == sorted_desc else 'no')
")"
[ "$ORDERED" = "yes" ] || fail "task list is not ordered by created_at DESC"
ok "task list is ordered by created_at DESC (matches /runs contract)"

# ---- 6. cross-check: studio.list_tasks reflects /runs ---------------

banner "6. cross-check: studio.list_tasks reflects /runs"
# Get the /runs list and the list_tasks list. The first task
# in list_tasks should match the first run in /runs (both
# ordered by created_at DESC).
WEB_FIRST_ID="$(curl -fsS "$STUDIO_HTTP/runs?limit=1" | python3 -c "
import json, sys
d = json.load(sys.stdin)
items = d.get('items', d.get('runs', []))
print(items[0].get('run_id', '') if items else '')
")"
TASK_FIRST_ID="$(echo "$TASK_PAYLOAD" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(d.get('tasks', [{}])[0].get('id', ''))
")"
[ "$WEB_FIRST_ID" = "$TASK_FIRST_ID" ] || fail "list_tasks first entry ($TASK_FIRST_ID) does not match /runs first entry ($WEB_FIRST_ID)"
ok "list_tasks first entry matches /runs first entry (same ordering, same content)"

# ---- 7. optional state filter --------------------------------------

banner "7. optional state filter: list_tasks with state=SUCCEEDED returns only that state"
SUCC_JSON="$(mcp_call "tools/call" '{"name":"studio.list_tasks","arguments":{"state":"SUCCEEDED","limit":20}}' 7)"
SUCC_PAYLOAD="$(echo "$SUCC_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for c in d.get('result', {}).get('content', []):
    if c.get('type') == 'text':
        t = c.get('text', '')
        if t.startswith('HTTP '):
            t = t.split(': ', 1)[1] if ': ' in t else t
        print(t)
        break
")"
SUCC_STATES="$(echo "$SUCC_PAYLOAD" | python3 -c "
import json, sys
d = json.load(sys.stdin)
states = set(t.get('state','?') for t in d.get('tasks', []))
print(','.join(sorted(states)) if states else 'empty')
")"
# Either no SUCCEEDED runs exist (empty) or all returned states are SUCCEEDED.
[ "$SUCC_STATES" = "empty" ] || [ "$SUCC_STATES" = "SUCCEEDED" ] \
    || fail "state=SUCCEEDED filter returned non-matching states: $SUCC_STATES"
ok "state=SUCCEEDED filter returns only SUCCEEDED tasks (or empty when none exist)"

# ---- summary --------------------------------------------------------
banner "AAT-MCP-008 Tasks extension surface: ALL CHECKS PASSED"
say "  • tools/list exposes studio.list_tasks"
say "  • list_tasks returns $TASK_COUNT task record(s) with the expected fields"
say "  • every task has id, kind, state, created_at, updated_at, claim_status"
say "  • every task state is a real RunState (no fabrication)"
say "  • task list is ordered by created_at DESC"
say "  • list_tasks reflects /runs (first entries match)"
say "  • state filter works (state=SUCCEEDED returns only that state or empty)"
say ""
say "Note: this is a tool-level implementation of the Tasks"
say "concept. The MCP protocol's Tasks extension (revision"
say "2025-11-25) is a future SDK upgrade. The studio's"
say "run_bindings are surfaced as task records so MCP clients"
say "can poll for progress without knowing the run_id space."
exit 0
