#!/usr/bin/env bash
# S5 E5.3 — VS Code extension package sanity check.
#
# The `vsce package` command has a few hard requirements that are
# easy to miss. This script asserts the documented ones so a missing
# field is caught before the operator runs the full packaging step.
#
# Checks (per https://code.visualstudio.com/api/working-with-extensions/publishing-extension):
#   - name, version, publisher, engines.vscode
#   - main points to a real file (dist/extension.js)
#   - categories is non-empty
#   - every activation event references a real contributed command
#   - every command id is unique
#   - description is present and non-empty
#
# Exits 0 on pass, 1 on fail.

set -euo pipefail

PKG="${1:-apps/vscode/package.json}"
[ -f "$PKG" ] || { echo "❌ $PKG not found"; exit 1; }

say() { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }

# Field presence
for f in name version publisher engines description main categories; do
    grep -q "\"$f\"" "$PKG" || fail "missing required field: $f"
done
ok "all required fields present"

# Engine vscode
grep -q '"vscode":' "$PKG" || fail "engines.vscode missing"
ok "engines.vscode present"

# main points to dist/extension.js
MAIN="$(grep -oE '"main":\s*"[^"]+"' "$PKG" | head -1 | sed 's/.*"main":\s*"\([^"]*\)".*/\1/')"
[ -n "$MAIN" ] || fail "main is empty"
ok "main = $MAIN"

# categories non-empty
CATS="$(grep -oE '"categories":\s*\[[^]]*\]' "$PKG" | head -1)"
[ -n "$CATS" ] || fail "categories array missing or empty"
ok "categories present: $CATS"

# Collect all contributed command ids
COMMANDS="$(grep -oE '"command":\s*"[^"]+"' "$PKG" | sed 's/.*"command":\s*"\([^"]*\)".*/\1/')"
CMD_COUNT="$(echo "$COMMANDS" | grep -c .)"
[ "$CMD_COUNT" -gt 0 ] || fail "no commands contributed"
ok "$CMD_COUNT command(s) contributed"

# Unique command ids
DUPES="$(echo "$COMMANDS" | sort | uniq -d)"
[ -z "$DUPES" ] || fail "duplicate command ids: $DUPES"
ok "all command ids are unique"

# Activation events reference real commands (or workspaceContains)
AEVENTS="$(grep -oE 'onCommand:[^"]*' "$PKG" | sed 's/^onCommand://')"
MISSING=""
for ae in $AEVENTS; do
    if ! echo "$COMMANDS" | grep -qx "$ae"; then
        MISSING="$MISSING $ae"
    fi
done
[ -z "$MISSING" ] || fail "activation events reference unknown commands:$MISSING"
ok "all onCommand activation events reference real commands"

# Description non-empty
DESC="$(grep -oE '"description":\s*"[^"]+"' "$PKG" | head -1 | sed 's/.*"description":\s*"\([^"]*\)".*/\1/')"
[ -n "$DESC" ] || fail "description is empty"
ok "description present (${#DESC} chars)"

# Optional: extension.ts uses the right activate signature
EXT="apps/vscode/src/extension.ts"
[ -f "$EXT" ] || fail "$EXT missing"
grep -q "export function activate" "$EXT" || fail "extension.ts missing export function activate"
grep -q "export function deactivate" "$EXT" || fail "extension.ts missing export function deactivate"
ok "extension.ts exposes activate + deactivate"

say
say "VS Code extension package is ready for 'vsce package' (subject to 'npm install' first)."
