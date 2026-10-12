#!/usr/bin/env bash
# docker/build.sh — build the pipelinek-studio Podman image.
#
# C1.4 (2026-10-12): the image now bundles the SDK
# extracted from the OFFICIAL PipelineK 0.48.0-rc1
# distribution ZIP, verified by the studio-cert
# resolver (ZipSdkResolver) before the JAR is staged
# for COPY. The previous asdf-based lookup is kept
# as a dev fallback only.
#
# The cert CLI guarantees the staged JAR is the
# one whose SHA matches the lockfile (see
# `studio-infra-sdk/src/main/resources/sdk.lock.json`).
#
# Usage:
#   ./docker/build.sh [TAG] [SDK_SOURCE]
#
# SDK_SOURCE (priority order):
#   1. A path to the official ZIP
#      (default: studio-cert/sdk/pipelinek-0.48.0.zip).
#      The cert CLI extracts + verifies the canonical
#      jar, then stages it.
#   2. A direct path to a pipeline-application-*.jar.
#      The cert CLI does NOT verify its SHA in this
#      mode (operator-driven), but the lockfile check
#      at first /api/version will refuse drift.
#   3. (fallback only) the asdf install at
#      /var/home/rubentxu/.asdf/installs/pipelinek/...
#      The cert CLI does NOT verify in this mode.
#
# Defaults:
#   TAG         = pipelinek-studio:1.0.0-rc.7
#   SDK_SOURCE  = studio-cert/sdk/pipelinek-0.48.0.zip
#
# Requires: studio-cert CLI built via `gradle installDist`
# in studio-cert/. The build below runs that step first
# (it is small + fast + the daemon is already warm).

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
TAG="${1:-pipelinek-studio:1.0.0-rc.7}"
SDK_SOURCE="${2:-}"
SKIP_HOST_BUILD="${SKIP_HOST_BUILD:-0}"

# C1.4: the cert CLI is the only way the build script
# consumes the SDK. If the CLI isn't built yet, build it.
CERT_CLI="$REPO_ROOT/studio-cert/build/install/studio-cert/bin/studio-cert"
if [ ! -x "$CERT_CLI" ]; then
    echo ">> building studio-cert CLI"
    (cd "$REPO_ROOT/studio-cert" && gradle installDist --console=plain 2>&1 | tail -3) || {
        echo "studio-cert build failed" >&2
        exit 2
    }
fi

# R6.2: the standalone tar is built on the host (where
# the network + tools are available + the gradle daemon
# is warm) and the Dockerfile just copies the prebuilt
# artifact. This avoids the OOM / network issues of
# building inside the container.
STANDALONE_DIR="$REPO_ROOT/studio-cli/build/standalone"
STUDIO_VERSION=$(grep '^studio.version' "$REPO_ROOT/gradle.properties" | cut -d= -f2)
if [ "$SKIP_HOST_BUILD" != "1" ]; then
    echo ">> building standalone tar on the host (this is the slow step)"
    (cd "$REPO_ROOT" && ./gradlew :studio-cli:standaloneDist --console=plain 2>&1 | tail -3) || {
        echo "host build failed; set SKIP_HOST_BUILD=1 to skip" >&2
        exit 2
    }
fi
if [ ! -d "$STANDALONE_DIR/studio-cli-standalone-$STUDIO_VERSION" ]; then
    echo "error: $STANDALONE_DIR/studio-cli-standalone-$STUDIO_VERSION not found" >&2
    exit 2
fi
echo ">> standalone tar ready: $STANDALONE_DIR"

# C1.4: resolve the SDK to a single canonical jar. Three
# modes: official ZIP (preferred), direct jar path
# (operator-driven, no SHA verify), asdf lookup
# (legacy fallback, no SHA verify).
DEFAULT_ZIP="$REPO_ROOT/studio-cert/sdk/pipelinek-0.48.0.zip"
SDK_SOURCE="${SDK_SOURCE:-$DEFAULT_ZIP}"

# staging dir the Dockerfile COPYs from
STAGE_DIR=$(mktemp -d)
SDK_STAGED=""
SDK_JAR=""

# Case 1: SDK_SOURCE is a path to a .zip we recognise
if [ -f "$SDK_SOURCE" ] && [[ "$SDK_SOURCE" == *.zip ]]; then
    echo ">> resolving SDK from official ZIP: $SDK_SOURCE"
    if ! "$CERT_CLI" resolve-sdk "$SDK_SOURCE" > "$STAGE_DIR/sdk-dist.json" 2> "$STAGE_DIR/sdk-resolve.err"; then
        echo "FAIL: cert resolver refused the ZIP" >&2
        cat "$STAGE_DIR/sdk-resolve.err" >&2 || true
        rm -rf "$STAGE_DIR"
        exit 3
    fi
    # Extract the canonical jar path from the cert CLI JSON
    SDK_JAR=$(python3 -c "
import json
d=json.load(open('$STAGE_DIR/sdk-dist.json'))
mj=[j for j in d['jars'] if j['isMainClass']][0]
print(mj['path'])
")
    if [ ! -f "$SDK_JAR" ]; then
        echo "FAIL: cert resolver returned a non-existent canonical jar: $SDK_JAR" >&2
        rm -rf "$STAGE_DIR"
        exit 3
    fi
    SDK_STAGED="$SDK_JAR"
    SDK_JAR=$(basename "$SDK_JAR")
    echo ">> cert-verified canonical jar: $SDK_JAR (sha256 pinned)"

# Case 2: SDK_SOURCE is a direct path to a .jar
elif [ -f "$SDK_SOURCE" ] && [[ "$SDK_SOURCE" == *.jar ]]; then
    echo ">> using direct jar path (no cert SHA verify): $SDK_SOURCE"
    SDK_STAGED="$SDK_SOURCE"
    SDK_JAR=$(basename "$SDK_SOURCE")

# Case 3: SDK_SOURCE is empty and we look under asdf (legacy)
elif [ -z "$SDK_SOURCE" ] || [ "$SDK_SOURCE" = "$DEFAULT_ZIP" ]; then
    if [ ! -f "$DEFAULT_ZIP" ]; then
        echo ">> default ZIP not present; falling back to asdf lookup"
        LEGACY=$(find /var/home/rubentxu/.asdf/installs/pipelinek \
            -name 'pipeline-application-0.48.0*.jar' 2>/dev/null \
            | sort -V | tail -1 || true)
        if [ -z "$LEGACY" ]; then
            echo ">> no SDK found; building WITHOUT the SDK (RuntimeUnavailable at /api/version)"
        else
            echo ">> legacy asdf jar: $LEGACY"
            SDK_STAGED="$LEGACY"
            SDK_JAR=$(basename "$LEGACY")
        fi
    fi

# Case 4: anything else — try asdf
else
    echo ">> SDK source not a ZIP and not a JAR; trying asdf"
    LEGACY=$(find /var/home/rubentxu/.asdf -name "$(basename "$SDK_SOURCE")" -type f 2>/dev/null | head -1 || true)
    if [ -n "$LEGACY" ]; then
        SDK_STAGED="$LEGACY"
        SDK_JAR=$(basename "$LEGACY")
    else
        echo ">> no SDK found; building WITHOUT the SDK"
    fi
fi

# Stage the standalone dir for the Dockerfile
cp -r "$STANDALONE_DIR/studio-cli-standalone-$STUDIO_VERSION" "$STAGE_DIR/studio"

# Build context is the staging dir
echo ">> podman build -t $TAG -f $REPO_ROOT/docker/Dockerfile $STAGE_DIR"
echo "   STUDIO_VERSION=$STUDIO_VERSION"
echo "   SDK_JAR=${SDK_JAR:-(none — RuntimeUnavailable)}"

# Stage the SDK jar if we have one
SDK_BUILD_ARG=""
if [ -n "$SDK_STAGED" ] && [ -f "$SDK_STAGED" ]; then
    cp "$SDK_STAGED" "$STAGE_DIR/"
    SDK_BUILD_ARG="--build-arg SDK_JAR=$SDK_JAR"
fi

# Copy the runtime-only Dockerfile into the staging dir
cp "$REPO_ROOT/docker/Dockerfile.runtime" "$STAGE_DIR/Dockerfile.runtime"

(cd "$STAGE_DIR" && podman build \
    --build-arg "STUDIO_VERSION=$STUDIO_VERSION" \
    $SDK_BUILD_ARG \
    -t "$TAG" \
    -f Dockerfile.runtime \
    .)

# Clean up
rm -rf "$STAGE_DIR"
