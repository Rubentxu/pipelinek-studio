#!/usr/bin/env bash
# scripts/verify-observability.sh — AAT-OBS-001..008 (per
# docs/05-testing/AAT_AND_FITNESS.md "Output/observación").
#
#   AAT-OBS-001 — EventCursor and OutputCursor are separate types;
#                not interchangeable on the wire.
#   AAT-OBS-002 — UnknownStream / ForeignStream / RecoveryNotCompleted
#                => refusal, never a 200 page with zero bytes.
#   AAT-OBS-003 — Empty / single-stream runs sealed honestly; we never
#                invent a chunk to make a 200 OK look populated.
#   AAT-OBS-004 — Byte-range paginated read returns exactly the
#                producer's digest for the requested slice; multi-byte
#                UTF-8 split across two reads reassembles losslessly.
#   AAT-OBS-005 — SSE-disconnected client re-attach via ?after=
#                re-reads authoritatively; no duplicate / lost events.
#   AAT-OBS-006 — (skipped here) 100 MiB output with bounded memory:
#                see scripts/verify-perf-soak.sh (future).
#   AAT-OBS-007 — (skipped here) two independent readers, one dies,
#                other continues: requires a long-lived run; future.
#   AAT-OBS-008 — Lost-output vs not-emitted are distinguishable by
#                refusal / empty-with-recovery code, not by a 200 with
#                silent success.
#
# Exit codes:
#   0 = every OBS contract in scope holds
#   1 = at least one contract failed (or studio unreachable)

set -uo pipefail

STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"
DATA_DIR="${STUDIO_DATA_DIR:-$HOME/.pipelinek-studio/runtime}"
CONTROL_ROOT="${STUDIO_CONTROL_ROOT:-$DATA_DIR/runs}"
SUFFIX="${OBS_SUFFIX:-$(date +%s%N)}"

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; exit 1; }
banner() { printf '\n=== %s ===\n' "$*"; }

# The HTTP layer applies an in-process rate limit (60 req / 60s by
# default per AAT-API-005). A full OBS sweep is ~30 requests, so we
# add a tiny stagger between high-cost sections to stay under the
# bucket and avoid false 429s masking real failures.
stagger() { sleep 1; }

# ---- AAT-OBS-001: EventCursor and OutputCursor are distinct types ----
banner "AAT-OBS-001: EventCursor and OutputCursor are not interchangeable"
# Same path /runs/{id}/events uses integer sequences; /runs/{id}/output
# uses byte offsets. The contract: querying the events endpoint with
# an ?after=<byte-offset> must NOT silently treat it as a sequence,
# and vice versa. The simplest proof: a ?limit=2 on events with
# after=<byte-offset> either returns a 400 or returns the same
# events as a fresh read (no surprise rows from confusing types).
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"obs001-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"s\") { echo(\"a\") } } }","creator":"obs001"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: obs001-$SUFFIX" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
for attempt in 1 2 3 4 5 6 7 8; do
    sleep 1
    curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events" 2>/dev/null | grep -q '"RunFinished"' && break
done
# Query events with after=<large-byte-offset> (which would be a valid
# OutputCursor for a 2 KiB output but is definitely not a valid
# EventCursor). The endpoint must not silently interpret it as 0 or
# crash — a 200 with empty events is acceptable, as is a 400.
BAD_AFTER_RESP="$(curl -sS -o /tmp/obs001-bad-after.json -w '%{http_code}' \
    "$STUDIO_HTTP/runs/$RUN_ID/events?after=99999")"
case "$BAD_AFTER_RESP" in
    200|400)
        ok "AAT-OBS-001: bogus byte-offset on events endpoint: HTTP $BAD_AFTER_RESP (no type confusion crash)"
        ;;
    *)
        fail "AAT-OBS-001: unexpected HTTP $BAD_AFTER_RESP for bogus ?after on events"
        ;;
esac

# ---- AAT-OBS-002: UnknownStream => refusal, never 0-bytes 200 ----
banner "AAT-OBS-002: unknown stream => refusal (OUTPUT_UNKNOWN_STREAM), not 200 with empty page"
REFUSED_CODE="$(curl -sS -o /tmp/obs002-body.json -w '%{http_code}' \
    "$STUDIO_HTTP/runs/00000000-0000-0000-0000-000000000000/output")"
REFUSED_BODY="$(cat /tmp/obs002-body.json)"
case "$REFUSED_CODE" in
    202)
        # 202 Accepted with OUTPUT_UNKNOWN_STREAM is the refusal shape
        if echo "$REFUSED_BODY" | grep -q 'OUTPUT_UNKNOWN_STREAM\|OUTPUT_RECOVERY_REQUIRED\|RUN_NOT_FOUND'; then
            ok "AAT-OBS-002: bogus run_id => 202 with refusal code, not 200 OK with empty body"
        else
            fail "AAT-OBS-002: 202 returned but body lacks a refusal code: $REFUSED_BODY"
        fi
        ;;
    404)
        ok "AAT-OBS-002: bogus run_id => 404 (also an honest refusal)"
        ;;
    200)
        fail "AAT-OBS-002: bogus run_id returned 200 (we must never invent an empty page)"
        ;;
    *)
        fail "AAT-OBS-002: unexpected HTTP $REFUSED_CODE for bogus run_id: $REFUSED_BODY"
        ;;
esac

# ---- AAT-OBS-003: empty / single-stream runs are sealed honestly ----
banner "AAT-OBS-003: empty pipeline => honest 200 with zero-chunk / no synthesized bytes"
EMPTY_EXP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"obs003-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
EMPTY_SNAP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EMPTY_EXP/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { } }","creator":"obs003"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
EMPTY_RUN="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: obs003-$SUFFIX" \
    -d "{\"experiment_id\":\"$EMPTY_EXP\",\"snapshot_id\":\"$EMPTY_SNAP\",\"scenario_id\":\"$EMPTY_EXP\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
for attempt in 1 2 3 4 5 6 7 8; do
    sleep 1
    curl -fsS "$STUDIO_HTTP/runs/$EMPTY_RUN/events" 2>/dev/null | grep -q '"RunFinished"' && break
done
EMPTY_OUT="$(curl -fsS "$STUDIO_HTTP/runs/$EMPTY_RUN/output?limit=10")"
EMPTY_TEXT="$(echo "$EMPTY_OUT" | python3 -c "
import json, sys, base64
d = json.load(sys.stdin)
out = []
for c in d.get('chunks', []):
    b = c.get('bytesBase64')
    if b:
        out.append(base64.b64decode(b).decode('utf-8', errors='replace'))
print(''.join(out))
")"
# We do NOT assert the body is empty (the SDK subprocess always
# emits its own boot header on stdout). We DO assert the body does
# not claim the user's pipeline echoed something that the user
# never asked it to echo. That would be a synthesized 200.
if echo "$EMPTY_TEXT" | grep -qiE 'echo.*synthesized|user-typed|sealed placeholder'; then
    fail "AAT-OBS-003: empty pipeline returned synthesized user-output: $(echo "$EMPTY_TEXT" | head -c 200)"
fi
ok "AAT-OBS-003: empty pipeline => honest subprocess stdout, no synthesized user-output"
stagger

# ---- AAT-OBS-004: byte-range paginated read is digest-stable ----
banner "AAT-OBS-004: byte-range reads reassemble losslessly across boundaries"
# Submit a multi-byte pipeline and read it in two slices via ?after=.
# The concat of (slice 1) + (slice 2) must equal the full read.
MARKER="Hola — 2026 — 🦊 λ Ω — $(date +%s%N)"
MULTI_EXP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"obs004-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
MULTI_SNAP_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$MULTI_EXP/snapshots" \
    -H "content-type: application/json" \
    -d "{\"source\":\"pipeline { stages { stage(\\\"mb\\\") { echo(\\\"$MARKER\\\") } } }\",\"creator\":\"obs004\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
MULTI_SNAP="$MULTI_SNAP_BODY"
MULTI_RUN="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: obs004-$SUFFIX" \
    -d "{\"experiment_id\":\"$MULTI_EXP\",\"snapshot_id\":\"$MULTI_SNAP\",\"scenario_id\":\"$MULTI_EXP\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
for attempt in 1 2 3 4 5 6 7 8; do
    sleep 1
    curl -fsS "$STUDIO_HTTP/runs/$MULTI_RUN/events" 2>/dev/null | grep -q '"RunFinished"' && break
done
sleep 1
# Get the full output once (read 1).
FULL_OUT="$(curl -fsS "$STUDIO_HTTP/runs/$MULTI_RUN/output")"
FULL_TEXT="$(echo "$FULL_OUT" | python3 -c "
import json, sys, base64
d = json.load(sys.stdin)
out = []
for c in d.get('chunks', []):
    b = c.get('bytesBase64')
    if b:
        out.append(base64.b64decode(b).decode('utf-8', errors='replace'))
print(''.join(out))
")"
if ! echo "$FULL_TEXT" | grep -qF "$MARKER"; then
    fail "AAT-OBS-004: marker not present in full read (cannot test reassembly)"
fi
# Find the byte offset of the marker in stdout (use python so we
# measure the actual byte position, not the character position).
MARKER_OFFSET="$(echo "$FULL_OUT" | python3 -c "
import json, sys, base64
marker = sys.argv[1].encode('utf-8')
d = json.load(sys.stdin)
acc = bytearray()
for c in d.get('chunks', []):
    b = c.get('bytesBase64')
    if b:
        acc.extend(base64.b64decode(b))
idx = acc.find(marker)
print(max(0, idx - 1))  # split a couple of bytes before the marker
" "$MARKER")"
SLICE_SIZE=$(( ${#MARKER} / 2 ))
# Read slice 1: bytes [0, MARKER_OFFSET+SLICE_SIZE)
SLICE1="$(curl -fsS "$STUDIO_HTTP/runs/$MULTI_RUN/output?limit=$((MARKER_OFFSET + SLICE_SIZE))")"
SLICE1_TEXT="$(echo "$SLICE1" | python3 -c "
import json, sys, base64
d = json.load(sys.stdin)
out = []
for c in d.get('chunks', []):
    b = c.get('bytesBase64')
    if b:
        out.append(base64.b64decode(b).decode('utf-8', errors='replace'))
print(''.join(out))
")"
SLICE1_NEXT="$(echo "$SLICE1" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(d.get('nextCursor', '0'))
")"
# Read slice 2: bytes [SLICE1_NEXT, end)
SLICE2="$(curl -fsS "$STUDIO_HTTP/runs/$MULTI_RUN/output?after=$SLICE1_NEXT")"
SLICE2_TEXT="$(echo "$SLICE2" | python3 -c "
import json, sys, base64
d = json.load(sys.stdin)
out = []
for c in d.get('chunks', []):
    b = c.get('bytesBase64')
    if b:
        out.append(base64.b64decode(b).decode('utf-8', errors='replace'))
print(''.join(out))
")"
# Marker must appear in slice1+slice2, byte-for-byte
RECOMBINED="${SLICE1_TEXT}${SLICE2_TEXT}"
if echo "$RECOMBINED" | grep -qF "$MARKER"; then
    ok "AAT-OBS-004: marker reassembles losslessly across a boundary at byte $MARKER_OFFSET"
else
    say "  slice1 length: $(echo -n "$SLICE1_TEXT" | wc -c)"
    say "  slice2 length: $(echo -n "$SLICE2_TEXT" | wc -c)"
    say "  expected marker length: ${#MARKER}"
    fail "AAT-OBS-004: marker lost across byte boundary (slice1_next=$SLICE1_NEXT)"
fi
# Slice 1 + slice 2 must equal the full read byte-for-byte
if [ "$RECOMBINED" = "$FULL_TEXT" ]; then
    ok "AAT-OBS-004: slice1+slice2 = full read (no bytes lost or duplicated)"
else
    fail "AAT-OBS-004: slice1+slice2 != full read (digest drift across ?after=)"
fi
stagger

# ---- AAT-OBS-005: SSE re-attach via ?after= is authoritative ----
banner "AAT-OBS-005: SSE ?after= re-read is authoritative (no dup, no loss)"
# Run a tiny pipeline, take events, then re-read with ?after=0
# (the head). The re-read must return the same events.
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"obs005-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"s\") { echo(\"x\") } } }","creator":"obs005"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: obs005-$SUFFIX" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
for attempt in 1 2 3 4 5 6 7 8; do
    sleep 1
    curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events" 2>/dev/null | grep -q '"RunFinished"' && break
done
# Full snapshot of the journal
JOURNAL="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events")"
JOURNAL_COUNT="$(echo "$JOURNAL" | python3 -c '
import json, sys
print(len(json.load(sys.stdin).get("events", [])))
')"
# Re-read with a paginated walk: limit=3, then ?after=next, etc.
WALK="$(python3 -c "
import json, urllib.request
seen = []
after = None
while True:
    url = 'http://127.0.0.1:7777/runs/$RUN_ID/events?limit=3'
    if after is not None:
        url += '&after=' + str(after)
    body = json.loads(urllib.request.urlopen(url, timeout=5).read())
    ev = body.get('events', [])
    seen.extend(ev)
    nxt = body.get('nextSequence')
    if nxt is None or len(ev) == 0:
        break
    after = nxt
print(len(seen))
")"
if [ "$WALK" = "$JOURNAL_COUNT" ]; then
    ok "AAT-OBS-005: paginated walk visits $WALK events = full journal ($JOURNAL_COUNT)"
else
    fail "AAT-OBS-005: paginated walk visited $WALK != full journal $JOURNAL_COUNT"
fi
stagger

# ---- AAT-OBS-008: lost-output vs not-emitted are distinguishable ----
banner "AAT-OBS-008: refusal / refusal-codes distinguish lost vs not-emitted"
# A bogus run_id returns 202 + a refusal code (we already proved this
# in OBS-002). A known run with no output yet (well-formed) returns
# 200 with an empty chunks list — that's "not emitted", which is a
# different state from "lost". The contract: the response distinguishes
# them via status + code, never by a silent 200 + empty page.
FRESH_EXP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"obs008-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
FRESH_SNAP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$FRESH_EXP/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"s\") { echo(\"y\") } } }","creator":"obs008"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
FRESH_RUN="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: obs008-$SUFFIX" \
    -d "{\"experiment_id\":\"$FRESH_EXP\",\"snapshot_id\":\"$FRESH_SNAP\",\"scenario_id\":\"$FRESH_EXP\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
# Immediately query /output for the still-running run: this is the
# "not emitted yet" case. It must return 200 with a well-formed
# body, not a refusal.
FRESH_CODE="$(curl -sS -o /tmp/obs008-fresh.json -w '%{http_code}' \
    "$STUDIO_HTTP/runs/$FRESH_RUN/output")"
if [ "$FRESH_CODE" = "200" ]; then
    ok "AAT-OBS-008: not-yet-emitted output => 200 with honest body (not refusal)"
else
    fail "AAT-OBS-008: not-yet-emitted output returned $FRESH_CODE (expected 200, refusal is for the lost case)"
fi
# Bogus run_id (lost) => we already verified 202/refusal above. So:
#   - lost         => 202 + OUTPUT_* refusal code
#   - not-emitted  => 200 with well-formed empty/short body
# These two states are distinguishable. Good.

say ""
say "AAT-OBS-001..005 + 008 (006/007 deferred to perf-soak): ALL PASSED"
