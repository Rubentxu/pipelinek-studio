#!/usr/bin/env bash
# scripts/uat-podman-all.sh — R6.2 (2026-10-12)
#
# Aggregator: runs every scripts/uat-podman-*.sh in
# alphabetical order and reports a per-UAT pass/fail.
# This is the UAT-039/040 regression surface for
# R6; each individual UAT is a black box that takes
# a podman-launched studio for its duration.
#
# Exit codes:
#   0 = every UAT passed
#   1 = at least one UAT failed
#   2 = no UAT scripts found
#
# Usage: ./scripts/uat-podman-all.sh [TAG]
#   TAG defaults to pipelinek-studio:1.0.0-rc.7

set -uo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
TAG="${1:-pipelinek-studio:1.0.0-rc.7}"

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; }
banner() { printf '\n== %s ==\n' "$*"; }

mapfile -t UATS < <(find "$REPO_ROOT/scripts" -maxdepth 1 -name "uat-podman-*.sh" -type f -executable | sort)

[ "${#UATS[@]}" -ge 1 ] || { say "no uat-podman-*.sh scripts found"; exit 2; }

banner "UAT-039/040 aggregator: ${#UATS[@]} Podman UAT(s) to run against $TAG"

PASSED=0
FAILED=0
FAILED_NAMES=()

for UAT in "${UATS[@]}"; do
    NAME="$(basename "$UAT")"
    say ""
    say "  → running $NAME"
    if bash "$UAT" "$TAG" >/tmp/uat-podman-out.$$ 2>&1; then
        ok "$NAME"
        PASSED=$((PASSED + 1))
    else
        fail "$NAME"
        FAILED=$((FAILED + 1))
        FAILED_NAMES+=("$NAME")
        say "  --- last 15 lines of $NAME ---"
        tail -15 /tmp/uat-podman-out.$$ | sed 's/^/    /'
        say "  --------------------------------"
    fi
    rm -f /tmp/uat-podman-out.$$
done

banner "UAT-039/040 summary"
say "  passed: $PASSED / $((PASSED + FAILED))"
say "  failed: $FAILED"
if [ "$FAILED" -gt 0 ]; then
    say "  failed UATs:"
    for n in "${FAILED_NAMES[@]}"; do
        say "    - $n"
    done
    exit 1
fi
exit 0
