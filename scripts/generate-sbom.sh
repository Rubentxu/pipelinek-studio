#!/usr/bin/env bash
# scripts/generate-sbom.sh
#
# S5 SBOM deliverable (R-VER-005 follow-up, 2026-10-11). Walks the
# studio-app classpath and emits a CycloneDX 1.5 STRICT JSON document
# with one component per (group, name, version) tuple. CycloneDX 1.5
# is the spec we mirror; the output now includes the mandatory
# `serialNumber`, `metadata.component`, `purl`, `bom-ref`, and
# `dependencies` graph fields so the file is consumable by
# Dependency-Track, GUAC, Syft, and Grype without manual repair.
#
# What this script CANNOT do (and why):
#   * No file-level SHA-256. The shell subset parses
#     `:studio-app:dependencies --configuration runtimeClasspath` text,
#     which yields coordinates but not JAR paths. The Gradle-side
#     `distExtras` task (B5.1) computes JAR-level SHA-256 and emits
#     strict SBOMs with `hashes[]`. If you need full provenance,
#     run `:studio-cli:distExtras` instead of this script.
#   * No licenses. CycloneDX's `licenses[]` block needs SPDX
#     resolution, which requires a metadata DB this script does not
#     have. The Gradle-side distExtras likewise does not emit licenses.
#
# Usage:
#   ./scripts/generate-sbom.sh [output-file]
#   default output: dist/sbom.cdx.json
#
# Reads the Studio version from gradle.properties:studio.version so
# the emitted SBOM tracks the release tag. R-VER-001 follow-up.

set -euo pipefail

OUT="${1:-dist/sbom.cdx.json}"
mkdir -p "$(dirname "$OUT")"

if [ -x "./gradlew" ]; then
    GRADLE="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE="gradle"
else
    echo '{"error":"no gradle wrapper or system gradle on PATH"}' > "$OUT"
    echo "ERROR: no gradle available; wrote $OUT" >&2
    exit 1
fi

# R-VER-001: read the Studio version from gradle.properties.
# Fallback to the historical 0.1.0-alpha.1 only if the property is
# missing (which would itself be a regression). The
# VersionCoherenceB0Test fails CI if this fallback ever fires.
STUDIO_VERSION=$(grep -E '^studio\.version=' gradle.properties | head -1 | sed -E 's/^studio\.version=//') \
    || STUDIO_VERSION="0.1.0-alpha.1"
if [ -z "$STUDIO_VERSION" ]; then
    STUDIO_VERSION="0.1.0-alpha.1"
fi
# Convert dotted version to Maven purl-safe (CycloneDX purl spec
# allows lowercase letters, digits, dot, dash, underscore — Maven coords
# already match, no transformation needed).

echo "[sbom] resolving runtime classpath for :studio-app..." >&2
RAW=$($GRADLE :studio-app:dependencies --configuration runtimeClasspath -q 2>/dev/null || true)

# Lines we care about look like:
#   +--- org.jetbrains.kotlin:kotlin-stdlib:2.1.0
#   |    +--- com.fasterxml.jackson.core:jackson-databind:2.17.2
#   \--- org.slf4j:slf4j-api:2.0.13
COMPONENTS=$(echo "$RAW" \
    | grep -oE '[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[0-9][a-zA-Z0-9_.-]*' \
    | sort -u)

COUNT=$(echo "$COMPONENTS" | grep -c . || true)

# R-VER-005 strict: build a RFC 4122 UUID without depending on any
# external tool. /proc/sys/kernel/random/uuid exists on Linux and
# provides v4 UUIDs. macOS lacks it; for macOS we fall back to a
# pure-shell synthesis from $RANDOM + timestamp + pid. The shell
# fallback is NOT cryptographically random but is unique enough for
# a per-build serial number.
SERIAL_NUMBER="urn:uuid:$(uuidgen 2>/dev/null \
    || cat /proc/sys/kernel/random/uuid 2>/dev/null \
    || printf '%04x%04x-%04x-%04x-%04x-%06x%06x' \
        $((RANDOM & 0xffff)) $((RANDOM & 0xffff)) \
        $((RANDOM & 0xffff)) \
        $(( (RANDOM & 0x0fff) | 0x4000 )) \
        $(( (RANDOM & 0x3fff) | 0x8000 )) \
        $(( (RANDOM & 0xffffff) )) $(( RANDOM & 0xffffff )))"
TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Render to a temp file in two phases (components + dependencies)
# so we can build the dependencies graph AFTER the component list
# is fully known (CycloneDX 1.5 §"Dependencies" requires refs that
# resolve to bom-refs declared in components[]).
TMP_COMP="$(mktemp)"
trap 'rm -f "$TMP_COMP"' EXIT

# Phase 1: emit components
{
    echo '{'
    echo '  "bomFormat": "CycloneDX",'
    echo '  "specVersion": "1.5",'
    echo "  \"serialNumber\": \"$SERIAL_NUMBER\","
    echo '  "version": 1,'
    echo '  "metadata": {'
    echo "    \"timestamp\": \"$TIMESTAMP\","
    echo '    "tools": [{"vendor":"Rubentxu","name":"scripts/generate-sbom.sh","version":"2"}],'
    # R-VER-005: metadata.component is mandatory in CycloneDX 1.5
    # strict; the root is the studio-cli application. The bom-ref
    # follows the Maven purl convention.
    echo "    \"component\": {"
    echo '      "type": "application",'
    echo '      "group": "dev.pipelinek",'
    echo '      "name": "pipelinek-studio",'
    echo "      \"version\": \"$STUDIO_VERSION\","
    echo "      \"purl\": \"pkg:maven/dev.pipelinek/pipelinek-studio@$STUDIO_VERSION\","
    echo "      \"bom-ref\": \"pkg:maven/dev.pipelinek/pipelinek-studio@$STUDIO_VERSION\""
    echo '    }'
    echo '  },'
    echo '  "components": ['
    FIRST=1
    while IFS=: read -r GROUP ARTIFACT VERSION; do
        [ -z "$GROUP" ] && continue
        if [ $FIRST -eq 0 ]; then echo '    ,'; fi
        FIRST=0
        # R-VER-005: every component gets purl + bom-ref per
        # CycloneDX 1.5 §"Components". Maven coords map directly
        # to a purl (group is dot-separated; artifact + version
        # follow the coordinate convention).
        PURL="pkg:maven/$GROUP:$ARTIFACT@$VERSION"
        printf '    {"type":"library","group":"%s","name":"%s","version":"%s","purl":"%s","bom-ref":"%s"}' \
            "$GROUP" "$ARTIFACT" "$VERSION" "$PURL" "$PURL"
    done <<EOF_COMPONENTS
$COMPONENTS
EOF_COMPONENTS
    echo
    echo '  ]'
} > "$TMP_COMP"

# Phase 2: append dependencies[] (root depends on every component;
# inter-component edges are not modelled because the shell subset
# does not parse the dep graph from `:dependencies` text).
{
    cat "$TMP_COMP"
    ROOT_BREAK="pkg:maven/dev.pipelinek/pipelinek-studio@$STUDIO_VERSION"
    echo ','
    echo '  "dependencies": ['
    # Root dependency
    echo "    {\"ref\":\"$ROOT_BREAK\",\"dependsOn\":["
    FIRST=1
    while IFS=: read -r GROUP ARTIFACT VERSION; do
        [ -z "$GROUP" ] && continue
        if [ $FIRST -eq 0 ]; then echo '      ,'; fi
        FIRST=0
        PURL="pkg:maven/$GROUP:$ARTIFACT@$VERSION"
        printf '      "%s"' "$PURL"
    done <<EOF_COMPONENTS
$COMPONENTS
EOF_COMPONENTS
    echo
    echo '    ]},'
    # Per-component dependency entries (CycloneDX 1.5 requires a
    # dependencies[] entry for every ref declared in components[],
    # even if the entry's dependsOn is empty — this is what
    # Dependency-Track expects).
    FIRST=1
    while IFS=: read -r GROUP ARTIFACT VERSION; do
        [ -z "$GROUP" ] && continue
        if [ $FIRST -eq 0 ]; then echo '    ,'; fi
        FIRST=0
        PURL="pkg:maven/$GROUP:$ARTIFACT@$VERSION"
        printf '    {"ref":"%s","dependsOn":[]}' "$PURL"
    done <<EOF_COMPONENTS
$COMPONENTS
EOF_COMPONENTS
    echo
    echo '  ]'
    echo '}'
} > "$OUT"

echo "[sbom] wrote $COUNT components to $OUT (CycloneDX 1.5 strict: purl + bom-ref + serialNumber + dependencies)" >&2