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
    # Look for the pinned jar (or any 0.48.0
    # variant) on the host.
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
else
    SDK_STAGED="$REPO_ROOT/$SDK_JAR"
    if [ ! -f "$SDK_STAGED" ]; then
        echo ">> SDK jar found ($SDK_JAR) but not at repo root; staging it"
        # Locate the actual file (we picked the basename only).
        SOURCE=$(find /var/home/rubentxu/.asdf -name "$SDK_JAR" -type f 2>/dev/null | head -1)
        if [ -z "$SOURCE" ]; then
            echo "   could not find $SDK_JAR on disk; building WITHOUT the SDK"
            SDK_STAGED=""
        else
            echo "   staging $SOURCE → $SDK_STAGED"
            cp "$SOURCE" "$SDK_STAGED"
        fi
    fi
    if [ -n "$SDK_STAGED" ]; then
        echo ">> SDK_JAR=$SDK_JAR"
    fi
fi

echo ">> podman build -t $TAG -f $REPO_ROOT/docker/Dockerfile $REPO_ROOT"
echo "   STUDIO_VERSION=$(grep '^studio.version' "$REPO_ROOT/gradle.properties" | cut -d= -f2)"

cd "$REPO_ROOT"
SDK_BUILD_ARG=""
if [ -n "$SDK_STAGED" ] && [ -f "$SDK_STAGED" ]; then
    SDK_BUILD_ARG="--build-arg SDK_JAR=$SDK_JAR"
fi
podman build \
    --build-arg "STUDIO_VERSION=$(grep '^studio.version' gradle.properties | cut -d= -f2)" \
    $SDK_BUILD_ARG \
    -t "$TAG" \
    -f docker/Dockerfile \
    "$REPO_ROOT"

# Clean up the staged jar.
if [ -n "$SDK_STAGED" ] && [ -f "$SDK_STAGED" ]; then
    rm -f "$SDK_STAGED"
fi

echo ""
echo ">> image built: $TAG"
echo "   run: podman run -d -p 7778:7778 --name studio $TAG"
echo "   cert: ./scripts/e2e-podman.sh $TAG"
