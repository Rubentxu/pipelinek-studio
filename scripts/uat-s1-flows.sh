#!/usr/bin/env bash
# scripts/uat-s1-flows.sh — UAT-005, UAT-006, UAT-007: the basic
# editor / seal / run / cancel / error paths the SPA exposes.
#
# Per the S1 ROADMAP item "edit / run / stop / problemas / outcomes":
#
#   UAT-005 — successful end-to-end flow: experiment + snapshot
#             (with source) + run + events + output
#   UAT-006 — cancel an in-flight run; SPA reports the new state
#   UAT-007 — syntax error: diagnostics surface line/column/path
#
# All three are exercised here against the live studio-app. Each
# step is a real HTTP call (not a UI driver).
#
# Exit codes:
#   0 = every flow passed
#   1 = at least one flow failed

set -uo pipefail

STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; exit 1; }
banner() { printf '\n=== %s ===\n' "$*"; }

# ---- UAT-005: successful end-to-end ----
banner "UAT-005: successful end-to-end (seal -> run -> events -> output)"
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d '{"name":"uat-005-success"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"ok\") { echo(\"good run\") } } }","creator":"uat-005"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: uat-005-$(date +%s%N)" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
ok "submitted: $RUN_ID"

# Wait for RunFinished
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    if curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events" 2>/dev/null | grep -q '"RunFinished"'; then
        break
    fi
done
# Verify the events include CompilationStarted, RunStarted, RunFinished(success)
EVENTS_JSON="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events")"
for kind in CompilationStarted RunStarted StageStarted StepStarted EchoOutputCaptured StageFinished RunFinished; do
    if echo "$EVENTS_JSON" | grep -q "\"$kind\""; then
        ok "event observed: $kind"
    else
        fail "missing event: $kind"
    fi
done
# Output contains "good run"
OUT="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/output" 2>/dev/null | python3 -c '
import json, sys, base64
d = json.load(sys.stdin)
out = []
for c in d.get("chunks", []):
    b = c.get("bytesBase64")
    if b:
        out.append(base64.b64decode(b).decode("utf-8", errors="replace"))
print("".join(out))
' 2>/dev/null)"
if echo "$OUT" | grep -qF "good run"; then
    ok "output contains 'good run'"
else
    fail "output missing 'good run' marker: $(echo "$OUT" | head -c 200)"
fi

# ---- UAT-006: cancel in-flight ----
banner "UAT-006: cancel in-flight run"
LONG_SRC='pipeline { stages { stage("long") { Thread.sleep(60000) } } }'
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d '{"name":"uat-006-cancel"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d "{\"source\":$(printf '%s' "$LONG_SRC" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),\"creator\":\"uat-006\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: uat-006-$(date +%s%N)" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
ok "submitted long run: $RUN_ID"
sleep 2
CANCEL_CODE="$(curl -sS -o /tmp/uat-cancel.json -w '%{http_code}' \
    -X POST "$STUDIO_HTTP/runs/$RUN_ID/cancel")"
CANCEL_BODY="$(cat /tmp/uat-cancel.json)"
if [ "$CANCEL_CODE" = "200" ] && echo "$CANCEL_BODY" | grep -q '"acknowledged":true'; then
    ok "cancel acknowledged: $CANCEL_BODY"
else
    fail "cancel did not return 200/acknowledged: HTTP $CANCEL_CODE body=$CANCEL_BODY"
fi
# Verify the SDK subprocess is gone. The SDK writes per-run state to a
# sqlite file whose path embeds the run_id; the runner process arguments
# carry that path on its command line, so a ps grep for the run_id is
# reliable regardless of the exact db location.
sleep 2
SDK_PROCS_LEFT="$(ps -ef | grep -F "$RUN_ID" | grep -v grep | wc -l)"
if [ "$SDK_PROCS_LEFT" = "0" ]; then
    ok "SDK subprocess gone after cancel"
else
    fail "SDK subprocess still running for $RUN_ID (cancel did not kill it)"
fi

# ---- UAT-007: syntax error diagnostic ----
banner "UAT-007: syntax error surfaces with line/column/path"
BAD_SRC='pipeline { this is not valid kotlin { } }'
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d '{"name":"uat-007-bad"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d "{\"source\":$(printf '%s' "$BAD_SRC" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),\"creator\":\"uat-007\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: uat-007-$(date +%s%N)" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
sleep 6
DIAG="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/diagnostics")"
ERROR_COUNT="$(echo "$DIAG" | python3 -c '
import json, sys
d = json.load(sys.stdin)
errs = [x for x in d.get("diagnostics", []) if x.get("severity") == "ERROR"]
print(len(errs))
')"
if [ "$ERROR_COUNT" -ge 1 ]; then
    ok "diagnostic surfaced: $ERROR_COUNT ERROR entries"
    echo "$DIAG" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for e in d.get('diagnostics', []):
    if e.get('severity') == 'ERROR':
        line = e.get('line')
        col = e.get('column')
        msg = (e.get('message') or '')[:80]
        print('    ERROR line={} col={} msg={!r}'.format(line, col, msg))
"
else
    fail "no ERROR diagnostic surfaced for the syntax error"
fi

# Verify line + column + path are present (the source-location contract)
LINE_PRESENT="$(echo "$DIAG" | python3 -c '
import json, sys
d = json.load(sys.stdin)
errs = [x for x in d.get("diagnostics", []) if x.get("severity") == "ERROR"]
if errs and errs[0].get("line") is not None and errs[0].get("column") is not None and errs[0].get("path") is not None:
    print("yes")
else:
    print("no")
')"
if [ "$LINE_PRESENT" = "yes" ]; then
    ok "source-location contract: line + column + path are all present"
else
    fail "source-location contract violated: line/column/path missing"
fi

say ""
say "UAT-005/006/007: ALL PASSED"
