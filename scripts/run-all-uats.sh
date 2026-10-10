#!/usr/bin/env bash
# scripts/run-all-uats.sh — S5 regression aggregator (UAT-039..040 subset).
#
# Per ROADMAP §"S5 — Product Ready" deliverable "regresión S0..S4":
#   "UAT-039..046, regresión S0..S4"
#
# This script does NOT add new UAT logic; it runs every UAT script
# in scripts/ that begins with `uat-` and aggregates the result. The
# contract is: every UAT added in S0..S4 must still be green at
# release-cut time. A new green UAT becomes part of this set
# automatically (no central list to keep in sync).
#
# Exit codes:
#   0 = every UAT script in scripts/ is green
#   1 = at least one UAT failed
#
# UAT-041..046 (specific S5 release scenarios) are out of scope for
# this aggregator; they test install scenarios that need a clean
# machine + a real release artefact, not just the dev binary.

set -uo pipefail

REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"
SUFFIX="${UAT_RUNNER_SUFFIX:-$(date +%s%N)}"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; }
banner() { printf '\n== %s ==\n' "$*"; }

[ -d "$REPO_ROOT/scripts" ] || { say "scripts/ not found"; exit 1; }
[ -x "$REPO_ROOT/uat-s1-flows.sh" ] || true  # noop: scripts are in $REPO_ROOT/scripts/

# Find every UAT script in alphabetical order
mapfile -t UAT_SCRIPTS < <(find "$REPO_ROOT/scripts" -maxdepth 1 -name "uat-*.sh" -type f -executable | sort)

[ "${#UAT_SCRIPTS[@]}" -ge 1 ] || { say "No UAT scripts found in $REPO_ROOT/scripts"; exit 1; }

banner "UAT-039..040: regression aggregator — ${#UAT_SCRIPTS[@]} UAT script(s) to run"

PASSED=0
FAILED=0
FAILED_NAMES=()

for UAT in "${UAT_SCRIPTS[@]}"; do
    NAME="$(basename "$UAT")"
    say "  → running $NAME"
    # Each UAT script writes its own progress to stdout; we just
    # capture the exit code and the last few lines.
    set +e
    "$UAT" > "/tmp/uat-runner-$NAME-$SUFFIX.log" 2>&1
    RC=$?
    set +e
    if [ "$RC" = "0" ]; then
        ok "$NAME: PASSED"
        PASSED=$((PASSED + 1))
    else
        fail "$NAME: FAILED (rc=$RC, see /tmp/uat-runner-$NAME-$SUFFIX.log)"
        FAILED=$((FAILED + 1))
        FAILED_NAMES+=("$NAME")
    fi
    # Stagger to keep the rate limiter happy. The studio's in-process
    # rate limiter is 60 req / 60s; each UAT can issue 10-30 requests
    # in a few seconds. A 30s stagger between UATs gives each one
    # its own clean 60s window when back-to-back.
    sleep 30
done

banner "UAT-039..040: summary"
say "  passed: $PASSED / ${#UAT_SCRIPTS[@]}"
say "  failed: $FAILED"
if [ "$FAILED" -gt 0 ]; then
    say "  failed scripts:"
    for n in "${FAILED_NAMES[@]}"; do say "    - $n"; done
    exit 1
fi
say ""
say "UAT-039..040: ALL PASSED"
say "  • every uat-*.sh in scripts/ is green"
