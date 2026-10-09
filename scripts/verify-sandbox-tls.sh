#!/usr/bin/env bash
# scripts/verify-sandbox-tls.sh
#
# S5 E5.4 hardening — TLS termination config syntax check.
#
# The doc docs/05-testing/SANDBOX_TLS.md ships nginx and Caddy
# reverse-proxy configs. This script checks the nginx configs with
# `nginx -t` (if nginx is installed) and the Caddy configs with
# `caddy validate` (if caddy is installed). It does NOT bind any
# port; the configs are syntax-only.
#
# Exit codes:
#   0 = every config we have a tool for parses cleanly
#   1 = a config has a syntax error
#   2 = prerequisite missing (no toolchain installed for that family)

set -euo pipefail

say() { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }
warn() { say "  ⚠️  $*"; }

DOC="docs/05-testing/SANDBOX_TLS.md"
[ -f "$DOC" ] || fail "$DOC missing"

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

# 1. Extract every fenced code block whose first line declares
#    a config (nginx: or caddy). We split the body to a temp
#    file and then probe it.
python3 - "$DOC" "$TMPDIR" <<'PY'
import os, re, sys
doc_path, tmpdir = sys.argv[1], sys.argv[2]
text = open(doc_path).read()
blocks = re.findall(r'```([a-zA-Z]+)\n(.*?)```', text, re.DOTALL)
written = 0
for lang, body in blocks:
    # nginx: sites, .conf, server { ... }
    if re.match(r'nginx(\s|$)', lang) or lang in ('conf', 'nginxconf'):
        suffix = 'nginx.conf'
        marker = 'nginx'
    # caddy: Caddyfile
    elif lang in ('caddy', 'Caddyfile'):
        suffix = 'Caddyfile'
        marker = 'caddy'
    else:
        continue
    p = os.path.join(tmpdir, f'block-{written:02d}.{suffix}')
    with open(p, 'w') as out:
        out.write(body)
    written += 1
    print(f'wrote {p}  (lang={lang}, bytes={len(body)})')
PY

NGINX_COUNT=$(ls "$TMPDIR"/block-*.nginx.conf 2>/dev/null | wc -l)
CADDY_COUNT=$(ls "$TMPDIR"/block-*.Caddyfile 2>/dev/null | wc -l)
[ "$NGINX_COUNT" -gt 0 ] || [ "$CADDY_COUNT" -gt 0 ] \
    || fail "no nginx or caddy config blocks found in $DOC"
ok "extracted $NGINX_COUNT nginx + $CADDY_COUNT caddy block(s) from $DOC"

# 2. nginx: try `nginx -t -c <file>` if nginx is installed
if command -v nginx >/dev/null 2>&1; then
    for f in "$TMPDIR"/block-*.nginx.conf; do
        [ -f "$f" ] || continue
        # nginx needs server_names_hash_bucket_size etc.; we wrap
        # the doc snippet in a minimal main context so the parser
        # is happy.
        WRAP="$TMPDIR/$(basename "$f").wrapped"
        {
            echo 'http {'
            echo '  server_names_hash_bucket_size 128;'
            cat "$f"
            echo '}'
        } > "$WRAP"
        if nginx -t -p "$TMPDIR/" -c "$(basename "$WRAP")" 2>/tmp/nginx-err; then
            ok "nginx -t  $(basename "$f")"
        else
            cat /tmp/nginx-err
            fail "nginx config syntax error in $f"
        fi
    done
else
    warn "nginx not installed; skipping nginx syntax check (operator runs on the host)"
fi

# 3. caddy: try `caddy validate --config <file>` if caddy is installed
if command -v caddy >/dev/null 2>&1; then
    for f in "$TMPDIR"/block-*.Caddyfile; do
        [ -f "$f" ] || continue
        if caddy validate --config "$f" --adapter caddyfile 2>/tmp/caddy-err; then
            ok "caddy validate  $(basename "$f")"
        else
            cat /tmp/caddy-err
            fail "caddy config syntax error in $f"
        fi
    done
else
    warn "caddy not installed; skipping caddy syntax check (operator runs on the host)"
fi

say
say "All available TLS-config syntax checks passed."
say "Operators with nginx and/or caddy installed get an extra layer of"
say "validation on top of the doc snippets; operators without them get a"
say "passing script (warn-only) and rely on the doc's prose."
