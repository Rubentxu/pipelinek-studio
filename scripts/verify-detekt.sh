#!/usr/bin/env bash
# scripts/verify-detekt.sh — AAT-DET static analysis audit.
#
# Per docs/05-testing/AAT_AND_FITNESS.md the S3 deliverable "Detekt
# + diagnósticos" requires a working static analyser wired into the
# verify pipeline. scripts/detekt-run.sh runs the analyser in an
# isolated JVM (not the Gradle plugin) so user code is never
# executed by lint.
#
# This script:
#   1. Invokes detekt-run.sh
#   2. Confirms the report was generated
#   3. Asserts that no Critical/High findings appeared
#      (MagicNumber / LongMethod / NestedBlockDepth / etc. are
#      informational; we only fail on security/correctness rules
#      like UnusedPrivateMember, ForbiddenComment, etc.)
#   4. Prints the top-5 finding types for visibility
#
# Exit codes:
#   0 = report generated + no critical findings
#   1 = report shows critical findings
#   2 = detekt failed to run

set -uo pipefail

REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
REPORT="$REPO_ROOT/build/reports/detekt.txt"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }

if ! bash "$REPO_ROOT/scripts/detekt-run.sh" > /dev/null 2>&1; then
    fail "detekt-run.sh did not exit 0"
fi

[ -f "$REPORT" ] || fail "detekt report missing at $REPORT"

ok "detekt report generated at $REPORT"

# Categorize findings. We FAIL on rules that indicate real risk;
# we REPORT on style/maintainability rules without failing.
CRITICAL_RULES="(UnusedPrivateMember|ForbiddenComment|ForbiddenImport|UnsafeCallOnNullableType|UnsafeCast|LateinitUsage)"
STYLE_RULES="(MagicNumber|LongMethod|NestedBlockDepth|TooGenericExceptionCaught|ReturnCount|TooManyFunctions|CyclomaticComplexMethod|CognitiveComplexMethod|VariableNaming|MatchingDeclarationName|FunctionNaming)"

CRITICAL_COUNT="$(grep -cE "$CRITICAL_RULES" "$REPORT" 2>/dev/null || true)"
STYLE_COUNT="$(grep -cE "$STYLE_RULES" "$REPORT" 2>/dev/null || true)"

if [ "${CRITICAL_COUNT:-0}" = "0" ]; then
    ok "AAT-DET 0 critical findings (UnusedPrivateMember / Unsafe* / Forbidden*)"
else
    fail "AAT-DET $CRITICAL_COUNT critical findings: $(grep -E "$CRITICAL_RULES" "$REPORT" | head -3)"
fi

if [ "${STYLE_COUNT:-0}" = "0" ]; then
    say "  ℹ️  no style findings (clean run)"
else
    say "  ℹ️  $STYLE_COUNT style findings (MagicNumber/LongMethod/etc.) — not blocking"
fi

# Top-5 finding types
say ""
say "Top finding types:"
grep -oE "^[A-Z][a-zA-Z]+ " "$REPORT" | sort | uniq -c | sort -rn | head -5 | while read count rule; do
    say "  $count $rule"
done

# Lines of code summary
say ""
LOC="$(grep -A1 "lines of code" "$REPORT" | tail -1 | awk '{print $1}' || echo '?')"
say "Total: ~${LOC} lines of code analyzed"

exit 0
