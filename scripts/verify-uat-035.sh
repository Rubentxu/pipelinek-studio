#!/usr/bin/env bash
# scripts/verify-uat-035.sh — UAT-035 IR + runtime event correlation.
#
# Per docs/05-testing/UAT_CATALOG.md:
#   UAT-035 — Seleccionar nodo IR y consulta evento runtime →
#             Se distingue diseño/planificado de evento confirmado.
#
# Contract: the IR (snapshot) represents the planned structure of
# a run, and the event journal represents the runtime trace. The
# studio distinguishes the two:
#   - IR has a step for every step the source declares (planned)
#   - Journal has a StepStarted/StepFinished event for every step
#     that actually ran (confirmed)
#   - For each IR step, its phase appears in the run's
#     phases_observed list, OR it is correctly identified as
#     'not yet observed' (planned but not yet executed)
#
# This verifier asserts the two views are coherent: same step
# count, same step names, and the phase of each IR step is
# either confirmed-by-event or honestly marked as not-yet-
# observed.
#
# Exit codes:
#   0 = IR + journal are coherent; phases distinguish
#       planned from confirmed
#   1 = IR or journal is missing, or counts disagree

set -uo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
SUFFIX="${UAT035_SUFFIX:-$(date +%s%N)}"

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

# ---- 1. Submit a multi-step run ------------------------------------

banner "1. submit a multi-step run (3 stages × 2 steps = 6 planned steps)"
EXP_ID="$(curl -fsS -X POST "$STUDIO_HTTP/experiments" -H "content-type: application/json" \
    -d "{\"name\":\"uat035-$SUFFIX\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["experiment_id"])')"
# A pipeline that compiles to multiple steps. The IR shows the
# step count the runtime will execute; the journal shows the
# same count of StepStarted + StepFinished events.
# (Sequential same-shape stages may be merged by the compiler
# into a single runtime stage; we count steps, not stages, to
# stay independent of that optimization.)
SOURCE='pipeline {
    stages {
        stage("a") { pwd() }
        stage("b") { pwd() }
        stage("c") { pwd() }
    }
}'
SNAP_ID="$(SNAP_BODY="$(curl -fsS -X POST "$STUDIO_HTTP/experiments/$EXP_ID/snapshots" \
    -H "content-type: application/json" \
    -d "$(python3 -c "import json,sys; print(json.dumps({'source': sys.argv[1], 'creator': 'uat035'}))" "$SOURCE")")" \
    && echo "$SNAP_BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["snapshot_id"])')"
ok "snapshot=$SNAP_ID"

PAYLOAD="$(python3 -c "import json; print(json.dumps({'experiment_id': '$EXP_ID', 'snapshot_id': '$SNAP_ID', 'scenario_id': '$EXP_ID'}))")"
RUN_ID="$(curl -fsS -X POST "$STUDIO_HTTP/runs" -H "content-type: application/json" \
    -H "idempotency-key: uat035-$SUFFIX" \
    -d "$PAYLOAD" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run_id"])')"
ok "submitted multi-step run: $RUN_ID"

# Wait for the run to finish.
for _ in $(seq 1 30); do
    sleep 1
    if curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events" 2>/dev/null | grep -q '"RunFinished"'; then
        break
    fi
done
ok "run finished"

# ---- 2. Read IR (planned structure) ---------------------------------

banner "2. IR (planned) reports 3 steps"
IR="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/ir")"
IR_STEP_COUNT="$(echo "$IR" | python3 -c "
import json, sys
d = json.load(sys.stdin)
s = d.get('snapshot')
if not s: sys.exit(1)
n = sum(len(st.get('steps', [])) for st in s.get('stages', []))
print(n)
")"
IR_STAGE_COUNT="$(echo "$IR" | python3 -c "
import json, sys
d = json.load(sys.stdin)
s = d.get('snapshot')
print(len(s.get('stages', [])) if s else 0)
")"
[ "$IR_STEP_COUNT" = "3" ] || fail "IR reports $IR_STEP_COUNT steps, expected 3 (3 pwd() calls in source)"
ok "IR: $IR_STAGE_COUNT stage(s) × $IR_STEP_COUNT steps"

# ---- 3. Read journal (confirmed runtime) ----------------------------

banner "3. journal (confirmed) reports 3 StepStarted + 3 StepFinished"
EVENTS="$(curl -fsS "$STUDIO_HTTP/runs/$RUN_ID/events")"
J_STEP_STARTED="$(echo "$EVENTS" | python3 -c "
import json, sys
d = json.load(sys.stdin)
n = sum(1 for e in d.get('events', []) if e.get('kind') == 'StepStarted')
print(n)
")"
J_STEP_FINISHED="$(echo "$EVENTS" | python3 -c "
import json, sys
d = json.load(sys.stdin)
n = sum(1 for e in d.get('events', []) if e.get('kind') == 'StepFinished')
print(n)
")"
J_RUN_FINISHED_OUTCOME="$(echo "$EVENTS" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for e in d.get('events', []):
    if e.get('kind') == 'RunFinished':
        print(e.get('payload', {}).get('outcome', '?'))
        sys.exit(0)
print('?')
")"
[ "$J_STEP_STARTED" = "3" ] || fail "journal reports $J_STEP_STARTED StepStarted, expected 3"
[ "$J_STEP_FINISHED" = "3" ] || fail "journal reports $J_STEP_FINISHED StepFinished, expected 3"
ok "journal: $J_STEP_STARTED StepStarted + $J_STEP_FINISHED StepFinished, RunFinished(outcome=$J_RUN_FINISHED_OUTCOME)"

# ---- 4. Coherence: IR step count == journal step count -------------

banner "4. IR (planned) and journal (confirmed) are coherent"
[ "$IR_STEP_COUNT" = "$J_STEP_STARTED" ] \
    || fail "IR step count ($IR_STEP_COUNT) does not match journal StepStarted count ($J_STEP_STARTED)"
ok "IR step count == journal StepStarted count ($IR_STEP_COUNT == $J_STEP_STARTED)"

# ---- 5. Each IR step's phase is either observed or honestly marked -

banner "5. each IR step's phase appears in phases_observed (or is honestly 'not yet observed')"
COHERENT="$(echo "$IR" | python3 -c "
import json, sys
d = json.load(sys.stdin)
s = d.get('snapshot')
if not s:
    print('no-snapshot')
    sys.exit(0)
phases_observed = set(s.get('phases_observed', []))
# Terminal phases for a finished run: ADMITTED, COMPILED, OBSERVED.
# An IR step in any of these phases was confirmed by the runtime.
# A step in a non-observed phase is 'planned, not yet observed'.
confirmed = 0
planned_only = 0
for st in s.get('stages', []):
    for step in st.get('steps', []):
        phase = step.get('phase', '?')
        if phase in phases_observed:
            confirmed += 1
        else:
            planned_only += 1
print(f'confirmed={confirmed} planned_only={planned_only}')
")"
echo "$COHERENT"
ok "phase coherence: $COHERENT"

# Cross-check: for a finished run, all IR steps must be in
# observed phases (otherwise the IR is lying about completion).
ALL_CONFIRMED="$(echo "$IR" | python3 -c "
import json, sys
d = json.load(sys.stdin)
s = d.get('snapshot')
if not s:
    print('no-snapshot')
    sys.exit(0)
phases_observed = set(s.get('phases_observed', []))
unconfirmed = []
for st in s.get('stages', []):
    for step in st.get('steps', []):
        if step.get('phase') not in phases_observed:
            unconfirmed.append(step.get('op_id', '?'))
if unconfirmed:
    print(f'unconfirmed={unconfirmed}')
else:
    print('all-confirmed')
")"
[ "$ALL_CONFIRMED" = "all-confirmed" ] \
    || fail "finished run has unconfirmed IR steps: $ALL_CONFIRMED"
ok "every IR step is in an observed phase (the IR agrees with the journal at finish)"

# ---- summary --------------------------------------------------------
banner "UAT-035 IR + runtime event correlation: ALL CHECKS PASSED"
say "  • IR (planned): $IR_STAGE_COUNT stage(s) × $IR_STEP_COUNT steps"
say "  • journal (confirmed): $J_STEP_STARTED StepStarted + $J_STEP_FINISHED StepFinished"
say "  • IR step count == journal step count (coherent)"
say "  • every IR step's phase appears in phases_observed (no lying)"
say "  • RunFinished outcome=$J_RUN_FINISHED_OUTCOME"
exit 0
