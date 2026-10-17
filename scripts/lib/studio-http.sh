#!/usr/bin/env bash
# scripts/lib/studio-http.sh — THE transport contract for every script that
# polls a running Studio.
#
# ## Why this file exists
#
# Studio serves HTTPS on 127.0.0.1:7778 by default and plain HTTP on
# 127.0.0.1:7777 only under the documented `--insecure-http` opt-in (see
# docs/04-security/transport.md). Every operator script used to carry
#     STUDIO_HTTP="${STUDIO_HTTP:-http://127.0.0.1:7777}"
# so a default (TLS) Studio was polled over a scheme it never listens on: the
# script reported "studio unreachable" for a healthy server and the failure
# looked like a product defect. There is no implicit target anywhere any more;
# the operator states the transport once and every script validates it here.
#
# ## The contract
#
#   STUDIO_HTTP              REQUIRED base URL, `https://…` or `http://…`.
#                            Never defaulted: a guessed scheme is the bug.
#   STUDIO_HTTP_INSECURE=1   REQUIRED when STUDIO_HTTP is `http://`, the same
#                            opt-in the CLI and the MCP adapter enforce.
#   STUDIO_CA_CERT=<pem>     PEM to trust for the self-signed loopback HTTPS
#                            certificate (export it with `keytool -exportcert`,
#                            see docs/05-testing/UAT_CATALOG.md).
#
# Sourcing this file validates the contract immediately and exits 2 (usage /
# prerequisite, before the first request) when it is violated. On success it
# exports the trust settings the HTTP clients below already honour:
#
#   CURL_CA_BUNDLE   the bundle `curl --cacert` would use
#   SSL_CERT_FILE    the same bundle for python's urllib
#
# so a self-signed loopback certificate is trusted through the documented CA
# path. There is no `-k`, no trust-all and no `--insecure`: a certificate
# failure stays a failure.
#
# ## Usage
#
#   # shellcheck source=lib/studio-http.sh disable=SC1091
#   . "$(dirname "${BASH_SOURCE[0]}")/lib/studio-http.sh"
#
# A script that starts the Studio itself sets STUDIO_HTTP to the literal
# endpoint it just bound — and passes the matching `--insecure-http` flag when
# that endpoint is `http://` — BEFORE sourcing this file.
#
# A script that already has its own usage handler (`die_usage`) keeps it: the
# contract failure is reported through that handler so the operator still gets
# the script's usage text.

# Defined before any check so `set -u` callers can always reach it.
studio_http_contract_error() {
    if [ "$(type -t die_usage 2>/dev/null)" = "function" ]; then
        die_usage "$@"
    fi
    {
        printf 'STUDIO_HTTP_CONTRACT_ERROR: %s\n\n' "$*"
        printf 'The transport is never implicit; state it once:\n'
        printf '  STUDIO_HTTP=https://127.0.0.1:7778 STUDIO_CA_CERT=studio-loopback.pem   # TLS (the default)\n'
        printf '  STUDIO_HTTP=http://127.0.0.1:7777 STUDIO_HTTP_INSECURE=1                # plain HTTP opt-in\n'
    } >&2
    exit 2
}

STUDIO_HTTP="${STUDIO_HTTP:-}"
[ -n "$STUDIO_HTTP" ] || studio_http_contract_error \
    "STUDIO_HTTP is required (e.g. https://127.0.0.1:7778); there is no implicit default because Studio serves TLS by default"
STUDIO_HTTP="${STUDIO_HTTP%/}"

case "$STUDIO_HTTP" in
    https://?*) ;;
    http://?*)
        [ "${STUDIO_HTTP_INSECURE:-}" = "1" ] || studio_http_contract_error \
            "STUDIO_HTTP is http:// but STUDIO_HTTP_INSECURE is not set. Set STUDIO_HTTP_INSECURE=1 to acknowledge plaintext test HTTP, or use https://"
        ;;
    *) studio_http_contract_error "STUDIO_HTTP must be an http:// or https:// URL, got: $STUDIO_HTTP" ;;
esac

if [ -n "${STUDIO_CA_CERT:-}" ]; then
    [ -r "$STUDIO_CA_CERT" ] || studio_http_contract_error "STUDIO_CA_CERT is not readable: $STUDIO_CA_CERT"
    case "$STUDIO_HTTP" in
        https://*) export CURL_CA_BUNDLE="$STUDIO_CA_CERT" SSL_CERT_FILE="$STUDIO_CA_CERT" ;;
        # A CA file for a plaintext target is meaningless; the opt-in above is
        # the only trust decision that applies there.
        *) ;;
    esac
fi
