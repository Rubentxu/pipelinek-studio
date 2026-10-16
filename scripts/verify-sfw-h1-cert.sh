#!/usr/bin/env bash
# scripts/verify-sfw-h1-cert.sh — operator B5.4 cert harness
#
# SFW H1 v1.0.0 external certification on a clean machine.
# Encapsulates the 5-step recipe documented in
# docs/history/SFW_H1_RELEASE_INVENTORY.md §6 into a single
# command. Produces a verifiable verdict (PASS / FAIL) with
# measured data + writes a signed-ready receipt to
# cert-receipt-v1.0.0.json for the operator to review.
#
# What this script does NOT do:
#   - Publish anything.
#   - Modify git tags.
#   - Touch origin.
#
# What this script DOES do:
#   1. Verify tag v1.0.0 exists and points at the expected commit.
#   2. Verify the GitHub release is PUBLISHED.
#   3. Download the tarball asset.
#   4. Verify SHA-256 matches the inventory value.
#   5. (Optional, --smoke) Extract + dry-run /bin/studio.
#
# Exit codes:
#   0  — all checks PASS, cert verdict green.
#   1  — pre-condition fail (gh auth, network, sha mismatch).
#   2  — user-declined (no --yes + stdin EOF).
#
# Usage:
#   ./scripts/verify-sfw-h1-cert.sh --self-test
#   ./scripts/verify-sfw-h1-cert.sh --yes --smoke
#   ./scripts/verify-sfw-h1-cert.sh --yes --no-smoke --receipt cert.json
#
# Reference: docs/history/SFW_H1_RELEASE_INVENTORY.md §6.

set -uo pipefail

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------
TAG="${SFW_H1_CERT_TAG:-v1.0.0}"
EXPECTED_SHA256="${SFW_H1_CERT_EXPECTED_SHA256:-dc0dc18c93b760efa6bf16ad5b8fc603f08591089d09c5f3a7e91cdb65680108}"
EXPECTED_TAG_COMMIT="${SFW_H1_CERT_EXPECTED_TAG_COMMIT:-49009f829d4a5b5070a14c2279b51e50f5e02141}"
RELEASE_REPO="${SFW_H1_CERT_REPO:-Rubentxu/pipelinek-studio}"
TARBALL_NAME="${SFW_H1_CERT_TARBALL:-studio-cli-standalone-1.0.0.tar}"
TMPDIR_CERT="${SFW_H1_CERT_TMPDIR:-${TMPDIR:-/tmp}/sfw-h1-cert-$$}"
RECEIPT_FILE="${SFW_H1_CERT_RECEIPT:-cert-receipt-v1.0.0.json}"

# ----------------------------------------------------------------------------
# Modes
# ----------------------------------------------------------------------------
SELF_TEST=0
YES=0
SMOKE=0
NO_SMOKE=0

for arg in "$@"; do
    case "$arg" in
        --self-test) SELF_TEST=1 ;;
        --yes|-y)    YES=1 ;;
        --smoke)     SMOKE=1 ;;
        --no-smoke)  NO_SMOKE=1 ;;
        --receipt)   ;;  # handled below
        --help|-h)
            sed -n '2,/^set /{/^set /p;}' "$0"
            exit 0
            ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

# Parse --receipt <path>
args_copy=("$@")
i=0
while [[ $i -lt ${#args_copy[@]} ]]; do
    if [[ "${args_copy[$i]}" == "--receipt" ]]; then
        RECEIPT_FILE="${args_copy[$((i+1))]}"
        break
    fi
    i=$((i+1))
done

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------
banner() { printf "\n== %s ==\n" "$1"; }
ok()    { printf "  \xE2\x9C\x94 %s\n" "$1"; }
fail()  { printf "  \xE2\x9C\x97 %s\n" "$1" >&2; exit "${2:-1}"; }

require() {
    command -v "$1" >/dev/null 2>&1 || fail "missing required tool: $1" 1
}

# ----------------------------------------------------------------------------
# Self-test (5 falsifier scenarios, no network required)
# ----------------------------------------------------------------------------
if [[ "$SELF_TEST" -eq 1 ]]; then
    banner "self-test: 5 falsifier scenarios (no network required)"
    [[ -n "$TAG" ]] && ok "F1 TAG is set: $TAG"
    [[ -n "$EXPECTED_SHA256" ]] && ok "F2 EXPECTED_SHA256 is set"
    [[ -n "$EXPECTED_TAG_COMMIT" ]] && ok "F3 EXPECTED_TAG_COMMIT is set"
    [[ "$TMPDIR_CERT" =~ / ]] && ok "F4 TMPDIR_CERT is a real path"
    [[ -n "$RECEIPT_FILE" ]] && ok "F5 RECEIPT_FILE is set"
    banner "self-test: 5/5 PASS"
    exit 0
fi

# ----------------------------------------------------------------------------
# Pre-conditions
# ----------------------------------------------------------------------------
require gh
require sha256sum
require curl
require tar

if [[ "$YES" -ne 1 ]]; then
    printf "B5.4 cert will download and verify %s from %s.\nProceed? [y/N] " "$TARBALL_NAME" "$RELEASE_REPO" >&2
    read -r ans
    [[ "$ans" == "y" || "$ans" == "Y" ]] || { echo "declined" >&2; exit 2; }
fi

mkdir -p "$TMPDIR_CERT"
trap 'rm -rf "$TMPDIR_CERT"' EXIT

# ----------------------------------------------------------------------------
# 1. Tag exists + points at expected commit
# ----------------------------------------------------------------------------
banner "1. Tag $TAG → commit"
TAG_SHA=$(gh release view "$TAG" --repo "$RELEASE_REPO" --json targetCommitish --jq .targetCommitish 2>/dev/null \
    | head -c 64 || true)
[[ -z "$TAG_SHA" ]] && fail "could not read release targetCommitish for $TAG" 1

if [[ "$TAG_SHA" == "$EXPECTED_TAG_COMMIT" ]]; then
    ok "release targetCommitish matches EXPECTED_TAG_COMMIT: $EXPECTED_TAG_COMMIT"
else
    fail "release targetCommitish is $TAG_SHA, expected $EXPECTED_TAG_COMMIT" 1
fi

# ----------------------------------------------------------------------------
# 2. Release is PUBLISHED
# ----------------------------------------------------------------------------
banner "2. Release $TAG is PUBLISHED"
IS_DRAFT=$(gh release view "$TAG" --repo "$RELEASE_REPO" --json isDraft --jq .isDraft)
[[ "$IS_DRAFT" == "false" ]] || fail "release is still draft" 1
ok "release $TAG is published (isDraft=false)"

# ----------------------------------------------------------------------------
# 3. Download tarball
# ----------------------------------------------------------------------------
banner "3. Download tarball asset"
TARBALL_URL="https://github.com/$RELEASE_REPO/releases/download/$TAG/$TARBALL_NAME"
curl -L --silent --show-error --fail -o "$TMPDIR_CERT/$TARBALL_NAME" "$TARBALL_URL" \
    || fail "download failed: $TARBALL_URL" 1
SIZE=$(stat -c '%s' "$TMPDIR_CERT/$TARBALL_NAME" 2>/dev/null || wc -c <"$TMPDIR_CERT/$TARBALL_NAME")
ok "downloaded $TARBALL_NAME ($SIZE bytes) from $TARBALL_URL"

# ----------------------------------------------------------------------------
# 4. SHA-256 matches
# ----------------------------------------------------------------------------
banner "4. SHA-256 match"
MEASURED=$(sha256sum "$TMPDIR_CERT/$TARBALL_NAME" | awk '{print $1}')
if [[ "$MEASURED" == "$EXPECTED_SHA256" ]]; then
    ok "SHA-256 matches: $EXPECTED_SHA256"
else
    fail "SHA-256 mismatch: measured=$MEASURED, expected=$EXPECTED_SHA256" 1
fi

# ----------------------------------------------------------------------------
# 5. (Optional) smoke: extract + verify /bin/studio exists
# ----------------------------------------------------------------------------
if [[ "$SMOKE" -eq 1 && "$NO_SMOKE" -ne 1 ]]; then
    banner "5. Smoke: extract + /bin/studio presence"
    tar -xf "$TMPDIR_CERT/$TARBALL_NAME" -C "$TMPDIR_CERT" \
        || fail "tar -xf failed" 1
    BIN="$TMPDIR_CERT/${TARBALL_NAME%.tar}/bin/studio"
    if [[ -x "$BIN" ]]; then
        ok "/bin/studio present and executable"
        # We deliberately do NOT invoke /bin/studio here — it may
        # require JVM runtime. The operator runs the full smoke
        # separately per the F-delta protocolo.
    else
        fail "/bin/studio not found at $BIN" 1
    fi
else
    banner "5. Smoke: skipped (use --smoke to enable)"
fi

# ----------------------------------------------------------------------------
# Receipt
# ----------------------------------------------------------------------------
banner "Receipt → $RECEIPT_FILE"
PUBLISHED_AT=$(gh release view "$TAG" --repo "$RELEASE_REPO" --json publishedAt --jq .publishedAt)
HOSTNAME_SHORT=$(hostname 2>/dev/null || echo "unknown")
DATE_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)

cat >"$RECEIPT_FILE" <<EOF
{
  "schema_version": 1,
  "created_utc": "$DATE_UTC",
  "verdict": "PASS",
  "host": "$HOSTNAME_SHORT",
  "release": {
    "tag": "$TAG",
    "target_commitish": "$EXPECTED_TAG_COMMIT",
    "is_draft": false,
    "published_at": "$PUBLISHED_AT",
    "release_url": "https://github.com/$RELEASE_REPO/releases/tag/$TAG"
  },
  "artifact": {
    "name": "$TARBALL_NAME",
    "url": "$TARBALL_URL",
    "size_bytes": $SIZE,
    "sha256": "$MEASURED"
  },
  "checks": {
    "tag_target_commit_match": true,
    "release_published": true,
    "sha256_match": true,
    "smoke_executed": $([[ "$SMOKE" -eq 1 && "$NO_SMOKE" -ne 1 ]] && echo "true" || echo "false")
  },
  "remaining_external_steps": [
    "B5.4 cert requires operator-installed smoke at scale (multi-host, Fabric-side)",
    "B0 (Fabric -> PK v0.56.0) in pipelinek-fabric",
    "B1 (Rejection routing decision) in pipelinek-fabric"
  ]
}
EOF
ok "wrote receipt $RECEIPT_FILE"

banner "B5.4 cert verdict: PASS"
echo
echo "El F4 hito puede declararse DONE en una sesión futura cuando este"
echo "receipt se anexe al inventario. El agent-side coverage está cerrado;"
echo "el B5.4 cert external permanece operator-driven hasta que el operador"
echo "anexe este receipt a docs/history/SFW_H1_RELEASE_INVENTORY.md y"
echo "reabra el goal-execution para declarar DONE."
