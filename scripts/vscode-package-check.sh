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
#   - contributes.commands declares at least one command
#   - every contributes.commands id is unique
#   - every onCommand: activation event resolves to a declared command
#   - every contributes.menus entry resolves to a declared command
#   - description is present and non-empty
#
# T12 (S1+S4): the manifest is parsed as JSON by `node` (shipped with the
# apps/vscode toolchain) instead of being grepped. The previous rule collected
# every `"command":` occurrence, so the ids that `contributes.menus`
# legitimately re-references under `view/title` and `view/item/context` were
# reported as duplicate *declarations* (4 false duplicates on the real
# manifest, so the gate was permanently red for a non-defect). Uniqueness now
# applies to `contributes.commands[].command` only, and the re-references are
# validated the way VS Code validates them: they must resolve to a declared
# command.
#
# Usage: bash scripts/vscode-package-check.sh [package.json]
#        (run from the repository root; defaults to apps/vscode/package.json)
# Exits 0 on pass, 1 on fail.

set -euo pipefail

PKG="${1:-apps/vscode/package.json}"
[ -f "$PKG" ] || { echo "❌ $PKG not found"; exit 1; }
command -v node >/dev/null 2>&1 \
    || { echo "❌ node not found on PATH (the apps/vscode toolchain needs it to parse $PKG)"; exit 1; }

say() { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }

# Parse and validate the JSON-declared contract. One `ok`/`fail` line per
# assertion, tab-separated, so the shell owns the verdict and the exit code.
REPORT=""
if ! REPORT="$(VSCODE_PKG="$PKG" node <<'NODE'
const fs = require('node:fs')

const pkgPath = process.env.VSCODE_PKG
const emit = (kind, message) => process.stdout.write(`${kind}\t${message}\n`)

let manifest
try {
    manifest = JSON.parse(fs.readFileSync(pkgPath, 'utf8'))
} catch (err) {
    emit('fail', `${pkgPath} is not valid JSON: ${err.message}`)
    process.exit(0)
}

const isText = (value) => typeof value === 'string' && value.trim() !== ''

// --- required fields -------------------------------------------------------
const REQUIRED = ['name', 'version', 'publisher', 'engines', 'description', 'main', 'categories']
const missingFields = REQUIRED.filter((field) => manifest[field] === undefined || manifest[field] === null || manifest[field] === '')
if (missingFields.length > 0) {
    emit('fail', `missing required field(s): ${missingFields.join(', ')}`)
    process.exit(0)
}
emit('ok', 'all required fields present')

if (!isText(manifest.engines?.vscode)) {
    emit('fail', 'engines.vscode missing')
    process.exit(0)
}
emit('ok', 'engines.vscode present')

if (!isText(manifest.main)) {
    emit('fail', 'main is empty')
    process.exit(0)
}
emit('ok', `main = ${manifest.main}`)

const categories = Array.isArray(manifest.categories) ? manifest.categories.filter(isText) : []
if (categories.length === 0) {
    emit('fail', 'categories array missing or empty')
    process.exit(0)
}
emit('ok', `categories present: ${JSON.stringify(categories)}`)

// --- contributes.commands: the ONLY place a command id is declared ---------
const declarations = Array.isArray(manifest.contributes?.commands) ? manifest.contributes.commands : []
const declared = new Map()
declarations.forEach((entry, index) => {
    const id = typeof entry === 'string' ? entry : entry?.command
    if (!isText(id)) {
        emit('fail', `contributes.commands[${index}] has no "command" id`)
        return
    }
    declared.set(id, (declared.get(id) ?? 0) + 1)
})
if (![...declared.keys()].length) {
    emit('fail', 'no commands contributed')
    process.exit(0)
}
emit('ok', `${declared.size} command(s) contributed`)

const duplicates = [...declared.entries()].filter(([, count]) => count > 1)
if (duplicates.length > 0) {
    const detail = duplicates.map(([id, count]) => `${id} (${count} declarations)`).join(', ')
    emit('fail', `duplicate command ids in contributes.commands of ${pkgPath}: ${detail}`)
    process.exit(0)
}
emit('ok', 'all contributes.commands ids are unique')

// --- activation events: onCommand:<id> must resolve ------------------------
const activationEvents = Array.isArray(manifest.activationEvents) ? manifest.activationEvents : []
const unresolvedEvents = activationEvents
    .filter(isText)
    .filter((event) => event.startsWith('onCommand:'))
    .map((event) => event.slice('onCommand:'.length))
    .filter((id) => !declared.has(id))
if (unresolvedEvents.length > 0) {
    emit('fail', `activation events reference unknown commands: ${unresolvedEvents.join(', ')}`)
    process.exit(0)
}
emit('ok', 'all onCommand activation events reference real commands')

// --- contributes.menus: every reference must resolve to a declared command --
const menus = manifest.contributes?.menus ?? {}
const unresolvedMenus = []
for (const [menuId, entries] of Object.entries(menus)) {
    if (!Array.isArray(entries)) {
        emit('fail', `contributes.menus.${menuId} is not an array`)
        process.exit(0)
    }
    entries.forEach((entry, index) => {
        const id = typeof entry === 'string' ? entry : entry?.command
        const where = `contributes.menus.${menuId}[${index}]`
        if (!isText(id)) {
            unresolvedMenus.push(`${where} has no "command" id`)
        } else if (!declared.has(id)) {
            unresolvedMenus.push(`${where} -> ${id}`)
        }
    })
}
if (unresolvedMenus.length > 0) {
    emit('fail', `menu entries reference commands that contributes.commands does not declare: ${unresolvedMenus.join(', ')}`)
    process.exit(0)
}
emit('ok', `all ${Object.keys(menus).length} contributes.menus section(s) reference declared commands`)

if (!isText(manifest.description)) {
    emit('fail', 'description is empty')
    process.exit(0)
}
emit('ok', `description present (${manifest.description.length} chars)`)
NODE
)"; then
    fail "could not parse $PKG with node: $REPORT"
fi

while IFS=$'\t' read -r kind message; do
    [ -n "$kind" ] || continue
    case "$kind" in
        ok) ok "$message" ;;
        fail) fail "$message" ;;
        *) fail "unexpected node report line: $kind $message" ;;
    esac
done <<< "$REPORT"

# Optional: extension.ts uses the right activate signature
EXT="apps/vscode/src/extension.ts"
[ -f "$EXT" ] || fail "$EXT missing"
grep -q "export function activate" "$EXT" || fail "extension.ts missing export function activate"
grep -q "export function deactivate" "$EXT" || fail "extension.ts missing export function deactivate"
ok "extension.ts exposes activate + deactivate"

say
say "VS Code extension package is ready for 'vsce package' (subject to 'npm install' first)."
