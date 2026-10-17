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
#   TAG defaults to pipelinek-studio:<studio.version from
#   gradle.properties>, the image docker/build.sh builds from this checkout.
#   It is never a release literal: the old hardcoded `rc.11` pin pointed at an
#   image this checkout cannot build, so every child failed for a non-defect.
#
# The aggregator owns no transport of its own: each child declares the TLS
# endpoint it bound and exports the certificate its own container generated
# (scripts/lib/studio-http.sh).
#
# C0.3 (2026-10-12): the find pattern MUST exclude the
# aggregator itself. The previous pattern
# `uat-podman-*.sh` matched `uat-podman-all.sh`, so the
# aggregator discovered and re-ran itself. We now use
# the more specific pattern `uat-podman-NNN.sh` (three
# digits, optional `.N` for scenarios).

set -uo pipefail

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; }
die_usage() { say "  ❌ $*"; exit 2; }
banner() { printf '\n== %s ==\n' "$*"; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
# Per-run scratch for the captured child output: an ignored path inside this
# checkout, never the host TMPDIR or a shared location.
SCRATCH_DIR="$REPO_ROOT/build/operational-podman/uat-all-$$"

# The image under test is the one this checkout builds, so the expected tag is
# derived from gradle.properties instead of a release literal.
STUDIO_VERSION="$(sed -n 's/^studio\.version=//p' "$REPO_ROOT/gradle.properties" 2>/dev/null | head -1)"
[ -n "$STUDIO_VERSION" ] || die_usage "gradle.properties has no studio.version (cannot derive the expected image tag)"
TAG="${1:-pipelinek-studio:$STUDIO_VERSION}"

# Invoked indirectly by the EXIT trap; shellcheck's reachability analysis stops
# at this script's explicit `exit` at the end, so the call is invisible to it.
# shellcheck disable=SC2329
cleanup() {
    if [ -n "$SCRATCH_DIR" ]; then rm -rf "$SCRATCH_DIR"; fi
}
trap cleanup EXIT

# C0.3 (2026-10-12): explicit pattern. The aggregator
# itself is `uat-podman-all.sh` (not a 3-digit UAT id)
# and MUST NOT be in the list. Defends against the
# self-discovery bug the mandate called out.
mapfile -t UATS < <(
    find "$REPO_ROOT/scripts" -maxdepth 1 \
        -regextype posix-extended \
        -regex '.*/uat-podman-[0-9]+(\.[0-9]+)?\.sh$' \
        -type f -executable | sort
)

[ "${#UATS[@]}" -ge 1 ] || { say "no uat-podman-NNN.sh scripts found"; exit 2; }

banner "UAT-039/040 aggregator: ${#UATS[@]} Podman UAT(s) to run against $TAG"

mkdir -p "$SCRATCH_DIR"

PASSED=0
FAILED=0
FAILED_NAMES=()

for UAT in "${UATS[@]}"; do
    NAME="$(basename "$UAT")"
    CHILD_LOG="$SCRATCH_DIR/$NAME.log"
    say ""
    say "  → running $NAME"
    if bash "$UAT" "$TAG" >"$CHILD_LOG" 2>&1; then
        ok "$NAME"
        PASSED=$((PASSED + 1))
    else
        fail "$NAME"
        FAILED=$((FAILED + 1))
        FAILED_NAMES+=("$NAME")
        say "  --- last 15 lines of $NAME ---"
        tail -15 "$CHILD_LOG" | sed 's/^/    /'
        say "  --------------------------------"
    fi
done

banner "UAT-039/040 summary"
say "  image:  $TAG"
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
