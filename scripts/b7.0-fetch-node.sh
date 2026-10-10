#!/usr/bin/env bash
# B7.0 (2026-10-10): pre-fetch the Node
# 20 tarball that nodeBundle consumes.
# B7.x (2026-10-10): also pin the
# SHA-256 against the upstream
# SHASUMS256.txt. The previous version
# verified the size (> 1MB) which
# catches redirect pages and 404
# bodies, but does NOT catch a hostile
# mirror serving a modified binary at
# the right size. The SHA-256 pin
# closes that supply-chain gap.
#
# The Gradle build runs inside a JVM
# sandbox that blocks URL.openStream() to
# remote hosts. The cleanest fix is to
# pre-fetch the tarball with curl (which
# the host shell can run), then let the
# Gradle task just copy from disk.
#
# Usage:
#   scripts/b7.0-fetch-node.sh
#
# Output:
#   build/node-bundle-input/node-vX.Y.Z-<platform>.tar.xz
#
# Idempotent: if the file already exists
# AND its SHA-256 matches the pinned
# hash, the script exits 0 without
# re-downloading. A stale cache with a
# mismatched hash triggers a re-download.

set -euo pipefail

# Pin the same version + base URL the
# Gradle task uses (see
# studio-cli/build.gradle.kts,
# nodeBundleVersionB70 + nodeBaseUrlB70).
NODE_VERSION="v20.18.0"
BASE_URL="https://nodejs.org/dist/${NODE_VERSION}"

# Pinned SHA-256 (lowercase hex) per
# platform. The source of truth is
# SHASUMS256.txt at
# ${BASE_URL}/SHASUMS256.txt; we copy
# the relevant line into the table
# below at release time. Bumping the
# Node version is a 2-step: (1)
# download the new SHASUMS256.txt, (2)
# update both the table and
# NODE_VERSION above.
#
# To regenerate the table: run
#   curl -sSL https://nodejs.org/dist/v<NEW>/SHASUMS256.txt | grep linux-x64
# and copy the hash.
declare -A NODE_SHA256=(
    [linux-x64]="4543670b589593f8fa5f106111fd5139081da42bb165a9239f05195e405f240a"
    [linux-arm64]="a9ce85675ba33f00527f6234d90000946c0936fb4fca605f1891bb5f4fe6fb0a"
    [darwin-x64]="63e150a3bb4f31743257d8597262c6b5f0a2356e7c42002e29d5f7d1bf161f08"
    [darwin-arm64]="678e062bdae3824aa997bd469580a4dda48fd51f61d3679b6ba06352e6cef38f"
)

# Resolve platform tag the same way the
# Gradle task does. The matrix is limited
# to platforms the B6.0 standalone tar
# supports; macOS + Windows are B7.x
# follow-ups.
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m | tr '[:upper:]' '[:lower:]')"

case "${OS}-${ARCH}" in
    linux-x86_64)   PLATFORM="linux-x64" ;;
    linux-aarch64)  PLATFORM="linux-arm64" ;;
    darwin-x86_64)  PLATFORM="darwin-x64" ;;
    darwin-arm64)   PLATFORM="darwin-arm64" ;;
    *) echo "unsupported platform for Node bundle: ${OS}-${ARCH}" >&2; exit 2 ;;
esac

TARBALL_NAME="node-${NODE_VERSION}-${PLATFORM}.tar.xz"
TARBALL_URL="${BASE_URL}/${TARBALL_NAME}"
EXPECTED_SHA="${NODE_SHA256[${PLATFORM}]:-}"
if [[ -z "${EXPECTED_SHA}" ]]; then
    echo "b7.0-fetch-node: SHA-256 pin for ${PLATFORM} is unset." >&2
    echo "Add it to the NODE_SHA256 table in $0 from ${BASE_URL}/SHASUMS256.txt" >&2
    exit 4
fi

# The cache dir lives inside the Gradle
# build dir so the task's input/output
# contract still works (the file is the
# build's input; the extracted node
# binary is the output).
CACHE_DIR="${PROJECT_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}/build/node-bundle-input"
mkdir -p "${CACHE_DIR}"
DEST="${CACHE_DIR}/${TARBALL_NAME}"

# 24.5MB is the published size for the
# linux-x64 tarball (CRC64 xz). Anything
# below 1MB is a redirect page or a 404
# body. The Gradle task also enforces
# this; we surface the check here so a
# failed download is obvious at fetch
# time, not at build time.
MIN_BYTES=1000000

# SHA-256 helper: lowercase hex.
sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        # macOS / BSD
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# Check the existing cache. If the file
# exists, has the right size, and
# matches the pinned SHA-256, we
# exit 0 without re-downloading. A
# stale cache (mismatched hash) is
# removed so the download path runs
# cleanly.
if [[ -f "${DEST}" ]]; then
    EXISTING_BYTES="$(stat -c %s "${DEST}" 2>/dev/null || stat -f %z "${DEST}")"
    if [[ "${EXISTING_BYTES}" -ge "${MIN_BYTES}" ]]; then
        EXISTING_SHA="$(sha256_of "${DEST}")"
        if [[ "${EXISTING_SHA}" == "${EXPECTED_SHA}" ]]; then
            echo "b7.0-fetch-node: ${DEST} already present (${EXISTING_BYTES} bytes), SHA-256 OK, skipping"
            exit 0
        else
            echo "b7.0-fetch-node: ${DEST} has wrong SHA-256 (got ${EXISTING_SHA}, expected ${EXPECTED_SHA}); redownloading"
            rm -f "${DEST}"
        fi
    fi
fi

echo "b7.0-fetch-node: downloading ${TARBALL_URL} -> ${DEST}"
curl -fSL --retry 3 --retry-delay 2 -o "${DEST}.tmp" "${TARBALL_URL}"

ACTUAL_BYTES="$(stat -c %s "${DEST}.tmp" 2>/dev/null || stat -f %z "${DEST}.tmp")"
if [[ "${ACTUAL_BYTES}" -lt "${MIN_BYTES}" ]]; then
    echo "b7.0-fetch-node: downloaded tarball is only ${ACTUAL_BYTES} bytes; expected > ${MIN_BYTES}." >&2
    echo "First 200 bytes of the response:" >&2
    head -c 200 "${DEST}.tmp" >&2
    echo >&2
    rm -f "${DEST}.tmp"
    exit 3
fi

ACTUAL_SHA="$(sha256_of "${DEST}.tmp")"
if [[ "${ACTUAL_SHA}" != "${EXPECTED_SHA}" ]]; then
    echo "b7.0-fetch-node: SHA-256 mismatch after download." >&2
    echo "  expected: ${EXPECTED_SHA}" >&2
    echo "  actual:   ${ACTUAL_SHA}" >&2
    echo "This is a supply-chain integrity failure: either the upstream tarball" >&2
    echo "was rotated, or the mirror served a modified binary. Refusing to use it." >&2
    rm -f "${DEST}.tmp"
    exit 5
fi

mv "${DEST}.tmp" "${DEST}"
echo "b7.0-fetch-node: done (${ACTUAL_BYTES} bytes, SHA-256 ${ACTUAL_SHA:0:16}...)"
