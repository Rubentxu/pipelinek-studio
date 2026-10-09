#!/usr/bin/env bash
# scripts/generate-checksums.sh
#
# S5 deliverable: checksums for the distribution. Walks the install
# tree of `:studio-cli:installDist` and emits a stable SHA-256
# manifest as both JSON (machine-readable) and a sha256sum-compatible
# text format. Zero external dependencies — uses only `sha256sum` and
# standard POSIX tools.
#
# Usage:
#   ./scripts/generate-checksums.sh [<install-dir>] [<out-prefix>]
#   default install-dir: studio-cli/build/install/studio-cli
#   default out-prefix:  dist/checksums
#
# Output:
#   <out-prefix>.json  -- {"files":[{"path":"lib/foo.jar","sha256":"..."}], "summary":{...}}
#   <out-prefix>.txt   -- "<sha256>  <path>" lines (sha256sum-compatible)

set -euo pipefail

INSTALL_DIR="${1:-studio-cli/build/install/studio-cli}"
OUT_PREFIX="${2:-dist/checksums}"
mkdir -p "$(dirname "$OUT_PREFIX")"

if [ ! -d "$INSTALL_DIR" ]; then
    echo '{"error":"install dir not found: '"$INSTALL_DIR"'"}' > "$OUT_PREFIX.json"
    echo "ERROR: $INSTALL_DIR not found; wrote error JSON" >&2
    exit 1
fi

TXT="$OUT_PREFIX.txt"
JSON="$OUT_PREFIX.json"
: > "$TXT"

# Build the text manifest. Relative paths so the file is portable
# across machines with different absolute roots.
( cd "$INSTALL_DIR" && find . -type f | sort | while IFS= read -r f; do
    sha256sum "$f"
done ) > "$TXT"

FILE_COUNT=$(wc -l < "$TXT" | tr -d ' ')
# Aggregate the top-level directory for a quick sanity check.
ROOT_DIR=$(basename "$INSTALL_DIR")

# Emit JSON. We hand-roll it (no jq dependency) — the structure is
# deliberately small and stable.
{
    echo '{'
    echo '  "root": "'"$ROOT_DIR"'",'
    echo '  "algorithm": "SHA-256",'
    echo '  "fileCount": '"$FILE_COUNT"','
    echo '  "files": ['
    FIRST=1
    while IFS='  ' read -r HASH PATH; do
        [ -z "$HASH" ] && continue
        if [ $FIRST -eq 0 ]; then echo '    ,'; fi
        FIRST=0
        # Strip leading "./" from the path for portability.
        P="${PATH#./}"
        # Strip a single leading subdir (e.g. "lib/") so the JSON path
        # is "lib/<file>" — done in pure shell to avoid sed portability.
        if [ "${P%%/*}" = "lib" ] || [ "${P%%/*}" = "bin" ]; then
            P="${P#*/}"
        fi
        printf '    {"path":"%s","sha256":"%s"}' "$P" "$HASH"
    done < "$TXT"
    echo
    echo '  ]'
    echo '}'
} > "$JSON"

echo "[checksums] wrote $FILE_COUNT entries" >&2
echo "[checksums]   text: $TXT" >&2
echo "[checksums]   json: $JSON" >&2
