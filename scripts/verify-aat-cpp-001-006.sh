#!/usr/bin/env bash
# scripts/verify-aat-cpp-001-006.sh — AAT-CPP-001..006 compile-only.
#
# Per docs/05-testing/AAT_AND_FITNESS.md:
#   AAT-CPP-001..006 — Static analyze + compile-only contract.
#                       Side effects on top-level Kotlin code
#                       must be 0 verifiable effects or
#                       UNSUPPORTED, never "analysis safe".
#
# Per ROADMAP §"Riesgos":
#   R-01 — validate/compile performs Kotlin eval. Mitigación:
#          separador compile-only real o UNSUPPORTED.
#   "STATIC_ANALYZE not exposed; honest 'compile-only' is
#    not in scope for S0..S5."
#
# The contract: the studio has no static-analyze / compile-only
# endpoint. Any future analyzer MUST return 0 verifiable
# effects (for a side-effect-free source) or UNSUPPORTED
# (for a source with effects) — never "analysis safe". The
# current state: the contract holds vacuously because there
# is no analyzer to lie.
#
# Exit codes:
#   0 = all currently-implementable contracts hold
#   1 = a contract was violated

set -uo pipefail

# Transport is stated by the operator, never assumed (Studio serves TLS on
# 127.0.0.1:7778 by default and plain HTTP only with --insecure-http): see
# scripts/lib/studio-http.sh.
# shellcheck source=lib/studio-http.sh disable=SC1091
. "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"

say()  { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
banner() { printf '\n== %s ==\n' "$*"; }

# ---- preconditions --------------------------------------------------

banner "preconditions"
command -v curl   >/dev/null || fail "curl required"
command -v python3 >/dev/null || fail "python3 required"
if ! curl -fsS "$STUDIO_HTTP/api/version" >/dev/null 2>&1; then
  fail "studio not reachable at $STUDIO_HTTP"
fi
ok "studio reachable at $STUDIO_HTTP"

# ---- AAT-CPP-001: no static-analyze endpoint -------------------------

banner "AAT-CPP-001: no /api/static-analyze endpoint (R-01 keeps compile-only out)"
STATIC_CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    "$STUDIO_HTTP/api/static-analyze" \
    -H "content-type: application/json" \
    -d '{"source":"package x; fun main() {}"}')"
[ "$STATIC_CODE" = "404" ] || fail "AAT-CPP-001: /api/static-analyze returned $STATIC_CODE (expected 404)"
ok "/api/static-analyze → 404 (no fabrication; no analyzer to lie)"

# ---- AAT-CPP-002: no analyze surface either -------------------------

banner "AAT-CPP-002: no /api/analyze alternative surface"
ANALYZE_CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    "$STUDIO_HTTP/api/analyze" \
    -H "content-type: application/json" \
    -d '{"source":"package x; fun main() {}"}')"
[ "$ANALYZE_CODE" = "404" ] || fail "AAT-CPP-002: /api/analyze returned $ANALYZE_CODE (expected 404)"
ok "/api/analyze → 404"

# ---- AAT-CPP-003: no /api/compile endpoint either -------------------

banner "AAT-CPP-003: no /api/compile alternative surface"
COMPILE_CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    "$STUDIO_HTTP/api/compile" \
    -H "content-type: application/json" \
    -d '{"source":"package x; fun main() {}"}')"
[ "$COMPILE_CODE" = "404" ] || fail "AAT-CPP-003: /api/compile returned $COMPILE_CODE (expected 404)"
ok "/api/compile → 404"

# ---- AAT-CPP-004: OpenAPI does not advertise a static-analyze ---

banner "AAT-CPP-004: OpenAPI does not expose any static-analyze surface"
OPENAPI="$(curl -fsS "$STUDIO_HTTP/openapi.json")"
EXPOSES="$(echo "$OPENAPI" | python3 -c "
import json, sys
d = json.load(sys.stdin)
bad = []
for p in d.get('paths', {}).keys():
    if 'analyz' in p.lower() or 'compile-only' in p.lower() or '/compile' in p.lower():
        bad.append(p)
print('none' if not bad else '\n'.join(bad))
")"
[ "$EXPOSES" = "none" ] || fail "AAT-CPP-004: OpenAPI exposes static-analyze surface: $EXPOSES"
ok "OpenAPI does not advertise any static-analyze / compile-only surface"

# ---- AAT-CPP-005: no diagnostics response claims 'analysis safe' --

banner "AAT-CPP-005: no /diagnostics response claims 'analysis safe' or 'safe to ship'"
# Sample a few runs and assert none of them contains a
# fabricated verdict phrase.
SAMPLE="$(curl -fsS "$STUDIO_HTTP/runs" 2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
ids = [r.get('run_id', '') for r in d.get('items', d.get('runs', []))[:5]]
print('\n'.join(ids))
" 2>/dev/null)"
BAD=0
for RUN in $SAMPLE; do
    [ -z "$RUN" ] && continue
    CLAIM="$(curl -fsS "$STUDIO_HTTP/runs/$RUN/diagnostics" 2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
text = json.dumps(d).lower()
bad = [p for p in ('analysis safe', 'safe to ship', 'no findings', '0 issues') if p in text]
print(','.join(bad) if bad else 'clean')
")"
    [ "$CLAIM" = "clean" ] || BAD=$((BAD + 1))
done
[ "$BAD" = "0" ] || fail "AAT-CPP-005: $BAD run(s) had fabricated analysis-safe / safe-to-ship phrases in diagnostics"
ok "no run diagnostics contains an 'analysis safe' / 'safe to ship' phrase (across $(echo "$SAMPLE" | wc -l) sample)"

# ---- AAT-CPP-006: a future analyzer MUST honour the contract ------

banner "AAT-CPP-006: a future static analyzer MUST honour the contract"
# This is a documentation step: the contract for any future
# analyzer that may be added is:
#   - return 0 verifiable effects for a side-effect-free source
#   - return UNSUPPORTED for a source with effects
#   - never claim 'analysis safe' or 'safe to ship' without
#     full type resolution on a valid classpath
#   - the analyzer MUST be a separate jar (not a built-in of
#     the studio) so the studio cannot be confused with the
#     analyzer's verdict
ok "future static analyzer contract documented (R-01: keep compile-only out of S0..S5)"

# ---- summary --------------------------------------------------------
banner "AAT-CPP-001..006: ALL CURRENTLY-IMPLEMENTABLE CONTRACTS HOLD"
say "  • AAT-CPP-001: no /api/static-analyze endpoint (404)"
say "  • AAT-CPP-002: no /api/analyze alternative surface (404)"
say "  • AAT-CPP-003: no /api/compile alternative surface (404)"
say "  • AAT-CPP-004: OpenAPI does not advertise static-analyze / compile-only"
say "  • AAT-CPP-005: no /diagnostics response claims 'analysis safe' / 'safe to ship'"
say "  • AAT-CPP-006: future analyzer contract documented (R-01: keep out of S0..S5)"
exit 0
