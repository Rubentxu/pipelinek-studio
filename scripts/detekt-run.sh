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
# B0.3 (2026-10-10): the previous script swallowed every detekt
# finding as "non-fatal for the S0 bootstrap" and exited 0. That
# is a false-PASS channel: the verify-from-clean-clone harness
# could declare READY while detekt was reporting an unbounded
# number of issues. The default is now to fail on findings
# (DETEKT_FAIL_ON_FINDINGS=1). Set DETEKT_FAIL_ON_FINDINGS=0 to
# opt into the legacy baseline posture (used while the team is
# shrinking the 35-finding baseline to a tight gate).
DETEKT_FAIL_ON_FINDINGS="${DETEKT_FAIL_ON_FINDINGS:-1}"
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
#
#    Detekt's exit code semantics:
#      0 = no findings (clean)
#      1 = an internal error (jar missing, malformed config, etc.)
#      2 = findings reported
#    We capture the exit code and decide what to do based on
#    DETEKT_FAIL_ON_FINDINGS. Internal errors (code 1) always
#    fail the script — those are infrastructure problems, not
#    policy.
set +e
java -jar "$JAR" \
    --input studio-domain/src/main/kotlin,studio-application/src/main/kotlin,studio-infra-storage/src/main/kotlin,studio-infra-sdk/src/main/kotlin,studio-http/src/main/kotlin,studio-cli/src/main/kotlin,studio-app/src/main/kotlin \
    --config "$ROOT/detekt.yml" \
    --report sarif:build/reports/detekt.sarif \
    --report txt:build/reports/detekt.txt \
    >/tmp/detekt-stdout.log 2>&1
DETEKT_EXIT=$?
set -e

# Count findings from the SARIF report (machine-readable, not the
# text report which is formatted for humans). Each finding is one
# element in the runs[].results[] array. Prefer jq; fall back to
# grep on the SARIF "results" key if jq is missing so the count
# stays accurate in minimal CI images. The plain-text detekt.txt
# is NOT used for the count: it's formatted for humans, with one
# line per rule plus a summary block, so a regex would over- or
# under-count depending on the rule grouping.
FINDINGS=0
if [ -f "build/reports/detekt.sarif" ]; then
    if command -v jq >/dev/null 2>&1; then
        FINDINGS="$(jq '[.runs[].results[]?] | length' build/reports/detekt.sarif 2>/dev/null || echo 0)"
    else
        # Fallback: count occurrences of '"ruleId"' in the SARIF
        # results section. This is heuristic but reliable: every
        # finding carries exactly one ruleId, and detekt never
        # nests rules with no finding into the results array.
        FINDINGS="$(awk '/"results" *:/{flag=1; next} /"tool" *:/{flag=0} flag' build/reports/detekt.sarif | grep -c '"ruleId"' || true)"
    fi
fi

if [ "$DETEKT_EXIT" = "1" ]; then
    fail "detekt internal error (exit 1); see /tmp/detekt-stdout.log"
fi

if [ "$DETEKT_EXIT" = "2" ] || [ "$FINDINGS" -gt 0 ]; then
    if [ "$DETEKT_FAIL_ON_FINDINGS" = "1" ]; then
        fail "detekt reported $FINDINGS finding(s); see build/reports/detekt.txt (set DETEKT_FAIL_ON_FINDINGS=0 to opt into the baseline posture)"
    else
        say
        say "Detekt reported $FINDINGS finding(s) — non-fatal (DETEKT_FAIL_ON_FINDINGS=0)."
        say "This is the legacy baseline posture. Tighten rules and remove the opt-out before S5 stable."
        say "See build/reports/detekt.txt for the per-rule breakdown."
    fi
fi

ok "detekt finished (findings=$FINDINGS, exit=$DETEKT_EXIT, fail_on_findings=$DETEKT_FAIL_ON_FINDINGS; reports: build/reports/detekt.{sarif,txt})"
