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
# A script that starts a Studio in a container gets that PEM from the
# container itself through studio_http_trust_podman_container below: the
# certificate is exported from the keystore the server wrote, so the trust
# decision is a real anchor and never a trust-all flag.
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
# that endpoint is `http://` — BEFORE sourcing this file. A script that starts
# it in a container calls studio_http_trust_podman_container once the container
# is up and before the first request:
#
#   studio_http_trust_podman_container "$CONTAINER" "$TRUST_DIR"
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

# studio_http_apply_ca — export the client settings for the CA the caller
# selected. Split out of the load-time check (and re-run by
# studio_http_trust_podman_container) because a script that starts a container
# only learns its certificate after the container is up.
studio_http_apply_ca() {
    [ -n "${STUDIO_CA_CERT:-}" ] || return 0
    [ -r "$STUDIO_CA_CERT" ] || studio_http_contract_error "STUDIO_CA_CERT is not readable: $STUDIO_CA_CERT"
    case "$STUDIO_HTTP" in
        https://*) export CURL_CA_BUNDLE="$STUDIO_CA_CERT" SSL_CERT_FILE="$STUDIO_CA_CERT" ;;
        # A CA file for a plaintext target is meaningless; the opt-in above is
        # the only trust decision that applies there.
        *) ;;
    esac
}

studio_http_apply_ca

# studio_http_trust_podman_container — trust the certificate a Studio started
# by Podman generated for itself.
#
#   studio_http_trust_podman_container <container> <scratch-dir> [timeout-s]
#
# The shipped image entrypoint is `studio start --host 0.0.0.0 --data-dir
# /var/lib/pipelinek-studio` (docker/Dockerfile): HTTPS on 7778 and nothing
# else, because `--insecure-http` is not part of it. So the honest transport is
# https and the only open question is which certificate to trust. It is the one
# in the container's own keystore, exported through the documented
# `keytool -exportcert` path — a trust anchor, never a trust-all flag. A
# failure here is a contract/prerequisite error (exit 2) BEFORE the first
# request: the alternative would be to silently stop verifying.
studio_http_trust_podman_container() {
    local container="${1:?studio_http_trust_podman_container needs a container name}"
    local scratch="${2:?studio_http_trust_podman_container needs a scratch directory}"
    local timeout="${3:-60}"
    local keystore="$scratch/keystore.jks"
    local pem="$scratch/studio-loopback.pem"
    local deadline

    command -v podman >/dev/null 2>&1 || studio_http_contract_error \
        "podman is required to read the keystore of container $container"
    command -v keytool >/dev/null 2>&1 || studio_http_contract_error \
        "keytool is required to export the container certificate (documented trust path, see docs/05-testing/UAT_CATALOG.md)"
    mkdir -p "$scratch" || studio_http_contract_error "cannot create the trust scratch directory: $scratch"

    # The keystore appears on the container's first boot, so retry until the
    # deadline instead of assuming it is already on disk.
    deadline=$(( $(date +%s) + timeout ))
    while :; do
        if podman cp "$container:/var/lib/pipelinek-studio/keystore.jks" "$keystore" >/dev/null 2>&1 \
            && keytool -exportcert -rfc -alias studio-loopback \
                -keystore "$keystore" -storepass studio-loopback-change-me >"$pem" 2>/dev/null; then
            STUDIO_CA_CERT="$pem"
            export STUDIO_CA_CERT
            studio_http_apply_ca
            return 0
        fi
        if [ "$(date +%s)" -ge "$deadline" ]; then
            studio_http_contract_error \
                "could not export the Studio certificate from container $container within ${timeout}s"
        fi
        sleep 1
    done
}
