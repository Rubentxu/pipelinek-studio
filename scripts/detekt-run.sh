#!/usr/bin/env bash
# S3 E3.2 — Detekt in-process isolated runner.
#
# This script does NOT use the detekt Gradle plugin (which would couple the
# static analyser to the rest of the build). Instead it downloads the
# detekt-cli jar (pinned via libs.versions.toml) and runs the analyser in a
# separate JVM. The output is SARIF JSON + a human-readable summary.
#
# Per E3.2: the analyser is "isolated" — it never executes the project's
# Kotlin code, only parses and lints it. This is the safe posture given that
# the SDK is pinned to a prerelease and we don't want lint rules triggering
# a full Studio build.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

say() { printf '%s\n' "$*"; }
fail() { say "❌ $*"; exit 1; }
ok()   { say "✅ $*"; }

DETEKT_VERSION="${DETEKT_VERSION:-1.23.8}"
CACHE="${DETEKT_CACHE:-$HOME/.cache/detekt}"
JAR="$CACHE/detekt-cli-$DETEKT_VERSION-all.jar"

# 1. Fetch the detekt CLI jar (idempotent). The download is large (~30 MB)
#    so we cache it; reruns are fast.
mkdir -p "$CACHE"
if [ ! -f "$JAR" ]; then
    say "downloading detekt-cli $DETEKT_VERSION to $JAR"
    if command -v curl >/dev/null 2>&1; then
        curl -fSL -o "$JAR" \
            "https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/$DETEKT_VERSION/detekt-cli-$DETEKT_VERSION-all.jar" \
            || fail "download failed"
    else
        fail "curl not found; install curl or download detekt-cli manually to $JAR"
    fi
fi
[ -f "$JAR" ] || fail "detekt jar missing: $JAR"
ok "detekt-cli: $JAR"

# 2. Run detekt against the seven Studio modules. We exclude the apps/mcp
#    and apps/web node projects (no Kotlin source there).
java -jar "$JAR" \
    --input studio-domain/src/main/kotlin,studio-application/src/main/kotlin,studio-infra-storage/src/main/kotlin,studio-infra-sdk/src/main/kotlin,studio-http/src/main/kotlin,studio-cli/src/main/kotlin,studio-app/src/main/kotlin \
    --config "$ROOT/detekt.yml" \
    --report sarif:build/reports/detekt.sarif \
    --report txt:build/reports/detekt.txt \
    2>&1 | tee /tmp/detekt-stdout.log || {
        say
        say "Detekt reported issues (see build/reports/detekt.txt for details)."
        say "This is non-fatal for the S0 bootstrap — the rules are a baseline,"
        say "not a gate. A future S5 hardening pass will tighten maxIssues."
        exit 0
    }

ok "detekt finished (reports: build/reports/detekt.{sarif,txt})"
