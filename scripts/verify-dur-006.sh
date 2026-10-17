#!/usr/bin/env bash
# scripts/verify-dur-006.sh — AAT-DUR-006 retention feature verifier.
#
# Per docs/05-testing/AAT_AND_FITNESS.md:
#   AAT-DUR-006 — Retención/cleanup solo elimina runs terminales sin
#                 lease vivo; crash cleanup recuperable.
#
# Per ROADMAP §"S5 — Product Ready": the retention service is a real
# feature, not just a current-state absence. The studio exposes
#   POST /api/admin/retention
# which walks `<controlRoot>/<runId>/`, opens each `run.db`, and
# deletes the per-run dir ONLY when
#   (a) the run is terminal (RunFinished event present), AND
#   (b) the runner does not report a live subprocess for this run_id.
#
# This verifier creates two synthetic per-run control-root dirs with
# hand-crafted run.db files, then exercises the endpoint and checks
# the post-conditions:
#   1. terminal run with NO live subprocess → deleted
#   2. non-terminal run → kept (not-terminal reason)
#   3. the report shape lists each scanned run with the right reason
#
# The "terminal run WITH a live subprocess" case is covered by the
# RetentionService unit tests (RetentionServiceTest.kt) because
# faking a live subprocess binding from the shell is not possible —
# the runner's liveProcs map is the only authoritative source.
#
# Exit codes:
#   0 = retention sweep honoured all contracts
#   1 = a contract was violated
#   2 = preconditions not met (studio not running, jq missing, etc.)

set -uo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
STUDIO_DATA_DIR="${STUDIO_DATA_DIR:-$HOME/.pipelinek-studio/runtime}"
CONTROL_ROOT="$STUDIO_DATA_DIR/runs"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

# ---- preconditions --------------------------------------------------

banner "preconditions"
command -v curl   >/dev/null || fail "curl required"
command -v jq     >/dev/null || fail "jq required"
command -v python3 >/dev/null || fail "python3 required"
if ! curl -fsS "$STUDIO_HTTP/api/version" >/dev/null 2>&1; then
  fail "studio not reachable at $STUDIO_HTTP — start it first"
fi
ok "studio reachable at $STUDIO_HTTP"
[ -d "$CONTROL_ROOT" ] || fail "control root does not exist: $CONTROL_ROOT"
ok "control root: $CONTROL_ROOT"

# ---- setup: build two synthetic per-run control-root dirs -----------

# Two flavours:
#   1) terminal-no-live  → should be DELETED
#   2) non-terminal      → should be KEPT (not-terminal reason)

UUID1='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'
UUID2='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2'

RUN_TERMINAL="$CONTROL_ROOT/$UUID1"
RUN_INFLIGHT="$CONTROL_ROOT/$UUID2"

# Clean up any prior artifacts from a previous run.
rm -rf "$RUN_TERMINAL" "$RUN_INFLIGHT"
mkdir -p "$RUN_TERMINAL" "$RUN_INFLIGHT"

# Build run.db files via python3 (always present in our env). The
# schema mirrors the SDK's SqliteEventStore events table.
export DB_TERMINAL="$RUN_TERMINAL/run.db"
export DB_INFLIGHT="$RUN_INFLIGHT/run.db"
python3 <<'PY'
import os, sqlite3
SCHEMA = """
CREATE TABLE events (
    event_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    kind TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    payload TEXT NOT NULL
);
"""
def write_terminal(path):
    con = sqlite3.connect(path)
    con.executescript(SCHEMA)
    con.execute("INSERT INTO events VALUES (?,?,?,?,?,?)",
                ('e1', 'r', 1, 'RunStarted', '2026-01-01T00:00:00Z', '{}'))
    con.execute("INSERT INTO events VALUES (?,?,?,?,?,?)",
                ('e2', 'r', 2, 'RunFinished', '2026-01-01T00:00:01Z', '{"outcome":"success"}'))
    con.commit(); con.close()
def write_inflight(path):
    con = sqlite3.connect(path)
    con.executescript(SCHEMA)
    con.execute("INSERT INTO events VALUES (?,?,?,?,?,?)",
                ('e1', 'r', 1, 'RunStarted', '2026-01-01T00:00:00Z', '{}'))
    con.commit(); con.close()
write_terminal(os.environ['DB_TERMINAL'])
write_inflight(os.environ['DB_INFLIGHT'])
PY

ok "synthetic runs created:"
say "    terminal-no-live  : $UUID1"
say "    non-terminal      : $UUID2"

# ---- exercise: POST /api/admin/retention -----------------------------

banner "POST /api/admin/retention"
RESP_FILE=$(mktemp)
HTTP_CODE=$(curl -sS -o "$RESP_FILE" -w '%{http_code}' -X POST "$STUDIO_HTTP/api/admin/retention")
[ "$HTTP_CODE" = "200" ] || fail "expected 200, got $HTTP_CODE: $(cat "$RESP_FILE")"
ok "endpoint returned 200"
say "    body head: $(head -c 400 "$RESP_FILE")..."

# ---- post-conditions: per-run dir state ------------------------------

banner "post-condition: terminal run was DELETED"
[ ! -d "$RUN_TERMINAL" ] || fail "terminal run dir still exists: $RUN_TERMINAL"
ok "terminal-no-live run is gone"

banner "post-condition: non-terminal run was KEPT"
[ -d "$RUN_INFLIGHT" ] || fail "non-terminal run was deleted (should have been kept)"
ok "non-terminal run still on disk"

# ---- post-conditions: report shape -----------------------------------

banner "post-condition: report shape"
INFLIGHT_KEPT_REASON=$(jq -r --arg id "$UUID2" '.runsKept[] | select(.runId == $id) | .reason' < "$RESP_FILE")
[ -n "$INFLIGHT_KEPT_REASON" ] || fail "UUID2 missing from runsKept"
[ "$INFLIGHT_KEPT_REASON" = "not-terminal" ] || fail "UUID2 reason was '$INFLIGHT_KEPT_REASON', expected 'not-terminal'"
ok "kept run UUID2 has reason 'not-terminal'"

TERMINAL_PRESENT=$(jq -r --arg id "$UUID1" '.runsKept | map(select(.runId == $id)) | length' < "$RESP_FILE")
[ "$TERMINAL_PRESENT" = "0" ] || fail "UUID1 (deleted) is in runsKept — should not be"
ok "deleted run UUID1 is NOT in runsKept"

# ---- cleanup ---------------------------------------------------------
rm -f "$RESP_FILE"

banner "AAT-DUR-006 retention: ALL CONTRACTS HOLD"
say "  terminal-without-live run → deleted"
say "  non-terminal run → kept with not-terminal reason"
say "  report shape correct (deleted runs absent, kept runs present with reason)"
exit 0
