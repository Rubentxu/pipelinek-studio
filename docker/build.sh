#!/usr/bin/env bash
# docker/build.sh — build the pipelinek-studio Podman image.
#
# R0.3.5 / R6.2 (2026-10-12): builds the image with the
# PipelineK SDK 0.48.0-rc1 bundled so the B5.4 cert
# can exercise the full run path.
#
# Usage:
#   ./docker/build.sh [TAG] [SDK_JAR]
#
# Defaults:
#   TAG     = pipelinek-studio:1.0.0-rc.7
#   SDK_JAR = pipeline-application-0.48.0-rc1.jar
#             (looked up under $REPO_ROOT; fails if absent)

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
TAG="${1:-pipelinek-studio:1.0.0-rc.7}"
SDK_JAR="${2:-}"
SKIP_HOST_BUILD="${SKIP_HOST_BUILD:-0}"

# R6.2 (2026-10-12): the standalone tar is now built
# on the HOST first (where the network + tools are
# available + the gradle daemon is already warm) and
# the Dockerfile just copies the prebuilt artifact.
# This avoids the OOM / network issues of building
# inside the container.
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

# The Dockerfile expects the SDK jar to be at
# /build/$SDK_JAR at build time. We stage it
# into the repo root temporarily so the
# `COPY --from=builder /build/${SDK_JAR} ...`
# line works.
#
# R6.2 (2026-10-12): the SDK jar is OPTIONAL.
# When the operator has the pinned SDK on the
# host, the cert runs the B5.4 surface with a
# real subprocess. Without it, the cert still
# runs but with `RuntimeUnavailable` (the
# studio reports it via /api/version).
if [ -z "$SDK_JAR" ]; then
    SDK_JAR=$(find /var/home/rubentxu/.asdf/installs/pipelinek \
        -name 'pipeline-application-0.48.0*.jar' 2>/dev/null \
        | sort -V | tail -1)
    if [ -z "$SDK_JAR" ]; then
        SDK_JAR="NOT_FOUND"
    else
        SDK_JAR=$(basename "$SDK_JAR")
    fi
fi

if [ "$SDK_JAR" = "NOT_FOUND" ]; then
    echo ">> SDK jar not found on host; building WITHOUT the SDK"
    echo "   (the cert will run with RuntimeUnavailable — /api/version reports it)"
    SDK_STAGED=""
elif [ -f "$SDK_JAR" ]; then
    # SDK_JAR is an absolute or relative path that
    # already exists on disk. Use it as-is.
    SDK_STAGED="$SDK_JAR"
    SDK_JAR=$(basename "$SDK_JAR")
    echo ">> SDK jar given as path: $SDK_STAGED"
elif [ -f "$REPO_ROOT/$SDK_JAR" ]; then
    # SDK_JAR is a basename and already lives at
    # the repo root. Use it as-is.
    SDK_STAGED="$REPO_ROOT/$SDK_JAR"
    echo ">> SDK jar at repo root: $SDK_STAGED"
else
    # SDK_JAR is a basename; look it up under
    # the host's asdf install.
    SOURCE=$(find /var/home/rubentxu/.asdf -name "$SDK_JAR" -type f 2>/dev/null | head -1)
    if [ -z "$SOURCE" ]; then
        echo ">> SDK jar ($SDK_JAR) not found on disk; building WITHOUT the SDK"
        SDK_STAGED=""
    else
        SDK_STAGED="$SOURCE"
        echo ">> SDK jar found: $SOURCE"
    fi
fi

# Copy the prebuilt standalone dir to a staging
# path the Dockerfile can COPY from. We do this
# as a separate context (the docker/ dir) so we
# don't need the entire repo in the build context.
STAGE_DIR=$(mktemp -d)
cp -r "$STANDALONE_DIR/studio-cli-standalone-$STUDIO_VERSION" "$STAGE_DIR/studio"
echo ">> staged standalone at $STAGE_DIR"

# Build context is the staging dir (a thin slice of
# the repo) so the build doesn't have to COPY the
# entire repo.
echo ">> podman build -t $TAG -f $REPO_ROOT/docker/Dockerfile $STAGE_DIR"
echo "   STUDIO_VERSION=$STUDIO_VERSION"
echo "   SDK_JAR=$SDK_JAR"

cd "$STAGE_DIR"
SDK_BUILD_ARG=""
if [ -n "$SDK_STAGED" ] && [ -f "$SDK_STAGED" ]; then
    cp "$SDK_STAGED" "$STAGE_DIR/"
    SDK_BUILD_ARG="--build-arg SDK_JAR=$SDK_JAR"
fi

# Copy the runtime-only Dockerfile into the
# staging dir. The original Dockerfile is
# 2-stage (builder + runtime) and is used when
# building inside the container; this script
# builds the standalone tar on the host first
# (where the network + gradle daemon are warm)
# and packages the result. The runtime-only
# Dockerfile is the host-prebuild variant
# and lives at docker/Dockerfile.runtime.
cp "$REPO_ROOT/docker/Dockerfile.runtime" "$STAGE_DIR/Dockerfile.runtime"

podman build \
    --build-arg "STUDIO_VERSION=$STUDIO_VERSION" \
    $SDK_BUILD_ARG \
    -t "$TAG" \
    -f "$STAGE_DIR/Dockerfile.runtime" \
    "$STAGE_DIR"

# Clean up.
rm -rf "$STAGE_DIR"
if [ -n "$SDK_STAGED" ] && [ -f "$SDK_STAGED" ]; then
    rm -f "$SDK_STAGED"
fi

echo ""
echo ">> image built: $TAG"
echo "   run: podman run -d -p 7778:7778 --name studio $TAG"
echo "   cert: ./scripts/e2e-podman.sh $TAG"
