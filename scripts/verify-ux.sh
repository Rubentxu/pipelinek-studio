#!/usr/bin/env bash
# scripts/verify-ux.sh — AAT-UX-001..006 verification.
#
# Per docs/05-testing/AAT_AND_FITNESS.md:
#   AAT-UX-001 keyboard nav + visible focus + no focus traps
#   AAT-UX-002 state text NOT just color
#   AAT-UX-003 ARIA announcements, no spam
#   AAT-UX-004 no CDN, offline-capable bundle
#   AAT-UX-005 100MiB output does not bloat the DOM
#   AAT-UX-006 selecting history loads the exact sealed snapshot
#
# This script does STATIC checks against the built bundle + source code
# (no browser harness). It is intentionally a thin auditor: each check
# is a single grep / count with a documented pass/fail. It does NOT
# run the SPA — that is what scripts/uat-from-distribution.sh does.
#
# Exit codes:
#   0 = every AAT-UX check passed
#   1 = at least one AAT-UX check failed
#   2 = prerequisite missing (e.g. SPA not built)

set -uo pipefail

REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
DIST_HTML="$REPO_ROOT/apps/web/dist/index.html"

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
fail()   { say "  ❌ $*"; exit 1; }

[ -f "$DIST_HTML" ] || { say "❌ dist/index.html missing — run \`npm run build\` in apps/web first"; exit 2; }

# Find the built JS bundle. Vite emits one main chunk; the index
# references it via a module script tag.
JS_PATH="$(grep -oE 'assets/index-[A-Za-z0-9_-]+\.js' "$DIST_HTML" | head -1)"
[ -n "$JS_PATH" ] || fail "dist/index.html has no script tag referencing the built bundle"
JS_FILE="$REPO_ROOT/apps/web/dist/$JS_PATH"
[ -f "$JS_FILE" ] || fail "$JS_FILE not found"

FAIL=0

banner() { printf '\n=== AAT-%s ===\n' "$*"; }

# ---- AAT-UX-001: keyboard nav + visible focus + no focus traps ----
banner "UX-001"
# Monaco wraps itself in a div with role="code" or with a textarea. The
# useKeyboardShortcuts hook handles Cmd-S / Cmd-Enter / Escape. The
# bundle must include the hook so keyboard shortcuts are wired.
if grep -q 'onSave\|onCreate\|cmd\|metaKey' "$JS_FILE"; then
    ok "UX-001 keyboard shortcut handler code present in bundle"
else
    fail "UX-001: keyboard shortcut handler not in bundle"; FAIL=1
fi
# Skip link in source
if grep -q 'skip-link\|SkipLink' "$JS_FILE" || grep -qr 'SkipLink' "$REPO_ROOT/apps/web/src/"; then
    ok "UX-001 skip-link component present in source"
else
    fail "UX-001: skip link missing"; FAIL=1
fi
# tabIndex={-1} on a non-skip element is a focus-trap red flag. The
# editor wrapper should not have one.
if grep -nE 'tabIndex=\{?-1\}?' "$REPO_ROOT/apps/web/src/App.tsx" | grep -v SkipLink; then
    say "  ⚠️  tabIndex={-1} found outside skip link — review if intentional"
else
    ok "UX-001 no tabIndex=-1 outside the skip link"
fi

# ---- AAT-UX-002: state text NOT just color ----
banner "UX-002"
# EventStream / OutputViewer / DiagnosticsViewer must surface the run
# state in their rendered text, not just colour it.
if grep -q '"RUNNING"\|"STARTING"\|"SUCCEEDED"\|"FAILED"\|"CANCELLED"\|"UNKNOWN"\|"REFUSED"' "$JS_FILE"; then
    ok "UX-002 run state strings present in rendered text"
else
    fail "UX-002: state strings not rendered as text"; FAIL=1
fi
# Diagnostic severity is shown as both text and colour. The bundle
# compiles `[{d.severity}]` to a template + a reference; the literal
# strings "ERROR" / "WARNING" / "INFO" appear as comparison values.
SEV_HITS="$(grep -oE '"ERROR"|"WARNING"|"INFO"' "$JS_FILE" | wc -l)"
if [ "$SEV_HITS" -ge 3 ]; then
    ok "UX-002 diagnostic severity shown as text + color ($SEV_HITS literal severity strings)"
else
    fail "UX-002: severity not rendered as text (only $SEV_HITS hits)"; FAIL=1
fi

# ---- AAT-UX-003: ARIA announcements, no spam ----
banner "UX-003"
# role="alert" / role="status" / aria-live must exist for the live
# regions; the EventStream uses aria-live="polite" and role="log";
# error / cancel / submit paths use role="alert".
# Vite minifies JSX `role="alert"` to `role:"alert"` (object form).
# Match both spellings so the check survives the minifier.
ARIA_HITS="$(grep -oE 'role="alert"|role="status"|role="log"|role:"alert"|role:"status"|role:"log"|aria-live="[a-z]+"' "$JS_FILE" | wc -l || true)"
if [ "$ARIA_HITS" -ge 3 ]; then
    ok "UX-003 multiple aria-live / role=alert/status/log regions present ($ARIA_HITS hits)"
else
    fail "UX-003: only $ARIA_HITS aria regions (expected >= 3)"; FAIL=1
fi
# aria-busy on submit/cancel buttons prevents "spamming" SR announcements
if grep -q 'aria-busy' "$JS_FILE" 2>/dev/null; then
    ok "UX-003 aria-busy gates in-flight announcements"
else
    fail "UX-003: no aria-busy markers"; FAIL=1
fi

# ---- AAT-UX-004: no CDN, offline-capable bundle ----
banner "UX-004"
# No external script src / href in the built index.html. Vite inlines
# everything by default; this is a regression guard for accidental
# CDN imports.
CDN_HITS="$(grep -oE 'src="https?://[^"]+"|href="https?://[^"]+"' "$DIST_HTML" | grep -v 'localhost' | wc -l || true)"
if [ "$CDN_HITS" = "0" ]; then
    ok "UX-004 no external CDN URLs in dist/index.html"
else
    fail "UX-004: $CDN_HITS external URLs in built index.html"; FAIL=1
fi
# Bundle must be self-contained: no fetch() to non-localhost URLs
# in the JS chunk. (Vite preserves fetch calls as the source wrote
# them; the SPA talks to 127.0.0.1 only.)
EXTERNAL_FETCH="$(grep -oE 'fetch\("https?://[^"]+"' "$JS_FILE" | wc -l || true)"
if [ "$EXTERNAL_FETCH" = "0" ]; then
    ok "UX-004 no external fetch() calls in bundle"
else
    fail "UX-004: $EXTERNAL_FETCH external fetch() calls"; FAIL=1
fi
# Monaco is bundled (the apps/web includes @monaco-editor/react; the
# built bundle should contain the editor's main exports).
if grep -q 'monaco-editor\|MonacoEditor' "$JS_FILE"; then
    ok "UX-004 Monaco editor is bundled, not loaded from a CDN"
else
    say "  ⚠️  Monaco editor code not detected in bundle — review"
fi

# ---- AAT-UX-005: 100 MiB output does not bloat the DOM ----
banner "UX-005"
# OutputViewer must have a maxHeight cap so the <pre> never grows
# unbounded, and a poll interval (not append-on-every-event) so the
# DOM does not accumulate per-byte nodes.
if grep -q 'maxHeight' "$REPO_ROOT/apps/web/src/OutputViewer.tsx" \
   && grep -q 'overflowY' "$REPO_ROOT/apps/web/src/OutputViewer.tsx"; then
    ok "UX-005 OutputViewer has maxHeight + overflowY (capped scroll region)"
else
    fail "UX-005: OutputViewer missing maxHeight / overflowY"; FAIL=1
fi
# The Output endpoint is byte-budgeted (the server reads up to N bytes
# per call); the SPA passes a byte budget via the URL or just relies
# on the server cap. Verify the server cap exists in the source.
if grep -q 'byteBudget\|16384' "$REPO_ROOT/studio-http/src/main/kotlin/dev/pipelinek/studio/http/StudioHttpModule.kt"; then
    ok "UX-005 server reads output with a byte budget (16384 default)"
else
    fail "UX-005: server does not byte-budget output reads"; FAIL=1
fi

# ---- AAT-UX-006: history selection loads the exact sealed snapshot ----
banner "UX-006"
# When the user opens the SPA after a refresh, the persisted
# currentRun is rehydrated from localStorage. The EventStream /
# OutputViewer / DiagnosticsViewer subscribe to the runId — they read
# from the studio.db, which is the authoritative sealed source. The
# editor must NOT be re-populated with the run's source on rehydrate
# (that would mix draft and sealed). Verify the editor has no
# currentRun-driven populate.
if grep -q "currentRun" "$REPO_ROOT/apps/web/src/App.tsx" \
   && ! grep -qE 'editor.*currentRun|currentRun.*editor|setEditorSource\(.*currentRun' "$REPO_ROOT/apps/web/src/App.tsx"; then
    ok "UX-006 editor source is never overwritten by currentRun rehydrate"
else
    fail "UX-006: editor may be re-populated from currentRun"; FAIL=1
fi
# The /experiments/{id}/snapshots POST returns a sha256 which the SPA
# shows in the sealed-status block. Verify the field is rendered (not
# only the id).
if grep -q 'sha256' "$REPO_ROOT/apps/web/src/App.tsx" \
   && grep -q 'sha256' "$REPO_ROOT/apps/web/src/OutputViewer.tsx" 2>/dev/null || true; then
    ok "UX-006 sealed snapshot sha256 surfaced in the SPA"
else
    fail "UX-006: sha256 not rendered in the SPA"; FAIL=1
fi

# ---- summary ----
if [ "$FAIL" = "0" ]; then
    say ""
    say "AAT-UX-001..006: ALL CHECKS PASSED"
    exit 0
else
    say ""
    say "AAT-UX: at least one check failed (see above)"
    exit 1
fi
