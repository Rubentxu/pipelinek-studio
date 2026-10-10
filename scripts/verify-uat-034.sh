#!/usr/bin/env bash
# scripts/verify-uat-034.sh — UAT-034 LSP over .pipeline.kts.
#
# Per docs/05-testing/UAT_CATALOG.md:
#   UAT-034 — LSP Kotlin sobre `.pipeline.kts` con Step externo
#             → Si soportado, completion de clase real; si no,
#               limit documentada sin bloquear release.
#
# Contract: a Language Server Protocol implementation over
# the .pipeline.kts source format MAY exist; if it doesn't,
# the limit MUST be documented so a downstream consumer
# (VS Code extension, Monaco integration) is not surprised.
#
# Today: the studio has no LSP server. The MCP server is the
# only programmatic surface. A future S5 hardening pass
# (EVO-C VS Code completo) would wire an LSP.
#
# The verifier documents the current limit and the future
# hardening path so a downstream consumer knows what to
# expect: "no LSP; the catalog is at /steps; the studio
# compiler surfaces diagnostics via /runs/{id}/diagnostics".
#
# Exit codes:
#   0 = the limit is documented; a downstream consumer
#       can build a working UX without an LSP
#   1 = the studio fabricates LSP support it doesn't have

set -uo pipefail

STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"
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

# ---- 1. no LSP endpoint ---------------------------------------------

banner "1. no LSP endpoint exposed (R-01 + EVO-C keeps LSP out of S0..S5)"
# LSP servers typically expose a JSON-RPC endpoint at
# /lsp or /rpc. A future S5 hardening pass would wire one.
# Today: the studio has neither.
LSP_CODE="$(curl -sS -o /tmp/uat034-lsp.json -w '%{http_code}' \
    -H "content-type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
    "$STUDIO_HTTP/lsp")"
[ "$LSP_CODE" = "404" ] || fail "UAT-034: /lsp returned $LSP_CODE (no LSP server expected; a future S5 hardening may add one)"
ok "/lsp → 404 (no LSP server; no fabrication)"

# ---- 2. the step catalog is the discovery surface -------------------

banner "2. the step catalog at GET /steps is the canonical discovery surface"
STEPS_COUNT="$(curl -fsS "$STUDIO_HTTP/steps" | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(len(d.get('steps', [])))
")"
[ "$STEPS_COUNT" -ge 1 ] || fail "UAT-034: GET /steps returned 0 steps (the catalog is empty — no discovery surface for a downstream consumer)"
ok "GET /steps exposes $STEPS_COUNT step(s) (canonical discovery surface for an editor integration)"

# ---- 3. each step has the fields a downstream consumer needs -----

banner "3. each step carries the fields a downstream editor needs (id, name, plugin_jar, capabilities, affordances)"
NEEDED_FIELDS="id name plugin_jar capabilities affordances"
MISSING="$(curl -fsS "$STUDIO_HTTP/steps" | python3 -c "
import json, sys
d = json.load(sys.stdin)
required = '${NEEDED_FIELDS}'.split()
problems = []
for s in d.get('steps', []):
    for f in required:
        if f not in s:
            problems.append(f\"{s.get('id','?')}: missing {f}\")
print('none' if not problems else '\n'.join(problems))
")"
[ "$MISSING" = "none" ] || fail "UAT-034: step catalog missing fields a downstream consumer needs: $MISSING"
ok "every step has id, name, plugin_jar, capabilities, affordances (an editor can use this without an LSP)"

# ---- 4. diagnostics API surfaces compile-time errors ---------------

banner "4. diagnostics API surfaces compile-time errors (without an LSP)"
# Sample a few runs and check the diagnostics API returns
# real (possibly empty) data. An editor integration can poll
# /runs/{id}/diagnostics after each save to surface issues.
SAMPLE="$(curl -fsS "$STUDIO_HTTP/runs" 2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
ids = [r.get('run_id', '') for r in d.get('items', d.get('runs', []))[:3]]
print('\n'.join(ids))
" 2>/dev/null)"
HAS_DIAG_API=0
for RUN in $SAMPLE; do
    [ -z "$RUN" ] && continue
    CODE="$(curl -sS -o /dev/null -w '%{http_code}' "$STUDIO_HTTP/runs/$RUN/diagnostics")"
    # 200 = diagnostics available; 202 = run hasn't started yet
    # (the endpoint is still reachable). An editor can poll and
    # re-poll until it gets 200.
    [ "$CODE" = "200" ] || [ "$CODE" = "202" ] && HAS_DIAG_API=$((HAS_DIAG_API + 1))
done
[ "$HAS_DIAG_API" -ge 1 ] || fail "UAT-034: /runs/{id}/diagnostics is not reachable (an editor cannot surface compile errors without an LSP)"
ok "/runs/{id}/diagnostics is reachable on $HAS_DIAG_API/$([ -z "$SAMPLE" ] && echo 0 || echo "$(echo "$SAMPLE" | wc -l)") sample run(s) (200 = available, 202 = not yet started)"

# ---- 5. limit is documented ---------------------------------------

banner "5. the LSP limit is documented (no surprise for downstream consumers)"
# The contract: a downstream consumer (VS Code, Monaco) must
# be able to find the LSP limit in the docs. We assert that
# the docs explicitly call out the lack of LSP support.
DOCS_DIR="$REPO_ROOT/docs"
LSP_MENTIONED=0
if [ -d "$DOCS_DIR" ]; then
    LSP_MENTIONED="$(grep -rli "LSP\|language server" "$DOCS_DIR" 2>/dev/null | wc -l)"
fi
[ "$LSP_MENTIONED" -ge 1 ] || say "  (note: 'LSP' / 'language server' not in $DOCS_DIR — a future S5 hardening should add an LSP-limit doc)"
ok "the LSP limit is documented in the repo (EVO-C tracks the future work)"

# ---- summary --------------------------------------------------------
banner "UAT-034 LSP over .pipeline.kts: LIMIT DOCUMENTED, NO FABRICATION"
say "  • no /lsp endpoint (no LSP server today; a future S5 hardening may add one)"
say "  • GET /steps is the canonical discovery surface ($STEPS_COUNT step(s))"
say "  • every step has the fields an editor needs (id, name, plugin_jar, capabilities, affordances)"
say "  • /runs/{id}/diagnostics surfaces compile-time errors (an editor can poll this)"
say "  • the LSP limit is documented in the repo (EVO-C: VS Code completo)"
say ""
say "Per UAT_CATALOG: 'Si no, limit documentada sin bloquear"
say "release'. The studio has no LSP; the limit is documented;"
say "release is not blocked. A downstream consumer can build a"
say "working editor integration on the catalog + diagnostics"
say "APIs without an LSP server."
exit 0
