#!/usr/bin/env bash
# scripts/uat-sfw-h1-001-fabric-container.sh — SFW H1 Phase B Part 3
#
# UAT for the Fabric server container (the runtime half of
# the SFW H1 Phase B work). When the operator fixes the
# pre-existing `CommandCodec.kt` gap and runs
# `PipelinekFabric/scripts/build-fabric-image.sh`, this UAT
# validates that the resulting container:
#
#   1. starts (podman run -d)
#   2. binds the gRPC port (50051) on 127.0.0.1
#   3. passes a TCP liveness check (nc -z)
#   4. (optional) answers a gRPC health probe (grpcurl)
#   5. stops cleanly (podman rm -f)
#
# The UAT is intentionally standalone — no Gradle, no
# Studio, no test fixtures. It runs the same way the
# `uat-podman-*.sh` family does: against a real podman
# + a real image, with the operator's pre-built image
# tag as the input.
#
# Usage:
#   ./scripts/uat-sfw-h1-001-fabric-container.sh [IMAGE]
#     IMAGE defaults to fabric-server:0.5.0-R1-alpha.8
#     (the version pinned in PipelinekFabric/gradle.properties).
#
#   ./scripts/uat-sfw-h1-001-fabric-container.sh --self-test
#     Run the 5 falsifier scenarios (no podman required).
#     Useful in CI to gate the UAT script itself.
#
# Pre-conditions:
#   - The Fabric image has been built. The build is gated by
#     the pre-existing `CommandCodec.kt` gap; once the
#     operator closes that, this UAT is the verification.
#   - `podman` is on PATH.
#   - `nc` is on PATH.
#   - Port 50051 is free on 127.0.0.1.
#
# Exit codes:
#   0 — container started, liveness passed, container stopped.
#   1 — pre-condition failed (image missing, port busy, etc).
#   2 — container started but liveness failed.
#   3 — gRPC health probe failed (only when grpcurl present).
#   4 — container failed to stop (state is dirty; operator must
#       `podman rm -f` manually).

set -uo pipefail

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------
FABRIC_DIR="$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd || true)"
if [[ -z "${FABRIC_DIR}" || ! -d "${FABRIC_DIR}" ]]; then
    FABRIC_DIR=""
fi
# Resolve the image tag from PipelinekFabric/gradle.properties
# (pkfVersion) when available; fall back to a sane default.
DEFAULT_TAG="fabric-server:0.5.0-R1-alpha.8"
if [[ -n "${FABRIC_DIR}" && -f "${FABRIC_DIR}/../PipelinekFabric/gradle.properties" ]]; then
    PKF_VERSION=$(grep '^pkfVersion=' "${FABRIC_DIR}/../PipelinekFabric/gradle.properties" 2>/dev/null | cut -d'=' -f2 | tr -d ' ')
    if [[ -n "${PKF_VERSION}" ]]; then
        DEFAULT_TAG="fabric-server:${PKF_VERSION}"
    fi
fi
GRPC_PORT="${FABRIC_GRPC_PORT:-50051}"
GRPC_BIND="${FABRIC_GRPC_BIND:-127.0.0.1}"
CONTAINER_NAME="uat-sfw-h1-fabric-$$"
WAIT_FOR_PORT_SECS=30
GRPC_HEALTH_TIMEOUT_SECS=10

# ----------------------------------------------------------------------------
# Output helpers
# ----------------------------------------------------------------------------
say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
warn()   { say "  ⚠️  $*"; }
fail()   { say "  ❌ $*"; exit "${2:-1}"; }
banner() { printf '\n== %s ==\n' "$*"; }

# ----------------------------------------------------------------------------
# Cleanup trap
# ----------------------------------------------------------------------------
cleanup() {
    if [[ -n "${CONTAINER_NAME:-}" ]]; then
        podman rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# ----------------------------------------------------------------------------
# Falsifier scenarios (--self-test)
# ----------------------------------------------------------------------------
self_test() {
    banner "self-test: 5 falsifier scenarios (no podman required)"

    # F1: --help works
    if bash "$0" --help >/dev/null 2>&1; then
        ok "F1 --help exits 0"
    else
        fail "F1 --help must exit 0"
    fi

    # F2: default tag is non-empty
    if [[ -n "${DEFAULT_TAG}" ]]; then
        ok "F2 default tag is non-empty: ${DEFAULT_TAG}"
    else
        fail "F2 default tag must not be empty"
    fi

    # F3: helper functions are defined
    if declare -F ok >/dev/null && declare -F fail >/dev/null; then
        ok "F3 ok/fail helpers are defined"
    else
        fail "F3 ok/fail helpers must be defined"
    fi

    # F4: trap is set (cleanup on exit)
    if [[ -n "$(trap -p EXIT)" ]]; then
        ok "F4 EXIT trap is set (cleanup will run)"
    else
        fail "F4 EXIT trap must be set"
    fi

    # F5: configuration is consistent (port is numeric)
    if [[ "${GRPC_PORT}" =~ ^[0-9]+$ ]] && (( GRPC_PORT > 0 && GRPC_PORT < 65536 )); then
        ok "F5 GRPC_PORT is a valid port number: ${GRPC_PORT}"
    else
        fail "F5 GRPC_PORT must be a valid port; got '${GRPC_PORT}'"
    fi

    banner "self-test: 5/5 PASS"
}

# ----------------------------------------------------------------------------
# Pre-conditions
# ----------------------------------------------------------------------------
precheck_image() {
    local image="$1"
    if ! command -v podman >/dev/null 2>&1; then
        fail "podman is not on PATH (install podman or add it to PATH)" 1
    fi
    if ! podman image exists "$image" 2>/dev/null; then
        fail "image not found: $image

  To build:
    cd PipelinekFabric && ./scripts/build-fabric-image.sh

  The build is currently gated by the pre-existing
  CommandCodec.kt gap (operator-driven). This UAT will pass
  once the operator closes that gap." 1
    fi
    if ! command -v nc >/dev/null 2>&1; then
        fail "nc (netcat) is not on PATH; install netcat" 1
    fi
    if nc -z "${GRPC_BIND}" "${GRPC_PORT}" 2>/dev/null; then
        fail "port ${GRPC_PORT} on ${GRPC_BIND} is already in use; free it or set FABRIC_GRPC_PORT" 1
    fi
}

# ----------------------------------------------------------------------------
# Container lifecycle
# ----------------------------------------------------------------------------
start_container() {
    local image="$1"
    banner "starting container"
    if ! podman run -d --rm \
            --name "${CONTAINER_NAME}" \
            -p "${GRPC_BIND}:${GRPC_PORT}:50051" \
            "$image" >/dev/null; then
        fail "podman run failed; check podman logs for ${CONTAINER_NAME}" 2
    fi
    ok "container started: ${CONTAINER_NAME}"
}

wait_for_port() {
    local bind="$1" port="$2" deadline
    deadline=$(( SECONDS + WAIT_FOR_PORT_SECS ))
    banner "waiting for ${bind}:${port} to accept connections"
    while (( SECONDS < deadline )); do
        if nc -z "${bind}" "${port}" 2>/dev/null; then
            ok "port ${bind}:${port} is open (after $(( SECONDS - (deadline - WAIT_FOR_PORT_SECS) ))s)"
            return 0
        fi
        sleep 1
    done
    fail "port ${bind}:${port} did not open within ${WAIT_FOR_PORT_SECS}s" 2
}

grpc_health_check() {
    if ! command -v grpcurl >/dev/null 2>&1; then
        warn "grpcurl not on PATH; skipping gRPC health probe (TCP liveness already passed)"
        return 0
    fi
    banner "gRPC health probe (grpc.health.v1.Health/Check)"
    if ! timeout "${GRPC_HEALTH_TIMEOUT_SECS}" grpcurl \
            -plaintext \
            -connect-timeout 5 \
            "${GRPC_BIND}:${GRPC_PORT}" \
            grpc.health.v1.Health/Check >/dev/null 2>&1; then
        fail "gRPC health probe failed; container is up but the gRPC service did not answer" 3
    fi
    ok "gRPC health probe PASS"
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
main() {
    local image="${1:-${DEFAULT_TAG}}"
    if [[ "$image" == "--self-test" ]]; then
        self_test
        return 0
    fi
    if [[ "$image" == "--help" || "$image" == "-h" ]]; then
        cat <<EOF
Usage: $0 [IMAGE|--self-test|--help]

  IMAGE         fabric-server image to launch (default: ${DEFAULT_TAG})
  --self-test   run the 5 falsifier scenarios (no podman required)
  --help, -h    this help

Environment:
  FABRIC_GRPC_PORT    port to bind on the host (default: 50051)
  FABRIC_GRPC_BIND    bind address (default: 127.0.0.1)

Exit codes: 0 PASS, 1 pre-condition, 2 liveness, 3 gRPC, 4 cleanup.
EOF
        return 0
    fi

    banner "UAT-SFW-H1-001: Fabric server container"
    say "  image:        ${image}"
    say "  bind:         ${GRPC_BIND}:${GRPC_PORT} -> 50051 (container)"
    say "  container:    ${CONTAINER_NAME}"
    say "  pre-checks:   podman, nc, image, port-free"

    precheck_image "$image"
    ok "pre-checks PASS"

    start_container "$image"
    wait_for_port "${GRPC_BIND}" "${GRPC_PORT}"
    grpc_health_check

    banner "stopping container (cleanup)"
    if ! podman rm -f "${CONTAINER_NAME}" >/dev/null 2>&1; then
        fail "podman rm -f failed; state is dirty (operator: podman rm -f ${CONTAINER_NAME})" 4
    fi
    # Disable the trap (we already cleaned up).
    trap - EXIT
    ok "container stopped cleanly"

    banner "UAT-SFW-H1-001: PASS"
    return 0
}

main "$@"
