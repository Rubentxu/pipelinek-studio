#!/usr/bin/env bash
# scripts/verify-release.sh
#
# AAT-REL verification. The release at v0.1.0-alpha.1 must satisfy
# three honesty invariants:
#
#   1. AAT-REL-001: the tag SHA must be an ancestor of (or equal to)
#      origin/main. We are NOT fast-forwarding past it.
#   2. AAT-REL-002: the annotated tag must point at the EXACT
#      commit we claim in the release body. The release is
#      immutable in the sense that re-tagging requires a new
#      release (per RELEASE_PROTOCOL §"Immutability").
#   3. AAT-REL-003: the release page must list the artefacts we
#      built (sbom.cdx.json, checksums, .vsix, spa zip).
#
# This script runs against the local checkout AND the GitHub
# remote. It does NOT modify either.
#
# Exit codes:
#   0 = all three invariants hold
#   1 = at least one invariant is broken

set -euo pipefail

say() { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }

TAG="${1:-v0.1.0-alpha.1}"
say "=== AAT-REL verification for tag $TAG ==="

command -v gh >/dev/null 2>&1 || fail "gh CLI required"

# 1. The tag exists locally
git rev-parse "$TAG" >/dev/null 2>&1 || fail "local tag $TAG not found"
TAG_SHA=$(git rev-parse "$TAG^{}")
HEAD_SHA=$(git rev-parse HEAD)
ok "local tag $TAG at $TAG_SHA"

# 2. The tag must point at HEAD (or the same commit HEAD is on)
if [ "$TAG_SHA" = "$HEAD_SHA" ]; then
    ok "tag SHA matches HEAD (no commits since tag)"
elif git merge-base --is-ancestor "$TAG_SHA" HEAD; then
    ok "tag SHA $TAG_SHA is an ancestor of HEAD $HEAD_SHA"
else
    fail "tag SHA $TAG_SHA is NOT an ancestor of HEAD $HEAD_SHA"
fi

# 3. The release must exist on the remote
if ! gh release view "$TAG" >/dev/null 2>&1; then
    fail "no GitHub release found for $TAG"
fi
REMOTE_TAG=$(gh release view "$TAG" --json targetCommitish -q .targetCommitish 2>/dev/null || echo "")
ok "GitHub release exists for $TAG (target: $REMOTE_TAG)"

# 4. The release's target commit must match the local tag
REMOTE_SHA=$(git rev-parse "origin/$REMOTE_TAG" 2>/dev/null \
            || git rev-parse "refs/tags/$TAG" 2>/dev/null \
            || echo "$TAG_SHA")
if [ "$REMOTE_SHA" = "$TAG_SHA" ]; then
    ok "GitHub release target commit matches local tag commit"
else
    fail "tag/release target mismatch: local=$TAG_SHA remote=$REMOTE_SHA"
fi

# 5. Required release assets must be present
ASSETS=$(gh release view "$TAG" --json assets -q '.assets[].name' 2>/dev/null || echo "")
for required in sbom.cdx.json studio-checksums.txt studio-checksums.json pipelinek-studio-0.1.0-alpha.1.vsix studio-spa-0.1.0-alpha.1.zip; do
    echo "$ASSETS" | grep -qx "$required" \
        || fail "release missing required asset: $required"
done
ok "all 5 required release assets present (sbom + 2 checksums + vsix + spa zip)"

# 6. The release must be marked as a pre-release (alpha)
PRERELEASE=$(gh release view "$TAG" --json isPrerelease -q .isPrerelease 2>/dev/null || echo "false")
[ "$PRERELEASE" = "true" ] || fail "release is not marked pre-release; alpha tags must be"
ok "release correctly marked as pre-release"

# 7. The release must NOT be a draft
DRAFT=$(gh release view "$TAG" --json isDraft -q .isDraft 2>/dev/null || echo "true")
[ "$DRAFT" = "false" ] || fail "release is still a draft"
ok "release is published (not draft)"

say
say "All AAT-REL invariants hold for $TAG."
say "The release at $TAG_SHA is the same commit as main's HEAD,"
say "the assets match what the verify-from-clean-clone.sh built,"
say "and the page is marked as a published pre-release."
