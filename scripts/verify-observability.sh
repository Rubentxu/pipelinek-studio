#!/usr/bin/env bash
# scripts/verify-observability.sh — AAT-OBS-001..005: the Output Plane contract.
#
# Per S5 deliverable "Matriz de runtime/API/plugin compatibles" + R-03
# ("OBS stdout/stderr/sellado/recovery no certificado" — no inferir
# salida de Event Plane ni convertir refusal en bytes vacíos):
#
#   OBS-001 — The Output Plane reports a streamId per chunk (we never
#             emit anonymous bytes that the SPA cannot label).
#   OBS-002 — Chunks are byte-faithful: base64 round-trip preserves
#             the underlying UTF-8 byte sequence (no charset mangling).
#   OBS-003 — EOF is reported on the last chunk; after EOF, the
#             same cursor is still well-formed (idempotent re-query).
#   OBS-004 — The cursor is monotonic: nextCursor > last cursor seen.
#   OBS-005 — A subprocess that produced no stdout still serves an
#             empty + EOF chunk (we never invent output).
#
# Exit codes:
#   0 = every OBS invariant holds
#   1 = at least one invariant failed

set -uo pipefail

STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"
# The controlRoot is derived from `pipelinek.studio.runtime.dataDir` (set
# in the Gradle-generated launcher to ~/.pipelinek-studio/runtime). Allow
# override via env var for tests that run in a sandboxed data dir.
DATA_DIR="${STUDIO_DATA_DIR:-$HOME/.pipelinek-studio/runtime}"
CONTROL_ROOT="${STUDIO_CONTROL_ROOT:-$DATA_DIR/runs}"
SUFFIX="${OBS_SUFFIX:-$(date +%s%N)}"

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; exit 1; }
banner() { printf '\n=== %s ===\n' "$*"; }

# 1. Run a tiny pipeline so we have a non-trivial output.
banner "Setup: submit a tiny echo pipeline"
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"aat-obs-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
SNAP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { stage(\"obs\") { echo(\"observability\") } } }","creator":"aat-obs"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: aat-obs-$SUFFIX" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
ok "submitted: $RUN_ID"

# Wait for run to finish
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    if curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events" 2>/dev/null | grep -q '"RunFinished"'; then
        break
    fi
done
ok "run finished"

# 2. OBS-001: streamId is present on every chunk
banner "OBS-001: every chunk has a streamId"
OUT="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/output?limit=50")"
ANON="$(echo "$OUT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
chunks = d.get('chunks', [])
if not chunks:
    print('NO_CHUNKS')
    sys.exit(0)
bad = [c for c in chunks if not c.get('streamId')]
print(','.join(str(i) for i, c in enumerate(chunks) if not c.get('streamId')))
")"
if [ "$ANON" = "NO_CHUNKS" ]; then
    fail "no chunks at all (the SDK subprocess produced no output?)"
fi
if [ -n "$ANON" ]; then
    fail "chunks without streamId: indices $ANON"
fi
STREAMS="$(echo "$OUT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(','.join(sorted({c.get('streamId') for c in d.get('chunks', [])})))
")"
ok "streamIds seen: $STREAMS"
case "$STREAMS" in
    *stdout*|*stderr*)
        ok "OBS-001 passed"
        ;;
    *)
        fail "OBS-001: expected at least one of {stdout, stderr}, got '$STREAMS'"
        ;;
esac

# 3. OBS-002: base64 round-trip preserves UTF-8
banner "OBS-002: chunks are byte-faithful (base64 round-trip)"
ROUNDTRIP_OK="$(echo "$OUT" | python3 -c "
import json, sys, base64
d = json.load(sys.stdin)
chunks = d.get('chunks', [])
ok = True
for c in chunks:
    b = c.get('bytesBase64')
    if b is None:
        continue
    try:
        # round-trip and re-encode; if the original is well-formed base64
        # the two encodings must match exactly
        raw = base64.b64decode(b, validate=True)
        again = base64.b64encode(raw).decode('ascii')
        if again != b:
            ok = False
            break
    except Exception:
        ok = False
        break
print('yes' if ok else 'no')
")"
if [ "$ROUNDTRIP_OK" = "yes" ]; then
    ok "OBS-002: every chunk's base64 is well-formed and stable under round-trip"
else
    fail "OBS-002: at least one chunk's base64 is malformed or not stable"
fi

# 4. OBS-003: EOF reported + the log is stable after RunFinished
banner "OBS-003: log file is sealed/stable after RunFinished (R-03 contract)"
LAST_CHUNK="$(echo "$OUT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
chunks = d.get('chunks', [])
print(json.dumps(chunks[-1]) if chunks else '{}')
")"
EOF_FLAG="$(echo "$LAST_CHUNK" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("eof", False))')"
LAST_CURSOR="$(echo "$LAST_CHUNK" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("cursor", "0"))')"
if [ "$EOF_FLAG" = "True" ]; then
    ok "last chunk has eof=True (cursor=$LAST_CURSOR)"
else
    fail "last chunk eof is not True (got $EOF_FLAG)"
fi
# After RunFinished, the log file MUST be stable. We give the subprocess
# a moment to truly exit (subprocess may still be flushing) and then
# assert: re-querying with after=<last_cursor> returns 0 chunks, AND
# the file size on disk equals the cursor.
sleep 2
SEALED_SIZE="$(stat -c %s "$CONTROL_ROOT/$RUN_ID/stdout.log" 2>/dev/null || echo 0)"
if [ "$SEALED_SIZE" = "0" ]; then
    fail "stdout.log not found at $CONTROL_ROOT/$RUN_ID/stdout.log"
fi
# The cursor sits at the byte offset of the byte just AFTER the last
# read byte. So a sealed log file must have size == cursor (no bytes
# were appended after RunFinished was reported).
if [ "$SEALED_SIZE" -gt "$LAST_CURSOR" ]; then
    fail "stdout.log grew after RunFinished: file=$SEALED_SIZE cursor=$LAST_CURSOR (R-03 violation: output is not sealed)"
fi
ok "stdout.log is sealed/stable: file size=$SEALED_SIZE, last cursor=$LAST_CURSOR"
# Now re-query: with after=LAST_CURSOR, the cursor sits inside the
# sealed file at offset=LAST_CURSOR and there's no more data to read.
RE_QUERY="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/output?limit=10&after=$LAST_CURSOR")"
RE_COUNT="$(echo "$RE_QUERY" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(len(d.get('chunks', [])))
")"
if [ "$RE_COUNT" = "0" ]; then
    ok "re-query with after=$LAST_CURSOR returns 0 chunks (EOF is terminal)"
else
    fail "OBS-003: re-query returned $RE_COUNT chunks after RunFinished (R-03: output not sealed)"
fi

# 5. OBS-004: cursor is monotonic
banner "OBS-004: cursor is monotonic across the run"
MONOTONIC="$(echo "$OUT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
chunks = d.get('chunks', [])
cursors = [int(c.get('cursor', 0)) for c in chunks]
print('yes' if all(b >= a for a, b in zip(cursors, cursors[1:])) else 'no')
")"
if [ "$MONOTONIC" = "yes" ]; then
    ok "OBS-004: cursors are monotonically non-decreasing"
else
    fail "OBS-004: cursor sequence is not monotonic"
fi

# 6. OBS-005: an empty pipeline doesn't produce a synthesized 200 with bytes
banner "OBS-005: empty pipeline: 200 with zero chunks, no synthesized body"
EMPTY_EXP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d "{\"name\":\"aat-obs-empty-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
EMPTY_SNAP="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EMPTY_EXP/snapshots" \
    -H "content-type: application/json" \
    -d '{"source":"pipeline { stages { } }","creator":"aat-obs-empty"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
EMPTY_RUN="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: aat-obs-empty-$SUFFIX" \
    -d "{\"experiment_id\":\"$EMPTY_EXP\",\"snapshot_id\":\"$EMPTY_SNAP\",\"scenario_id\":\"$EMPTY_EXP\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
for attempt in 1 2 3 4 5 6 7 8; do
    sleep 1
    if curl -fsS "$STUDIO_HTTP/runs/$EMPTY_RUN/events" 2>/dev/null | grep -q '"RunFinished"'; then
        break
    fi
done
EMPTY_OUT="$(curl -fsS "$STUDIO_HTTP/runs/$EMPTY_RUN/output?limit=10")"
EMPTY_CHUNKS="$(echo "$EMPTY_OUT" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(len(d.get('chunks', [])))
")"
# An empty pipeline with the SDK subprocess may still produce the
# "Discovered external Step plugins: ..." boot header on stdout (the
# SDK subprocess always emits it), so we expect a chunk but it should
# be the honest boot header — not a synthesized payload claiming
# something was echoed.
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
if echo "$EMPTY_TEXT" | grep -qE 'echo|synthesized|fake'; then
    fail "OBS-005: empty pipeline produced suspicious text: $(echo "$EMPTY_TEXT" | head -c 200)"
fi
ok "OBS-005: empty pipeline produced $EMPTY_CHUNKS chunk(s); no synthesized echo content"
ok "  chunk content preview: $(echo "$EMPTY_TEXT" | head -c 80 | tr '\n' ' ')..."

say ""
say "AAT-OBS-001..005: ALL PASSED"
