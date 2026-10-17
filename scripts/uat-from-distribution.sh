#!/usr/bin/env bash
# scripts/uat-from-distribution.sh — the canonical public UAT for an installed
# Studio distribution.
#
# One operator-visible flow against a *running* Studio:
#   health -> version -> OpenAPI -> experiment -> scenario -> snapshot ->
#   run submit -> terminal state -> output bytes -> diagnostics
#
# ## Verdict contract (S3)
#
# A verdict is only PASSED when PipelineK actually ran and produced the
# expected result. Absence of execution is NEVER a pass:
#
#   exit 0  UAT_VERDICT: PASSED    run reached SUCCEEDED and the output
#                                  contained the marker sealed into the source
#   exit 1  UAT_VERDICT: FAILED    executed (or advertised) but the expected
#                                  result is missing, non-success terminal, or
#                                  an undocumented response
#   exit 2                         usage / prerequisite (no HTTP is issued)
#   exit 3  UAT_VERDICT: BLOCKED   the runtime is absent or not certified:
#                                  409 REFUSED / 503 / no run id / timeout
#
# The historical bug this file closes: a `409 RUNTIME_UNAVAILABLE` used to
# print `ALL UAT STEPS PASSED` and exit 0 without executing PipelineK. A
# refusal is *evidence about absence*, not acceptance: it is reported as
# BLOCKED and the success banner is unreachable from that path.
#
# ## Target (no silent plaintext downgrade)
#
# STUDIO_HTTP is REQUIRED — there is no default endpoint, because the Studio
# now serves HTTPS (127.0.0.1:7778) and a stale http:// default would look
# like a failure instead of a misconfiguration:
#
#   STUDIO_HTTP=https://127.0.0.1:7778 \
#     STUDIO_CA_CERT=/path/to/studio-loopback.pem ./scripts/uat-from-distribution.sh
#
#   STUDIO_HTTP=http://127.0.0.1:7777 STUDIO_HTTP_INSECURE=1 ...   # test HTTP
#
# https:// uses curl's trust store; a self-signed loopback cert needs
# STUDIO_CA_CERT (export it from the Studio keystore, see
# docs/05-testing/UAT_CATALOG.md). http:// requires the same documented
# STUDIO_HTTP_INSECURE=1 opt-in the CLI and MCP adapter already enforce.
# There is no `-k`/insecure-TLS bypass here: a certificate failure is a real
# failure. The contract itself is implemented once, in
# scripts/lib/studio-http.sh, and shared with every other operator script.
#
# ## Options and environment
#
#   --help                     print this contract
#   STUDIO_HTTP                required base URL
#   STUDIO_HTTP_INSECURE=1     allow plain http:// (test-only opt-in)
#   STUDIO_CA_CERT             PEM to trust for https://
#   STUDIO_CLI_BIN             installed `studio-cli`; its --version must agree
#                              with GET /api/version (never a hardcoded literal)
#   UAT_TIMEOUT=120            1..1800; bounds the terminal wait, every HTTP
#                              request (connect <=5s, total <=30s) and the
#                              post-terminal output grace (<=10s)
#   UAT_POLL_INTERVAL=1        0..60; seconds between polls
#
# Diagnostics (events / diagnostics / IR / steps) are reported as diagnostics:
# a *documented* refusal is NOT MEASURED and does not gate acceptance. An
# undocumented status is a contract deviation and fails the run.

set -uo pipefail

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
info()   { say "  • $*"; }
banner() { printf '\n=== %s ===\n' "$*"; }

usage() { # print the header block (lines 2.. until the first non-comment line)
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
}

fail() { # UAT_VERDICT: FAILED — the product executed but the expected result is absent.
    say ""
    say "  ❌ $*"
    say "UAT_VERDICT: FAILED"
    say "From-distribution UAT: FAILED (acceptance NOT met)"
    exit 1
}

blocked() { # UAT_VERDICT: BLOCKED — the runtime is absent/uncertified. Never a pass.
    say ""
    say "  ⚠️  $*"
    say "UAT_VERDICT: BLOCKED"
    say "From-distribution UAT: BLOCKED (not a pass; the runtime did not certify a run)"
    exit 3
}

die_usage() {
    say "UAT_CONTRACT_ERROR: $*" >&2
    say "" >&2
    usage >&2
    exit 2
}

need() {
    command -v "$1" >/dev/null 2>&1 || {
        say "UAT_CONTRACT_ERROR: missing required tool: $1" >&2
        exit 2
    }
}

# --- arguments ------------------------------------------------------------
case "${1:-}" in
    --help | -h) usage; exit 0 ;;
    "") ;;
    *) die_usage "unknown argument: $1" ;;
esac

need curl
need python3

# The transport contract lives in exactly one place: scripts/lib/studio-http.sh.
# Sourcing it validates STUDIO_HTTP / STUDIO_HTTP_INSECURE / STUDIO_CA_CERT and
# fails as a usage error (exit 2, via die_usage above) before any request; it
# also exports CURL_CA_BUNDLE / SSL_CERT_FILE for the documented CA path.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"

# One knob bounds the whole run: UAT_TIMEOUT caps the terminal wait, each
# HTTP request (so a stalled server cannot hang the UAT), and the post-terminal
# output grace. Every value is validated BEFORE the first request so a typo
# fails as a usage error instead of a mysterious network timeout.
UAT_TIMEOUT="${UAT_TIMEOUT:-120}"
UAT_POLL_INTERVAL="${UAT_POLL_INTERVAL:-1}"
case "$UAT_TIMEOUT" in '' | *[!0-9]*) die_usage "UAT_TIMEOUT must be an integer number of seconds" ;; esac
case "$UAT_POLL_INTERVAL" in '' | *[!0-9]*) die_usage "UAT_POLL_INTERVAL must be an integer number of seconds" ;; esac
[ "$UAT_TIMEOUT" -ge 1 ] && [ "$UAT_TIMEOUT" -le 1800 ] || die_usage "UAT_TIMEOUT must be between 1 and 1800 seconds"
[ "$UAT_POLL_INTERVAL" -ge 0 ] && [ "$UAT_POLL_INTERVAL" -le 60 ] || die_usage "UAT_POLL_INTERVAL must be between 0 and 60 seconds"
# Derived, not extra knobs: a request may never outlive the run budget.
HTTP_MAX_TIME="$UAT_TIMEOUT"
[ "$HTTP_MAX_TIME" -gt 30 ] && HTTP_MAX_TIME=30
HTTP_CONNECT_TIMEOUT="$UAT_TIMEOUT"
[ "$HTTP_CONNECT_TIMEOUT" -gt 5 ] && HTTP_CONNECT_TIMEOUT=5
# The output plane is stable once the run is terminal (R-03), so the grace
# after a terminal state is short and never exceeds the run budget.
OUTPUT_WAIT="$UAT_TIMEOUT"
[ "$OUTPUT_WAIT" -gt 10 ] && OUTPUT_WAIT=10

# --- per-run owned scratch under TMPDIR (never a shared /tmp/uat-* path) ---
WORK="$(mktemp -d "${TMPDIR:-/tmp}/uat-XXXXXX")" || { say "UAT_CONTRACT_ERROR: cannot create scratch dir" >&2; exit 2; }
# Invoked by the EXIT trap below (shellcheck cannot see trap targets).
# shellcheck disable=SC2329
cleanup() { [ -n "${WORK:-}" ] && rm -rf "$WORK"; }
trap cleanup EXIT

# --- small HTTP/JSON helpers ---------------------------------------------
# http METHOD PATH [JSON_BODY] [extra curl args...]
# sets HTTP_CODE + HTTP_BODY. Never aborts on a non-2xx: the caller decides
# the verdict, which is the whole point of this script.
http() {
    local method="$1" path="$2"
    shift 2
    local body=""
    if [ "$#" -gt 0 ] && [ "${1#-}" = "$1" ]; then
        body="$1"
        shift
    fi
    local -a args=(-sS -o "$WORK/body" -w '%{http_code}' -X "$method" \
        --connect-timeout "$HTTP_CONNECT_TIMEOUT" --max-time "$HTTP_MAX_TIME")
    if [ -n "$body" ]; then
        args+=(-H 'content-type: application/json' --data-binary "$body")
    fi
    args+=("$@")
    HTTP_CODE="$(curl "${args[@]}" "${STUDIO_HTTP}${path}" 2>"$WORK/curl.err" || true)"
    HTTP_BODY="$(cat "$WORK/body" 2>/dev/null || true)"
    if [ -z "$HTTP_CODE" ]; then
        HTTP_CODE="000"
        HTTP_BODY="$(cat "$WORK/curl.err" 2>/dev/null || true)"
    fi
}

# json_get DOTTED.KEY  (reads JSON on stdin; prints the value)
json_get() {
    python3 -c '
import json, sys
try:
    value = json.load(sys.stdin)
except Exception:
    sys.exit(1)
for key in sys.argv[1].split("."):
    if isinstance(value, dict) and key in value:
        value = value[key]
    else:
        sys.exit(1)
if value is None:
    sys.exit(1)
sys.stdout.write(value if isinstance(value, str) else json.dumps(value))
' "$1"
}

# decode_output reads the typed OutputReadResponse and concatenates the
# Base64 chunks in cursor order (the wire shape is bytesBase64, not text).
decode_output() {
    python3 -c '
import base64, json, sys
try:
    doc = json.load(sys.stdin)
except Exception:
    sys.exit(1)
out = []
for chunk in doc.get("chunks") or []:
    payload = chunk.get("bytesBase64") or ""
    if payload:
        out.append(base64.b64decode(payload).decode("utf-8", "replace"))
sys.stdout.write("".join(out))
'
}

json_string() { # json_string <bash string> -> JSON string literal
    python3 -c 'import json,sys; sys.stdout.write(json.dumps(sys.argv[1]))' "$1"
}

banner "0. Target"
info "base URL: $STUDIO_HTTP"
info "timeout:  ${UAT_TIMEOUT}s for a terminal run state"

# --- 1. Liveness ----------------------------------------------------------
banner "1. Liveness"
HEALTH_DEADLINE=$((SECONDS + HTTP_MAX_TIME))
while :; do
    http GET /health
    if [ "$HTTP_CODE" = "200" ] && [ "$(printf '%s' "$HTTP_BODY" | json_get status 2>/dev/null)" = "ok" ]; then
        ok "/health -> ok"
        break
    fi
    [ "$SECONDS" -ge "$HEALTH_DEADLINE" ] && blocked "/health never reported ok (last HTTP $HTTP_CODE: $(printf '%s' "$HTTP_BODY" | head -c 200))"
    sleep 1
done

# --- 2. Version contract --------------------------------------------------
banner "2. Version contract"
http GET /api/version
[ "$HTTP_CODE" = "200" ] || fail "/api/version returned HTTP $HTTP_CODE: $(printf '%s' "$HTTP_BODY" | head -c 200)"
STUDIO_VERSION="$(printf '%s' "$HTTP_BODY" | json_get studio 2>/dev/null || printf '%s' "$HTTP_BODY" | json_get version 2>/dev/null || true)"
[ -n "$STUDIO_VERSION" ] || fail "/api/version names no studio version: $(printf '%s' "$HTTP_BODY" | head -c 200)"
ok "API version: $STUDIO_VERSION"

# S3: compare against the *installed* build, never a hardcoded release literal.
if [ -n "${STUDIO_CLI_BIN:-}" ]; then
    [ -x "$STUDIO_CLI_BIN" ] || die_usage "STUDIO_CLI_BIN is not executable: $STUDIO_CLI_BIN"
    INSTALLED_VERSION="$("$STUDIO_CLI_BIN" --version 2>&1 || true)"
    case "$INSTALLED_VERSION" in
        *"$STUDIO_VERSION"*) ok "installed binary agrees: $INSTALLED_VERSION" ;;
        *) fail "installed/API version drift: /api/version=$STUDIO_VERSION but '$STUDIO_CLI_BIN --version' -> '$INSTALLED_VERSION'" ;;
    esac
else
    info "STUDIO_CLI_BIN not set: installed-binary cross-check skipped"
fi

# --- 3. OpenAPI contract --------------------------------------------------
banner "3. OpenAPI contract"
http GET /openapi.json
[ "$HTTP_CODE" = "200" ] || fail "/openapi.json returned HTTP $HTTP_CODE"
case "$HTTP_BODY" in
    *'"openapi": "3.1.0"'* | *'"openapi":"3.1.0"'*) ok "openapi 3.1.0" ;;
    *) fail "openapi doc is not 3.1.0" ;;
esac
for path in /health /api/version /experiments /runs /steps; do
    case "$HTTP_BODY" in
        *"\"$path\""*) ;;
        *) fail "openapi doc is missing the documented path $path" ;;
    esac
done
ok "5/5 sampled paths present"

# --- 4. Experiment --------------------------------------------------------
banner "4. Create experiment"
http POST /experiments "$(printf '{"name":"uat-%s"}' "$(date +%s)")"
case "$HTTP_CODE" in
    200 | 201) ;;
    *) fail "POST /experiments returned HTTP $HTTP_CODE: $(printf '%s' "$HTTP_BODY" | head -c 200)" ;;
esac
EXP_ID="$(printf '%s' "$HTTP_BODY" | json_get experiment_id 2>/dev/null || true)"
[ -n "$EXP_ID" ] || fail "create experiment returned no experiment_id: $(printf '%s' "$HTTP_BODY" | head -c 200)"
ok "experiment: $EXP_ID"

# --- 5. Scenario ----------------------------------------------------------
# R0.3.1: scenarios are first-class. The experiment id is NOT a scenario id
# surrogate (AAT-DUR-005); run_bindings FKs to scenarios(scenario_id).
banner "5. Register scenario"
http POST /scenarios '{}'
case "$HTTP_CODE" in
    200 | 201) ;;
    *) fail "POST /scenarios returned HTTP $HTTP_CODE: $(printf '%s' "$HTTP_BODY" | head -c 200)" ;;
esac
SCENARIO_ID="$(printf '%s' "$HTTP_BODY" | json_get scenario_id 2>/dev/null || true)"
[ -n "$SCENARIO_ID" ] || fail "register scenario returned no scenario_id: $(printf '%s' "$HTTP_BODY" | head -c 200)"
ok "scenario: $SCENARIO_ID"

# --- 6. Seal a snapshot that prints a unique marker -----------------------
banner "6. Seal snapshot"
# Digits only after the dash so the marker needs no escaping downstream.
MARKER="UAT_MARKER-$(date +%s)$$$RANDOM"
SOURCE="$(printf 'pipeline {\n  stages {\n    stage(\"uat\") {\n      echo(\"%s\")\n    }\n  }\n}' "$MARKER")"
SNAP_PAYLOAD="$(printf '{"source":%s,"creator":"uat-from-distribution"}' "$(json_string "$SOURCE")")"
http POST "/experiments/${EXP_ID}/snapshots" "$SNAP_PAYLOAD"
case "$HTTP_CODE" in
    200 | 201) ;;
    *) fail "seal snapshot returned HTTP $HTTP_CODE: $(printf '%s' "$HTTP_BODY" | head -c 200)" ;;
esac
SNAP_ID="$(printf '%s' "$HTTP_BODY" | json_get snapshot_id 2>/dev/null || true)"
[ -n "$SNAP_ID" ] || fail "seal snapshot returned no snapshot_id: $(printf '%s' "$HTTP_BODY" | head -c 200)"
ok "snapshot: $SNAP_ID"
info "expected output marker: $MARKER"

# --- 7. Run submit --------------------------------------------------------
banner "7. Run submit"
IDEM_KEY="uat-$(date +%s)-$RANDOM$RANDOM"
RUN_PAYLOAD="$(printf '{"experiment_id":"%s","snapshot_id":"%s","scenario_id":"%s"}' "$EXP_ID" "$SNAP_ID" "$SCENARIO_ID")"
http POST /runs "$RUN_PAYLOAD" -H "idempotency-key: $IDEM_KEY"
case "$HTTP_CODE" in
    200 | 201 | 202)
        RUN_ID="$(printf '%s' "$HTTP_BODY" | json_get run_id 2>/dev/null || true)"
        [ -n "$RUN_ID" ] || fail "run submit returned no run_id; the public response key is run_id, got: $(printf '%s' "$HTTP_BODY" | head -c 200)"
        ok "run: $RUN_ID (state: $(printf '%s' "$HTTP_BODY" | json_get state 2>/dev/null || echo unknown))"
        ;;
    409 | 503)
        REFUSAL="$(printf '%s' "$HTTP_BODY" | json_get code 2>/dev/null || echo UNTYPED)"
        blocked "run submit refused (HTTP $HTTP_CODE $REFUSAL): $(printf '%s' "$HTTP_BODY" | head -c 300)"
        ;;
    *)
        fail "run submit returned HTTP $HTTP_CODE: $(printf '%s' "$HTTP_BODY" | head -c 300)"
        ;;
esac

# --- 8. Terminal state ----------------------------------------------------
banner "8. Wait for a terminal state"
DEADLINE=$((SECONDS + UAT_TIMEOUT))
TERMINAL=""
while :; do
    http GET "/runs/${RUN_ID}"
    if [ "$HTTP_CODE" = "200" ]; then
        STATE="$(printf '%s' "$HTTP_BODY" | json_get state 2>/dev/null || true)"
        case "$STATE" in
            SUCCEEDED | FAILED | CANCELLED | REFUSED) TERMINAL="$STATE"; break ;;
        esac
    fi
    if [ "$SECONDS" -ge "$DEADLINE" ]; then
        blocked "no terminal state for run $RUN_ID within ${UAT_TIMEOUT}s (last state: ${STATE:-unknown}, HTTP $HTTP_CODE)"
    fi
    sleep "$UAT_POLL_INTERVAL"
done
case "$TERMINAL" in
    SUCCEEDED) ok "run terminated state=SUCCEEDED" ;;
    REFUSED) blocked "run $RUN_ID terminated REFUSED (the runtime declined to execute)" ;;
    *) fail "run $RUN_ID terminated state=$TERMINAL (success requires SUCCEEDED)" ;;
esac

# --- 9. Output plane: the expected result --------------------------------
banner "9. Output contains the sealed marker"
OUT_DEADLINE=$((SECONDS + OUTPUT_WAIT))
OUTPUT=""
OUT_REFUSAL=""
while :; do
    http GET "/runs/${RUN_ID}/output"
    case "$HTTP_CODE" in
        200)
            OUTPUT="$(printf '%s' "$HTTP_BODY" | decode_output 2>/dev/null || true)"
            case "$OUTPUT" in
                *"$MARKER"*) break ;;
            esac
            ;;
        202)
            # A typed refusal is final (the stream is not available for this
            # run); an untyped 202 means the plane is still materialising.
            if printf '%s' "$HTTP_BODY" | grep -qE 'OUTPUT_REFUSED|OUTPUT_UNKNOWN_STREAM|OUTPUT_RECOVERY_REQUIRED'; then
                OUT_REFUSAL="$(printf '%s' "$HTTP_BODY" | head -c 200)"
                break
            fi
            ;;
        *)
            fail "/runs/{id}/output returned HTTP $HTTP_CODE: $(printf '%s' "$HTTP_BODY" | head -c 200)"
            ;;
    esac
    [ "$SECONDS" -ge "$OUT_DEADLINE" ] && break
    sleep "$UAT_POLL_INTERVAL"
done
if [ -n "$OUT_REFUSAL" ]; then
    fail "output plane refused the stream, so the sealed marker cannot be verified: $OUT_REFUSAL"
fi
case "$OUTPUT" in
    *"$MARKER"*) ok "output contains the marker ($(printf '%s' "$OUTPUT" | wc -c) bytes decoded)" ;;
    *) fail "output marker missing after ${OUTPUT_WAIT}s: expected '$MARKER' in $(printf '%s' "$OUTPUT" | wc -c) decoded bytes" ;;
esac

# --- 10. Diagnostics (NOT acceptance) -------------------------------------
# These surfaces are reported as diagnostics. A documented refusal means the
# capability is not wired in this build: NOT MEASURED, not a failure of the
# run above — and not evidence that it works either. An undocumented status
# is a contract deviation and fails the run.
banner "10. Diagnostics (reported, never used as acceptance)"
diagnostic() { # diagnostic <label> <path> <documented-refusal-regex> [<200-shape-regex>]
    local label="$1" path="$2" refusals="$3" shape="${4:-}"
    http GET "$path"
    case "$HTTP_CODE" in
        200)
            if [ -n "$shape" ] && ! printf '%s' "$HTTP_BODY" | grep -qE "$shape"; then
                fail "$label: 200 without the documented shape /$shape/: $(printf '%s' "$HTTP_BODY" | head -c 200)"
            fi
            ok "$label: 200"
            ;;
        202 | 503)
            if printf '%s' "$HTTP_BODY" | grep -qE "$refusals"; then
                info "$label: NOT MEASURED (documented refusal: $(printf '%s' "$HTTP_BODY" | json_get code 2>/dev/null || echo refusal))"
            else
                fail "$label returned HTTP $HTTP_CODE without a documented refusal: $(printf '%s' "$HTTP_BODY" | head -c 200)"
            fi
            ;;
        *) fail "$label returned undocumented HTTP $HTTP_CODE: $(printf '%s' "$HTTP_BODY" | head -c 200)" ;;
    esac
}

diagnostic "events" "/runs/${RUN_ID}/events" 'EVENTS_UNKNOWN|run\.db'
diagnostic "diagnostics" "/runs/${RUN_ID}/diagnostics" 'DIAGNOSTICS_UNAVAILABLE|DIAGNOSTICS_UNKNOWN'
diagnostic "ir" "/runs/${RUN_ID}/ir" 'IR_UNAVAILABLE|IR_UNKNOWN'
diagnostic "steps" "/steps" 'STEP_CATALOG_UNAVAILABLE|STEP_CATALOG_FAILURE' '"steps"'

# --- 11. Verdict ----------------------------------------------------------
banner "11. Verdict"
say "UAT_VERDICT: PASSED"
say "ALL UAT STEPS PASSED"
say "From-distribution UAT: ACCEPTED"
say "  experiment: $EXP_ID"
say "  scenario:   $SCENARIO_ID"
say "  snapshot:   $SNAP_ID"
say "  run:        $RUN_ID (state=$TERMINAL)"
say "  marker:     $MARKER"
exit 0
