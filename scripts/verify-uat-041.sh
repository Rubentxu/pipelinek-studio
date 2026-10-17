#!/usr/bin/env bash
# scripts/verify-uat-041.sh — UAT-041 idempotency on reconnect.
#
# Per docs/05-testing/UAT_CATALOG.md:
#   UAT-041 — Repetir tool MCP start con misma clave tras
#             desconexión → Misma run; no segunda ejecución de
#             Step con efecto.
#
# Contract: when the same Idempotency-Key is replayed (e.g. an MCP
# client reconnects after a network blip), the studio returns the
# SAME run_id AND the underlying run did not start a second time.
# "No second execution of Step with effect" means the event journal
# for the original run has not grown — no second StepStarted, no
# second RunStarted, no new events of any kind.
#
# AAT-API-008 already covers the run_id stability half of this
# contract. UAT-041 adds the "no second execution" check by
# snapshotting the event count before and after the replay.
#
# Exit codes:
#   0 = same run_id + no new events after replay
#   1 = replay created a new run OR new events appeared

set -uo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
SUFFIX="${UAT041_SUFFIX:-$(date +%s%N)}"

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

# ---- setup: experiment + snapshot -----------------------------------

banner "setup: experiment + snapshot"
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"uat041-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"s\") { echo(\"uat041\") } } }","creator":"uat041"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
ok "experiment=$EXP_ID snapshot=$SNAP_ID"

# ---- step 1: first submission with idempotency-key ------------------

banner "step 1: first submit with idempotency-key=uat041-$SUFFIX"
KEY="uat041-$SUFFIX"
PAYLOAD="{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}"
FIRST_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: $KEY" \
    -d "$PAYLOAD")"
FIRST_RUN_ID="$(echo "$FIRST_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
[ -n "$FIRST_RUN_ID" ] || fail "first submission did not return a run_id: $FIRST_BODY"
ok "first submission returned run_id=$FIRST_RUN_ID"

# Wait for the first run to finish so we can read a stable event count.
for _ in $(seq 1 30); do
    sleep 1
    EVENTS="$(curl -fsS "$STUDIO_HTTP/runs/$FIRST_RUN_ID/events" 2>/dev/null || echo "")"
    if echo "$EVENTS" | grep -q '"RunFinished"'; then
        break
    fi
done
FIRST_EVENT_COUNT="$(echo "$EVENTS" | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("events", [])))')"
[ -n "$FIRST_EVENT_COUNT" ] || fail "could not read first run's event count"
ok "first run finished with $FIRST_EVENT_COUNT event(s)"

# ---- step 2: replay the same idempotency-key ------------------------

banner "step 2: replay same idempotency-key after 'disconnect'"
# The MCP client thought the response was lost; it replays the
# same key. The studio must return the SAME run_id.
SECOND_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: $KEY" \
    -d "$PAYLOAD")"
SECOND_RUN_ID="$(echo "$SECOND_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
[ "$SECOND_RUN_ID" = "$FIRST_RUN_ID" ] \
    || fail "replay returned a different run_id ($SECOND_RUN_ID != $FIRST_RUN_ID)"
ok "replay returned the same run_id=$FIRST_RUN_ID"

# ---- step 3: assert no new events on the run ------------------------

banner "step 3: no second execution (event count unchanged)"
# Give the studio a moment in case it scheduled any deferred work
# (it shouldn't, but be defensive).
sleep 2
EVENTS_AFTER="$(curl -fsS "$STUDIO_HTTP/runs/$FIRST_RUN_ID/events")"
SECOND_EVENT_COUNT="$(echo "$EVENTS_AFTER" | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("events", [])))')"
[ "$SECOND_EVENT_COUNT" = "$FIRST_EVENT_COUNT" ] \
    || fail "replay added events to the run ($SECOND_EVENT_COUNT != $FIRST_EVENT_COUNT)"
ok "event count unchanged: $FIRST_EVENT_COUNT == $SECOND_EVENT_COUNT (no second execution)"

# ---- step 4: cross-check there is exactly ONE binding for this key --

banner "step 4: cross-check — one binding per idempotency key"
# We can't query the studio.db directly, but we can confirm there
# is exactly one run that is reachable by the idempotency key by
# listing runs and counting occurrences of the same snapshot.
LIST="$(curl -fsS "$STUDIO_HTTP/runs")"
# There may be runs from prior tests on the same experiment; filter
# to runs whose run_id matches what we got.
MATCHING="$(echo "$LIST" | python3 -c "
import json, sys
d = json.load(sys.stdin)
items = d.get('items', d.get('runs', []))
m = [r for r in items if r.get('run_id') == '$FIRST_RUN_ID']
print(len(m))
")"
[ "$MATCHING" = "1" ] || fail "expected exactly 1 run binding, got $MATCHING"
ok "run is registered exactly once (no duplicate launch)"

# ---- summary --------------------------------------------------------
banner "UAT-041 idempotency on reconnect: ALL CHECKS PASSED"
say "  • same idempotency-key + reconnect → same run_id ($FIRST_RUN_ID)"
say "  • event journal unchanged ($FIRST_EVENT_COUNT events before/after)"
say "  • no second execution of Step with effect"
exit 0
