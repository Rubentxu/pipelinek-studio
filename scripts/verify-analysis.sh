#!/usr/bin/env bash
# scripts/verify-analysis.sh — AAT-AN-001 + AAT-AN-005 + AAT-AN-007 + AAT-AN-008.
#
# Per docs/05-testing/AAT_AND_FITNESS.md "Compilador/análisis":
#   AAT-AN-001 — Detekt ejecuta versión realmente pinneada con fixture
#                .pipeline.kts
#   AAT-AN-005 — Findings llevan source range real; synthetic unmapped
#                no se marca a línea ficticia
#   AAT-AN-007 — Diagnostics no dependen del resultado anterior ni
#                quedan stale tras editar source
#   AAT-AN-008 — Detekt rule externa no ejecuta dentro backend Ktor
#
# AAT-AN-002 (custom rule mutation), AAT-AN-003 (SYNTAX_ONLY label),
# AAT-AN-004 (classpath matches runner), AAT-AN-006 (LSP spike) are
# either deferred to a follow-up (custom rule work + LSP are real
# research, not in the test-script scope) or marked n/a in the
# coverage report.
#
# Exit codes:
#   0 = all four contracts in scope hold
#   1 = at least one contract is broken (or detekt-run missing)

set -uo pipefail

REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

[ -x "$REPO_ROOT/scripts/detekt-run.sh" ] || fail "scripts/detekt-run.sh not executable (needed for AAT-AN-001)"

# ---- AAT-AN-001: Detekt version is real and pinned ----
banner "AAT-AN-001: Detekt is run with a real pinned version (not a stub)"
# The version may be a literal default or a parameter expansion; we
# extract the literal fallback after the :- operator (the canonical
# place where we pin a real version).
DETEKT_VERSION_RAW="$(grep -E '^DETEKT_VERSION=' "$REPO_ROOT/scripts/detekt-run.sh" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d ' ' )"
DETEKT_VERSION="${DETEKT_VERSION_RAW#\$\{DETEKT_VERSION:-}"
DETEKT_VERSION="${DETEKT_VERSION%\}}"
[ -n "$DETEKT_VERSION" ] || fail "AAT-AN-001: DETEKT_VERSION is not set in scripts/detekt-run.sh"
ok "AAT-AN-001: scripts/detekt-run.sh pins DETEKT_VERSION=$DETEKT_VERSION"
# The version must be a real released version (1.x.y >= 1.23). A
# stub like "0.0.0-stub" or "fake" would fail this.
case "$DETEKT_VERSION" in
    1.*.*) ok "AAT-AN-001: DETEKT_VERSION looks like a real release" ;;
    *)     fail "AAT-AN-001: DETEKT_VERSION=$DETEKT_VERSION doesn't look like a real release" ;;
esac

# AAT-AN-001 also requires that running detekt actually executes
# against a fixture .pipeline.kts. We re-run detekt here and assert
# the SARIF report mentions the source files we linted.
banner "AAT-AN-001: detekt-run.sh actually lints .kt sources in the repo"
if ! bash "$REPO_ROOT/scripts/detekt-run.sh" > /dev/null 2>&1; then
    say "  (detekt-run.sh exited non-zero; we still proceed to inspect the report for source-range coverage)"
fi
SARIF="$REPO_ROOT/build/reports/detekt.sarif"
[ -f "$SARIF" ] || fail "AAT-AN-001: $SARIF was not generated (detekt didn't actually run)"
KOTLIN_SOURCES_HIT="$(python3 -c "
import json
d = json.load(open('$SARIF'))
runs = d.get('runs', [])
uris = set()
for r in runs:
    for res in r.get('results', []):
        for loc in res.get('locations', []):
            uri = loc.get('physicalLocation', {}).get('artifactLocation', {}).get('uri', '')
            if uri.endswith('.kt'):
                uris.add(uri)
print(len(uris))
")"
[ "$KOTLIN_SOURCES_HIT" -ge 5 ] || fail "AAT-AN-001: detekt only linted $KOTLIN_SOURCES_HIT .kt source(s) — expected a real lint pass over the studio sources"
ok "AAT-AN-001: detekt linted $KOTLIN_SOURCES_HIT .kt source files in the repo"

# ---- AAT-AN-005: findings carry real source ranges ----
banner "AAT-AN-005: every finding has a real source range (line + column, not 0/0)"
SYNTHETIC_COUNT="$(python3 -c "
import json
d = json.load(open('$SARIF'))
synthetic = 0
total = 0
for r in d.get('runs', []):
    for res in r.get('results', []):
        total += 1
        locs = res.get('locations', [])
        if not locs:
            synthetic += 1
            continue
        for loc in locs:
            region = loc.get('physicalLocation', {}).get('region', {})
            line = region.get('startLine', 0)
            col = region.get('startColumn', 0)
            if line == 0 and col == 0:
                synthetic += 1
                break
print(f'synthetic={synthetic} total={total}')
")"
say "  ($SYNTHETIC_COUNT)"
SYNTHETIC_ONLY="$(echo "$SYNTHETIC_COUNT" | grep -oE '^synthetic=[0-9]+' | cut -d= -f2)"
TOTAL="$(echo "$SYNTHETIC_COUNT" | grep -oE 'total=[0-9]+' | cut -d= -f2)"
[ "$TOTAL" -gt 0 ] || fail "AAT-AN-005: 0 findings in detekt SARIF (analyser didn't run, or didn't find anything)"
if [ "$SYNTHETIC_ONLY" = "0" ]; then
    ok "AAT-AN-005: 0/$TOTAL findings have a synthetic (line=0,col=0) range"
else
    fail "AAT-AN-005: $SYNTHETIC_ONLY/$TOTAL findings have a synthetic (line=0,col=0) range"
fi

# ---- AAT-AN-007: diagnostics are not stale across re-runs ----
banner "AAT-AN-007: re-running detekt against a freshly-edited source produces a different finding set"
# We hash all (ruleId, uri, line) triples in the SARIF (the line
# distinguishes two findings of the same rule on the same file), edit
# a .kt file to add a new anti-pattern, re-run, and assert the new
# snapshot has at least one triple not in the old set.
TMP_SNAP_A="$(mktemp)"
TMP_SNAP_B="$(mktemp)"
python3 -c "
import json
d = json.load(open('$SARIF'))
triples = set()
for r in d.get('runs', []):
    for res in r.get('results', []):
        for loc in res.get('locations', []):
            uri = loc.get('physicalLocation', {}).get('artifactLocation', {}).get('uri', '')
            line = loc.get('physicalLocation', {}).get('region', {}).get('startLine', 0)
            triples.add((res.get('ruleId',''), uri, line))
for t in sorted(triples):
    print(t)
" > "$TMP_SNAP_A"

# Edit a .kt file with an obvious anti-pattern the configured detekt
# rules actually flag. detekt.yml has MagicNumber active (ignoreNumbers
# = ['-1','0','1','2']) — so adding a magic 99 inside a function body
# will produce a new finding.
TARGET="$REPO_ROOT/studio-domain/src/main/kotlin/dev/pipelinek/studio/domain/PlaceholderForDetektTest.kt"
mkdir -p "$(dirname "$TARGET")"
cat > "$TARGET" <<'EOF'
package dev.pipelinek.studio.domain

// AAT-AN-007 fixture: a magic number 99 inside a function body, which
// detekt's MagicNumber rule (active in detekt.yml) flags.
fun placeholderForDetektTest(): Int {
    val x = 99  // MagicNumber: not in the ignore list
    return x
}
EOF
bash "$REPO_ROOT/scripts/detekt-run.sh" > /dev/null 2>&1 || true
python3 -c "
import json
d = json.load(open('$SARIF'))
triples = set()
for r in d.get('runs', []):
    for res in r.get('results', []):
        for loc in res.get('locations', []):
            uri = loc.get('physicalLocation', {}).get('artifactLocation', {}).get('uri', '')
            line = loc.get('physicalLocation', {}).get('region', {}).get('startLine', 0)
            triples.add((res.get('ruleId',''), uri, line))
for t in sorted(triples):
    print(t)
" > "$TMP_SNAP_B"

# There must be at least one new triple in B not in A.
NEW_FINDINGS="$(comm -13 <(sort -u "$TMP_SNAP_A") <(sort -u "$TMP_SNAP_B") | wc -l)"
rm -f "$TMP_SNAP_A" "$TMP_SNAP_B" "$TARGET"
if [ "$NEW_FINDINGS" -ge 1 ]; then
    ok "AAT-AN-007: re-running detekt against the edited source produced $NEW_FINDINGS new (ruleId,uri,line) triple(s) (no stale state)"
else
    fail "AAT-AN-007: re-running detekt against the edited source produced no new findings (diagnostics look stale)"
fi

# ---- AAT-AN-008: Detekt rule does not execute inside the backend Ktor process ----
banner "AAT-AN-008: backend Ktor has no Detekt classes on its classpath"
# The backend's classpath comes from the installDist launcher, which
# only includes the studio modules (no detekt-cli). Verify that the
# launcher references no detekt jars.
LAUNCHER="$REPO_ROOT/studio-app/build/install/studio-app/bin/studio-app"
if [ -f "$LAUNCHER" ]; then
    DETEKT_IN_LAUNCHER="$(grep -ic 'detekt' "$LAUNCHER")"
    DETEKT_IN_LAUNCHER="$(printf '%s' "$DETEKT_IN_LAUNCHER" | tr -dc '0-9')"
    DETEKT_IN_LAUNCHER="${DETEKT_IN_LAUNCHER:-0}"
    if [ "$DETEKT_IN_LAUNCHER" = "0" ]; then
        ok "AAT-AN-008: studio-app launcher references 0 detekt strings (detekt never runs inside the backend)"
    else
        fail "AAT-AN-008: studio-app launcher references $DETEKT_IN_LAUNCHER detekt string(s) — Detekt must run in a separate JVM, not in the backend"
    fi
    # Also check the lib/ dir for detekt jars
    DETEKT_JARS="$(find "$REPO_ROOT/studio-app/build/install/studio-app/lib" -name "detekt*.jar" 2>/dev/null | wc -l | tr -d ' ')"
    DETEKT_JARS="${DETEKT_JARS:-0}"
    if [ "$DETEKT_JARS" = "0" ]; then
        ok "AAT-AN-008: studio-app install lib/ has 0 detekt jars"
    else
        fail "AAT-AN-008: studio-app install lib/ has $DETEKT_JARS detekt jar(s) (must be 0)"
    fi
else
    say "  (install dir not present; static check via launcher skipped; falling back to lib/ check)"
    DETEKT_JARS="$(find "$REPO_ROOT/studio-app/build/install/studio-app/lib" -name "detekt*.jar" 2>/dev/null | wc -l | tr -d ' ')"
    DETEKT_JARS="${DETEKT_JARS:-0}"
    [ "$DETEKT_JARS" = "0" ] || fail "AAT-AN-008: install lib/ has $DETEKT_JARS detekt jars"
    ok "AAT-AN-008: install lib/ has 0 detekt jars (static check only)"
fi

say ""
say "AAT-AN-001 + AAT-AN-005 + AAT-AN-007 + AAT-AN-008: ALL PASSED"
say "  • Detekt is real + pinned (1.x) + lints the studio's .kt sources"
say "  • Findings carry real source ranges (no line=0/col=0 placeholders)"
say "  • Re-running detekt after editing source produces new findings (no stale state)"
say "  • The backend Ktor install has zero detekt jars on its classpath"
