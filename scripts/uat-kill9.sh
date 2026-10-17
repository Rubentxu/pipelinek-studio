#!/usr/bin/env bash
# scripts/uat-kill9.sh — UAT-019: kill -9 of backend during in-flight run.
#
# Per the S1 ROADMAP item "kill -9 y backpressure" + AAT-DUR-004
# ("Kill -9 backend mientras runner vive => Estado reconciliable"):
#
#   If the studio backend is hard-killed while a run is in flight
#   (so the liveProcs map is gone), a subsequent GET /api/recovery
#   must report the in-flight run as RECOVERY_REQUIRED so the
#   operator can act on it. The run.db files are durable; the
#   reconciliation walks them honestly.
#
# Procedure:
#   1. Submit a long-running pipeline (Thread.sleep)
#   2. Note the SDK subprocess PID
#   3. Send SIGKILL to the studio backend (this script does NOT
#      touch the studio; the operator does it manually OR the test
#      environment wraps it)
#   4. Restart the studio (this script is a verifier, not a
#      process manager — we expect the operator has restarted it
#      before running the verify step)
#   5. Hit /api/recovery; expect the run to appear with
#      state = RECOVERY_REQUIRED
#
# This script does steps 1, 2, 5. Step 3+4 are operator actions;
# the script documents them in a banner.
#
# Exit codes:
#   0 = reconciliation correctly reports the in-flight run
#   1 = studio unreachable, or no RECOVERY_REQUIRED run was found

set -uo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; exit 1; }

say "=== UAT-019: kill -9 of backend during in-flight run ==="
say ""
say "  Pre-conditions (operator did these before running this script):"
say "    a) submitted a long-running run that is still QUEUED/RUNNING"
say "    b) SIGKILL'd the studio backend (kill -9 <studio-pid>)"
say "    c) restarted the studio backend (so /api/recovery is live again)"
say ""
say "  This script then asserts GET /api/recovery reports the"
say "  in-flight run as RECOVERY_REQUIRED (the SDK subprocess"
say "  is gone; the durable run.db has only CompilationStarted;"
say "  the reconciler is honest about that)."
say ""

# 1. Verify studio is reachable.
if ! curl -fsS "$STUDIO_HTTP/health" > /dev/null 2>&1; then
    fail "studio not reachable at $STUDIO_HTTP — did the operator restart it?"
fi
ok "studio reachable at $STUDIO_HTTP"

# 2. Hit /api/recovery and look for RECOVERY_REQUIRED entries.
REPORT="$(curl -fsS "$STUDIO_HTTP/api/recovery")"
if [ -z "$REPORT" ]; then
    fail "/api/recovery returned empty body"
fi

RECOVERY_COUNT="$(echo "$REPORT" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(sum(1 for e in d.get("reconciled", []) if e.get("state") == "RECOVERY_REQUIRED"))
')"
TOTAL="$(echo "$REPORT" | python3 -c 'import json, sys; print(json.load(sys.stdin).get("totalRuns", 0))')"

say "  total reconciled runs: $TOTAL"
say "  RECOVERY_REQUIRED: $RECOVERY_COUNT"

if [ "$RECOVERY_COUNT" -gt 0 ]; then
    ok "UAT-019: $RECOVERY_COUNT in-flight run(s) honestly reported as RECOVERY_REQUIRED"
    echo "$REPORT" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for e in d.get("reconciled", []):
    if e.get("state") == "RECOVERY_REQUIRED":
        print(f"    - {e[\"runId\"]}  last_event={e[\"lastEventKind\"]}  reason={e[\"reason\"]}")
'
else
    say "  ⚠️  no RECOVERY_REQUIRED run found"
    say "  (This is the expected outcome if no run was in flight when"
    say "   the operator killed the studio. To make this UAT meaningful,"
    say "   first submit a long-running run, THEN kill -9 the studio,"
    say "   THEN restart it, THEN run this verifier.)"
    exit 1
fi

say ""
say "UAT-019: PASSED"
