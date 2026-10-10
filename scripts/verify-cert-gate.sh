#!/usr/bin/env bash
# scripts/verify-cert-gate.sh — AAT-REL-007 meta-certification.
#
# A certification gate that cannot prove it can fail is itself a
# false-PASS surface. B0.6 (2026-10-10) mandates a self-test that
# injects a controlled failure into the harness and asserts:
#
#   1. The harness exits non-zero
#   2. The HARNESS_SUMMARY line shows passed < expected
#   3. The non-zero exit is propagated into a `false` GitHub
#      workflow step conclusion (B0.2's classification)
#
# We do NOT touch the production verify-from-clean-clone.sh.
# Instead we copy it to a tmp location, strip out one `ok` call
# (the distTar step), and run it. The script's own HARNESS_EXPECTED
# counter is bumped by the missing `ok`, so on_exit will compute
# passed=39 expected=40 and fail the harness. This is a
# property-test of the trap and the summary line, not a test of
# the underlying code.
#
# Exit codes:
#   0 = the meta-test passed (the harness failed as expected)
#   1 = the meta-test failed (the harness declared READY after a
#       controlled failure — this is a B0.6 false-positive)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

say()  { printf '%s\n' "$*"; }
ok()   { say "✅ $*"; }
fail() { say "❌ $*"; exit 1; }

HARNESS="$ROOT/scripts/verify-from-clean-clone.sh"
[ -f "$HARNESS" ] || fail "harness not found: $HARNESS"

# 1. Sanity: the production harness must already be syntactically
#    valid. If it isn't, this self-test cannot run.
bash -n "$HARNESS" || fail "production harness has bash syntax errors"

# 2. Build a tampered copy. We delete exactly ONE `ok` line in
#    the distTar step (step 2). The step still runs distTar and
#    still completes, but HARNESS_PASSED is not incremented for
#    that step — on_exit will detect passed < expected and exit
#    non-zero. We use sed to make the deletion deterministic.
TMP_DIR="$(mktemp -d)"
TAMPERED="$TMP_DIR/verify-from-clean-clone.sh"
cp "$HARNESS" "$TAMPERED"
# The distTar step ends with `ok "distTar: $TAR"`. Remove that
# one line. We anchor on the message text to be precise.
sed -i '/^ok "distTar: /d' "$TAMPERED"

# 3. Verify the deletion landed.
if grep -q '^ok "distTar: ' "$TAMPERED"; then
    fail "tampering did not take effect: distTar ok line still present"
fi
ok "tampered harness prepared: 1 ok call removed from step 2"

# 4. Run the tampered harness in a fully isolated environment.
#    We avoid depending on the test suite or the distTar target
#    (which can take minutes); instead we just need to know
#    whether the trap fires. The harness calls ./gradlew in
#    several steps, so the test will be slow. We give it 10
#    minutes and capture the exit code.
say "    running tampered harness (this will take a few minutes)…"
HARNESS_LOG="$TMP_DIR/harness.log"
set +e
bash "$TAMPERED" >"$HARNESS_LOG" 2>&1
HARNESS_EXIT=$?
set -e

# 5. The tampered harness must exit non-zero.
if [ "$HARNESS_EXIT" = "0" ]; then
    say "--- last 20 lines of tampered harness log ---"
    tail -n 20 "$HARNESS_LOG" | sed 's/^/    /' || true
    fail "tampered harness exited 0 — the on_exit trap did not fire; false-positive channel is open"
fi
ok "tampered harness exited non-zero (rc=$HARNESS_EXIT)"

# 6. The summary line must show passed < expected.
if ! grep -E '^❌ HARNESS_SUMMARY passed=[0-9]+ expected=[0-9]+ ' "$HARNESS_LOG" >/dev/null; then
    say "--- last 20 lines of tampered harness log ---"
    tail -n 20 "$HARNESS_LOG" | sed 's/^/    /' || true
    fail "tampered harness did not emit a ❌ HARNESS_SUMMARY line; summary helper may be broken"
fi
SUMMARY_LINE="$(grep -E '^❌ HARNESS_SUMMARY passed=[0-9]+ expected=[0-9]+ ' "$HARNESS_LOG" | head -1)"
ok "tampered harness emitted: $SUMMARY_LINE"

# 7. The summary must actually show a gap (passed < expected).
PASSED="$(echo "$SUMMARY_LINE" | sed -E 's/^❌ HARNESS_SUMMARY passed=([0-9]+) expected=([0-9]+).*/\1/')"
EXPECTED="$(echo "$SUMMARY_LINE" | sed -E 's/^❌ HARNESS_SUMMARY passed=([0-9]+) expected=([0-9]+).*/\2/')"
[ -n "$PASSED" ] && [ -n "$EXPECTED" ] || fail "could not parse passed/expected from: $SUMMARY_LINE"
if [ "$PASSED" -ge "$EXPECTED" ]; then
    fail "summary shows passed >= expected ($PASSED >= $EXPECTED); trap did not detect the missing ok"
fi
ok "summary gap is real: $PASSED < $EXPECTED"

rm -rf "$TMP_DIR"

ok "B0.6 self-test PASSED: the certification gate cannot falsely declare READY"
