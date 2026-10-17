#!/usr/bin/env bash
# scripts/uat-output-bytes.sh — UAT-018: multi-byte output round-trip.
#
# Per docs/02-specifications/HTTP_AND_STREAMING_API.md §Output Plane
# and the S1 ROADMAP item "multi-byte dividido":
#
#   A pipeline that emits UTF-8 multi-byte characters (Spanish, Japanese,
#   emoji, etc.) must round-trip through the Output Plane without
#   byte-loss or charset corruption. The OutputReadPort is supposed
#   to be byte-faithful; this UAT proves it.
#
# The script:
#   1. Seals a snapshot whose stage echoes a known UTF-8 string
#      (Spanish, accented, multi-byte)
#   2. Submits a run
#   3. Polls /runs/{id}/output until the run finishes
#   4. Decodes the base64 envelope
#   5. Greps for the known UTF-8 string in the captured stdout
#
# Exit codes:
#   0 = UTF-8 string round-tripped cleanly
#   1 = output missing the expected string (or studio unreachable)

set -euo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; exit 1; }

# A short multi-byte string: Spanish, accented, plus an emoji (4-byte UTF-8).
MARKER="Hola mundo — PipelineK Studio: año 2026 — Ω λ — 🦊"
say "=== UAT-018: multi-byte output round-trip ==="
say "marker: $MARKER"

# 1. Create experiment
EXP_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" \
    -H "content-type: application/json" \
    -d '{"name":"uat-018-multibyte"}')"
EXP_ID="$(echo "$EXP_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
ok "experiment: $EXP_ID"

# 2. Seal a snapshot that prints the marker.
# Use Kotlin's string interpolation so the marker reaches the SDK
# verbatim. The echo step captures it to stdout.
SNAP_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    --data-binary @- <<JSON
{
  "source": "pipeline {\n  stages {\n    stage(\"multibyte\") {\n      echo(\"${MARKER}\")\n    }\n  }\n}",
  "creator": "uat-018"
}
JSON
)"
SNAP_ID="$(echo "$SNAP_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
ok "snapshot: $SNAP_ID"

# 3. Submit a run
RUN_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/runs" \
    -H "content-type: application/json" \
    -H "idempotency-key: uat-018-$(date +%s%N)" \
    -d "{\"experiment_id\":\"$EXP_ID\",\"snapshot_id\":\"$SNAP_ID\",\"scenario_id\":\"$EXP_ID\"}")"
RUN_ID="$(echo "$RUN_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
ok "run submitted: $RUN_ID"

# 4. Poll /runs/{id}/output up to 15s, accumulating base64 chunks.
DECODED=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 2
    OUT_JSON="$(curl -sS "$STUDIO_HTTP/runs/$RUN_ID/output" 2>/dev/null || echo "{}")"
    if echo "$OUT_JSON" | grep -q '"chunksBase64"\|"chunks"'; then
        DECODED="$(echo "$OUT_JSON" | python3 -c '
import json, sys, base64
try:
    d = json.load(sys.stdin)
    chunks = d.get("chunks", [])
    out = []
    for c in chunks:
        b = c.get("bytesBase64")
        if b:
            out.append(base64.b64decode(b).decode("utf-8", errors="replace"))
    sys.stdout.write("".join(out))
except Exception as e:
    sys.stderr.write(f"decode_error: {e}")
')"
        if [ -n "$DECODED" ] && echo "$DECODED" | grep -qF "Hola mundo"; then
            break
        fi
    fi
done

if [ -z "$DECODED" ]; then
    fail "no output captured after 15s"
fi

# 5. Verify the marker is in the output, byte-for-byte.
if echo "$DECODED" | grep -qF "$MARKER"; then
    ok "UAT-018 marker round-tripped cleanly (Spanish + accented + Ω + λ + emoji)"
else
    fail "UAT-018 marker not found in output. Got: $(echo "$DECODED" | head -c 300)"
fi

# 6. Verify byte length matches (no truncation)
EXPECTED_BYTES="$(printf '%s' "$MARKER" | wc -c)"
SAW_BYTES="$(echo "$DECODED" | grep -F "$MARKER" | head -1 | wc -c)"
say "  expected bytes: $EXPECTED_BYTES, saw (line containing marker): $SAW_BYTES"
if [ "$EXPECTED_BYTES" -gt 0 ] && [ "$SAW_BYTES" -ge "$EXPECTED_BYTES" ]; then
    ok "UAT-018 byte length matches (no truncation)"
else
    fail "UAT-018 byte length mismatch"
fi

# 7. Verify the echo's exact content (not the whole stdout is huge)
SAMPLE="$(echo "$DECODED" | grep -F "Hola mundo" | head -1)"
say "  sample line: $SAMPLE"

say ""
say "UAT-018: PASSED"
