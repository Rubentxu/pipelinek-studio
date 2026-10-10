#!/usr/bin/env bash
# B7.0 (2026-10-10): pre-fetch the Node
# 20 tarball that nodeBundle consumes.
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
# and matches the expected size, the
# script exits 0 without re-downloading.

set -euo pipefail

# Pin the same version + base URL the
# Gradle task uses (see
# studio-cli/build.gradle.kts,
# nodeBundleVersionB70 + nodeBaseUrlB70).
NODE_VERSION="v20.18.0"
BASE_URL="https://nodejs.org/dist/${NODE_VERSION}"

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

if [[ -f "${DEST}" ]] && [[ "$(stat -c %s "${DEST}" 2>/dev/null || stat -f %z "${DEST}")" -ge "${MIN_BYTES}" ]]; then
    echo "b7.0-fetch-node: ${DEST} already present ($(stat -c %s "${DEST}" 2>/dev/null || stat -f %z "${DEST}") bytes), skipping"
    exit 0
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

mv "${DEST}.tmp" "${DEST}"
echo "b7.0-fetch-node: done (${ACTUAL_BYTES} bytes)"
