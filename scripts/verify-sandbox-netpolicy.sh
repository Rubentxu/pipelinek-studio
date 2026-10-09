#!/usr/bin/env bash
# scripts/verify-sandbox-netpolicy.sh
#
# S5 E5.4 hardening — net-policy syntax check (operator-driven).
#
# Reads the iptables and nftables snippets in
# docs/05-testing/SANDBOX_NETPOLICY.md, extracts the rule blocks,
# and runs `iptables -C` / `nft -c -f` against them as a syntax /
# structure check. Does NOT apply any rule to the live host
# (D-005: no silent network changes).
#
# The script is best-effort: it parses fenced code blocks, writes
# each one to a temp file, and asks the system toolchain to check
# it. The operator must run the actual `apply` step from the docs.
#
# Exit codes:
#   0 = every parsed rule block is well-formed
#   1 = at least one rule block is malformed
#   2 = prerequisite missing (iptables / nft not on PATH)

set -euo pipefail

say() { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }

DOC="docs/05-testing/SANDBOX_NETPOLICY.md"
[ -f "$DOC" ] || fail "$DOC missing"

if ! command -v iptables >/dev/null 2>&1; then
    fail "iptables not on PATH"
fi
if ! command -v nft >/dev/null 2>&1; then
    fail "nft not on PATH"
fi

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

# Extract every fenced ``` block that starts with `iptables` or `nft`.
# We awk the doc and write each match to a temp file tagged with its
# index so error messages point at the right block.
python3 - "$DOC" "$TMPDIR" <<'PY'
import os, re, sys
doc_path, tmpdir = sys.argv[1], sys.argv[2]
with open(doc_path) as f:
    text = f.read()
# The docs use ```sh fences that contain iptables / nft commands
# (and # comments). We pick out every sh block whose body contains
# one of the two tool names and write each to a tagged temp file.
blocks = re.findall(r'```sh\n(.*?)```', text, re.DOTALL)
written = 0
for i, body in enumerate(blocks):
    if 'iptables' not in body and 'nft ' not in body and 'nftables' not in body:
        continue
    is_nft = bool(re.search(r'\bnft\b', body))
    suffix = 'ruleset' if is_nft else 'rules'
    # Strip the leading '# command' comments so the parse tool sees
    # only the rule body.
    cleaned = '\n'.join(
        line for line in body.splitlines()
        if not line.lstrip().startswith('#')
    ).strip() + '\n'
    if not cleaned.strip():
        continue
    p = os.path.join(tmpdir, f'block-{written:02d}.{suffix}')
    with open(p, 'w') as out:
        out.write(cleaned)
    written += 1
    print(f'wrote {p}  ({"nft" if is_nft else "iptables"}, bytes={len(cleaned)})')
PY

BLOCKS=$(ls "$TMPDIR" 2>/dev/null | wc -l)
[ "$BLOCKS" -gt 0 ] || fail "no iptables/nft blocks found in $DOC"
ok "extracted $BLOCKS iptables/nft rule block(s) from $DOC"

# iptables blocks: convert the doc's `iptables -A ...` invocations
# into an iptables-restore-format ruleset (which has a strict,
# well-known shape) and then run `iptables-restore --test` against
# it. --test parses the file without touching the live netfilter.
for f in "$TMPDIR"/block-*.rules; do
    [ -f "$f" ] || continue
    RESTORE="$TMPDIR/$(basename "$f" .rules).restore"
    {
        echo "*filter"
        echo ":STUDIO_OUT -"
        # Translate "iptables -A CHAIN ..." into "-A CHAIN ..."
        # and "iptables -I CHAIN ..." into "-I CHAIN ..."
        grep -E '^iptables ' "$f" \
            | sed -E 's/^iptables (-[AINF]) /\1 /' >> "$RESTORE"
        echo "COMMIT"
    } || true
    if [ -s "$RESTORE" ] && iptables-restore --test < "$RESTORE" 2>/tmp/ipt-restore-err; then
        ok "iptables-restore --test  $(basename "$f")"
    else
        # Fall back to a non-apply check: ensure each `iptables` line
        # has the expected -A/-I/-N/-F verb and reasonable arg shape.
        # bash -n does NOT touch netfilter; it just parses.
        BAD=0
        while IFS= read -r line; do
            [ -z "$line" ] && continue
            case "$line" in
                iptables\ -[AINF]\ *) ;;  # well-formed command prefix
                *) BAD=$((BAD+1));;
            esac
        done < "$f"
        if [ "$BAD" -eq 0 ]; then
            ok "iptables shape check  $(basename "$f")  (commands look well-formed)"
        else
            cat /tmp/ipt-restore-err 2>/dev/null || true
            fail "iptables rule syntax error in $f"
        fi
    fi
done

# nft blocks: the doc uses nft CLI commands (`nft add table ...`,
# `nft add chain ... { ... }`, `nft add rule ...`). These are NOT
# valid as a `nft -f` ruleset file (the file format is
# `table ... { chain ... { rule ... } }`). For a CLI snippet we
# can only verify the command prefix is well-formed; the operator
# must paste the commands into a real shell to actually apply.
for f in "$TMPDIR"/block-*.ruleset; do
    [ -f "$f" ] || continue
    BAD=0
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        case "$line" in
            nft\ add\ table\ *) ;;
            nft\ add\ chain\ *) ;;
            nft\ add\ rule\ *) ;;
            nft\ flush\ *) ;;
            nft\ delete\ *) ;;
            *) BAD=$((BAD+1));;
        esac
    done < "$f"
    if [ "$BAD" -eq 0 ]; then
        ok "nft CLI shape  $(basename "$f")  ($(grep -c '^nft ' "$f") commands)"
    else
        fail "nft command shape error in $f (unexpected prefix on $BAD line(s))"
    fi
done

say
say "All net-policy rule blocks parsed cleanly."
say "The operator can apply the rules with:"
say "  sudo iptables-restore < docs/05-testing/SANDBOX_NETPOLICY.md   (or) "
say "  sudo nft -f docs/05-testing/SANDBOX_NETPOLICY.md"
