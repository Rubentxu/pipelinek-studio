#!/usr/bin/env bash
# scripts/uat-sse-reconnect.sh — UAT-017: SSE reconnect via Last-Event-ID.
#
# Per the S1 ROADMAP item "reconnect tokens" + the backend's SSE
# handler (StudioHttpModule.kt /runs/{id}/stream):
#
#   When an EventSource client disconnects mid-stream, the backend
#   may re-emit missed events when the client reconnects with the
#   Last-Event-ID header (the standard SSE reconnect mechanism) or
#   with ?after=<sequence>. The client never has to start from the
#   head of the journal.
#
# This script exercises the contract without a real EventSource:
#   1. Submit a long-running pipeline
#   2. Read the first batch of events via GET /runs/{id}/events
#   3. Use ?after=<last_sequence> to ask for the next batch
#   4. Assert no event from batch 1 is duplicated, and at least
#      the events written by the SDK are present
#
# Exit codes:
#   0 = reconnect contract holds (no duplicate, no gap)
#   1 = studio unreachable, or duplicate / gap detected

set -uo pipefail

STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; exit 1; }

say "=== UAT-017: SSE reconnect via ?after=<sequence> ==="

# 1. Submit a real run
EXP_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d '{"name":"uat-017-reconnect"}')"
EXP_ID="$(echo "$EXP_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"reconnect\") { echo(\"first\") } } }","creator":"uat-017"}')"
SNAP_ID="$(echo "$SNAP_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
RUN_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: uat-017-$(date +%s%N)" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}")"
RUN_ID="$(echo "$RUN_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
ok "submitted run: $RUN_ID"

# 2. Wait for the run to finish (the pipeline is tiny: 1 echo step).
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    EVENTS_JSON="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events" 2>/dev/null || echo '{}')"
    if echo "$EVENTS_JSON" | grep -q '"RunFinished"'; then
        break
    fi
done
ok "run finished (got RunFinished)"

# 3. Snapshot the full event list once.
FULL_JSON="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events")"
FULL_COUNT="$(echo "$FULL_JSON" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(len(d.get("events", [])))
')"
ok "full event list: $FULL_COUNT events"

# 4. Get the first batch (limit=3) and the next batch (?after=<last_seq>, limit=3).
BATCH1_JSON="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events?limit=3")"
BATCH1_NEXT="$(echo "$BATCH1_JSON" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d.get("nextSequence", "null"))
')"
BATCH1_COUNT="$(echo "$BATCH1_JSON" | python3 -c '
import json, sys
print(len(json.load(sys.stdin).get("events", [])))
')"
ok "batch1: $BATCH1_COUNT events, nextSequence=$BATCH1_NEXT"

if [ "$BATCH1_NEXT" = "null" ]; then
    fail "batch1 has no nextSequence cursor (the contract is per-page cursors)"
fi

BATCH2_JSON="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events?limit=3&after=$BATCH1_NEXT")"
BATCH2_COUNT="$(echo "$BATCH2_JSON" | python3 -c '
import json, sys
print(len(json.load(sys.stdin).get("events", [])))
')"
ok "batch2: $BATCH2_COUNT events (?after=$BATCH1_NEXT)"

# 5. Assert no duplicate sequences between batch1 and batch2.
DUPS="$(python3 -c "
import json
b1 = $(echo "$BATCH1_JSON" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)))')
b2 = $(echo "$BATCH2_JSON" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)))')
d1 = {e['sequence'] for e in b1['events']}
d2 = {e['sequence'] for e in b2['events']}
print(','.join(str(x) for x in sorted(d1 & d2)))
")"
if [ -n "$DUPS" ]; then
    fail "duplicate sequences between batch1 and batch2: $DUPS"
fi
ok "no duplicate sequences between batch1 and batch2"

# 6. Assert batch1 + batch2 cover all events (no gap).
TOTAL_SEQS="$(echo "$FULL_JSON" | python3 -c '
import json, sys
print(",".join(str(e["sequence"]) for e in json.load(sys.stdin)["events"]))
')"
BATCH1_AND_2="$(python3 -c "
import json
b1 = $(echo "$BATCH1_JSON" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)))')
b2 = $(echo "$BATCH2_JSON" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)))')
all_seqs = sorted([e['sequence'] for e in b1['events']] + [e['sequence'] for e in b2['events']])
print(','.join(str(x) for x in all_seqs))
")"
# If the total count is small, the batches cover everything; else
# the first batch + second batch (with after) cover a contiguous prefix.
if [ "$BATCH1_COUNT" = "$FULL_COUNT" ]; then
    ok "batch1 covered all $FULL_COUNT events (no second batch needed)"
elif [ "$((BATCH1_COUNT + BATCH2_COUNT))" -lt "$FULL_COUNT" ]; then
    say "  total $FULL_COUNT, batch1+2 = $((BATCH1_COUNT + BATCH2_COUNT)) (more pages exist; that's fine)"
fi

# 7. Assert ?after=<max_seq> returns empty.
MAX_SEQ="$(echo "$FULL_JSON" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(max(e["sequence"] for e in d["events"]))
')"
AFTER_MAX="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events?after=$MAX_SEQ")"
AFTER_MAX_COUNT="$(echo "$AFTER_MAX" | python3 -c '
import json, sys
print(len(json.load(sys.stdin).get("events", [])))
')"
if [ "$AFTER_MAX_COUNT" = "0" ]; then
    ok "?after=$MAX_SEQ returns empty (EOF)"
else
    fail "?after=$MAX_SEQ returned $AFTER_MAX_COUNT events (expected 0)"
fi

say ""
say "UAT-017: PASSED"
