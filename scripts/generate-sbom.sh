#!/usr/bin/env bash
# scripts/generate-sbom.sh
#
# S5 SBOM deliverable. Walks the studio-app classpath and emits a
# CycloneDX-style JSON document with one component per (group, name,
# version) tuple. CycloneDX 1.5 is the spec we mirror; the output
# is intentionally a SUBSET of the spec (no licenses, no purls) so
# the script has zero external dependencies and works on any Gradle
# 8.x install.
#
# Usage:
#   ./scripts/generate-sbom.sh [output-file]
#   default output: dist/sbom.cdx.json
#
# The script does NOT block on a missing gradle wrapper: it falls
# back to `gradle` on PATH and emits a structured error to stdout
# if neither is available.

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

{
    echo '{'
    echo '  "bomFormat": "CycloneDX",'
    echo '  "specVersion": "1.5",'
    echo '  "version": 1,'
    echo '  "metadata": {'
    echo '    "timestamp": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'",'
    echo '    "tools": [{"name":"scripts/generate-sbom.sh","version":"1"}],'
    echo '    "component": {"type":"application","name":"pipelinek-studio","version":"0.1.0-alpha.1"}'
    echo '  },'
    echo '  "components": ['
    FIRST=1
    while IFS=: read -r GROUP ARTIFACT VERSION; do
        [ -z "$GROUP" ] && continue
        if [ $FIRST -eq 0 ]; then echo '    ,'; fi
        FIRST=0
        printf '    {"type":"library","group":"%s","name":"%s","version":"%s"}' \
            "$GROUP" "$ARTIFACT" "$VERSION"
    done <<EOF_COMPONENTS
$COMPONENTS
EOF_COMPONENTS
    echo
    echo '  ]'
    echo '}'
} > "$OUT"

echo "[sbom] wrote $COUNT components to $OUT" >&2
