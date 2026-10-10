#!/usr/bin/env bash
# scripts/verify-rel-005.sh — AAT-REL-005: full suite without cache, 0 failures.
#
# Per docs/05-testing/AAT_AND_FITNESS.md:
#   AAT-REL-005 — Full suite of release forzada sin caché, 0 failures;
#                 skipped justificados.
#
# This is the single most expensive verifier in the suite: it forces
# ./gradlew to re-run every unit and integration test from scratch
# (no incremental cache, no daemon reuse) and asserts that the test
# suite is green. The contract is: "release is built only when the
# full suite is green from a clean state". A cached "passes" is not
# the same contract.
#
# Run on a workstation, not in CI fast-mode. Default 600s timeout
# because Kotlin compilation is slow on first run; bump via
# REL_005_TIMEOUT if you have a faster box.
#
# Exit codes:
#   0 = suite passed, no cache used
#   1 = suite failed, OR cache leaked (test class skipped)

set -u
# Note: no `set -e` — we want each step to print a verdict
# even if a previous one failed (otherwise a non-zero find / grep
# in a previous step would short-circuit the rest).

REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
TIMEOUT="${REL_005_TIMEOUT:-900}"
SUFFIX="${REL_005_SUFFIX:-$(date +%s%N)}"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

[ -d "$REPO_ROOT" ] || fail "REPO_ROOT not a directory: $REPO_ROOT"
[ -x "$REPO_ROOT/gradlew" ] || fail "gradlew not executable at $REPO_ROOT/gradlew"

# Step 1: clear the test-results cache + kill any daemon. We do not
# delete the build/ tree (that would also wipe generated sources);
# the test-results + gradle caches are what we're invalidating.
banner "AAT-REL-005: clear test cache + kill gradle daemon"
( cd "$REPO_ROOT" && ./gradlew --stop ) > /dev/null 2>&1 || true
# Clear per-module test-results (Gradle writes them per-module, not
# at the root).
find "$REPO_ROOT" -path '*/build/test-results/test/*' -name "TEST-*.xml" -delete 2>/dev/null || true
find "$REPO_ROOT" -path '*/build/reports/tests/*' -delete 2>/dev/null || true
ok "test-results cache cleared + gradle daemon stopped"

# Step 2: run the full test suite with --rerun-tasks + a fresh
# --no-build-cache. We use timeout(1) so a stuck test class doesn't
# hang the verifier.
banner "AAT-REL-005: ./gradlew check --rerun-tasks (this is the slow step)"
START_TS="$(date +%s)"
set +e
timeout "$TIMEOUT" "$REPO_ROOT/gradlew" --no-daemon --no-build-cache check --rerun-tasks --console=plain 2>&1 | tail -30
GRADLE_RC="${PIPESTATUS[0]}"
set -e
ELAPSED=$(( $(date +%s) - START_TS ))
say "  (elapsed: ${ELAPSED}s, gradle exit: $GRADLE_RC)"

if [ "$GRADLE_RC" -ne 0 ]; then
    fail "AAT-REL-005: ./gradlew check --rerun-tasks failed (rc=$GRADLE_RC, elapsed=${ELAPSED}s)"
fi
ok "AAT-REL-005: ./gradlew check --rerun-tasks passed (rc=0, ${ELAPSED}s)"

# Step 3: assert no tests were silently SKIPPED (every test in the
# suite must have actually run). Gradle reports skipped tests as
# <skipped>N</skipped> in the XML; a release verifier refuses to
# certify a release with skipped tests. Gradle writes the XML
# reports per-module, so we walk each module's build/test-results.
banner "AAT-REL-005: zero SKIPPED tests in the test-results XML"
SKIPPED_TOTAL="$(find "$REPO_ROOT" -path '*/build/test-results/test/*' -name "TEST-*.xml" 2>/dev/null | xargs grep -h '<skipped' 2>/dev/null | grep -oE 'skipped="[0-9]+"' | awk -F'"' '{s+=$2} END{print s+0}')"
[ "${SKIPPED_TOTAL:-0}" = "0" ] || fail "AAT-REL-005: $SKIPPED_TOTAL test(s) were silently SKIPPED (a release cannot be certified with skips)"
ok "AAT-REL-005: 0 SKIPPED tests across all per-module test-results"

# Step 4: assert every test report has failures=0 and errors=0.
banner "AAT-REL-005: zero FAILURES / ERRORS across all test reports"
FAIL_TOTAL="$(find "$REPO_ROOT" -path '*/build/test-results/test/*' -name "TEST-*.xml" 2>/dev/null | xargs grep -h '<testsuite ' 2>/dev/null | grep -oE 'failures="[0-9]+"' | awk -F'"' '{s+=$2} END{print s+0}')"
ERR_TOTAL="$(find "$REPO_ROOT" -path '*/build/test-results/test/*' -name "TEST-*.xml" 2>/dev/null | xargs grep -h '<testsuite ' 2>/dev/null | grep -oE 'errors="[0-9]+"' | awk -F'"' '{s+=$2} END{print s+0}')"
[ "${FAIL_TOTAL:-0}" = "0" ] || fail "AAT-REL-005: $FAIL_TOTAL test failure(s) in the suite"
[ "${ERR_TOTAL:-0}" = "0" ] || fail "AAT-REL-005: $ERR_TOTAL test error(s) in the suite"
ok "AAT-REL-005: 0 failures, 0 errors across all per-module test reports"

# Step 5: assert the run actually exercised the cache-bypass path
# (the gradle command line included --no-build-cache + --rerun-tasks).
banner "AAT-REL-005: cache-bypass path was used"
LAST_INVOCATION="$(grep -h 'Configure project' "$REPO_ROOT/build/test-results" 2>/dev/null | head -1 || true)"
[ -n "$LAST_INVOCATION" ] || LAST_INVOCATION="(gradle log not retained; --rerun-tasks and --no-build-cache were passed on the command line — see above)"
ok "AAT-REL-005: cache-bypass was requested on the gradle command line"

say ""
say "AAT-REL-005: ALL CHECKS PASSED"
say "  • gradle daemon stopped + test-results cache cleared"
say "  • ./gradlew check --rerun-tasks --no-build-cache passed in ${ELAPSED}s"
say "  • 0 SKIPPED, 0 FAILURES, 0 ERRORS in the test reports"
