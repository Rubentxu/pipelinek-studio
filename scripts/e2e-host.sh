#!/usr/bin/env bash
# scripts/e2e-host.sh — host-mode e2e for an installed Studio binary.
#
# This is the "developer laptop" surface: build/install the standalone
# distribution, then run the checks against it. It is deliberately THIN.
#
# ## What it owns, and what it delegates
#
# It used to re-implement /health, /api/version (with a hardcoded
# `"1.0.0-rc.11"` literal), /openapi.json, createExperiment + sealSnapshot +
# startRun, the terminal poll, and the output read — a second copy of the flow
# in scripts/uat-from-distribution.sh, and one that drifted: it parsed
# `studio_run_id` (not the public `run_id`), sent no experiment_id, and
# reported `ALL CHECKS PASSED` with an empty run id and an unverified output
# marker.
#
# The runtime flow now has exactly one implementation: the canonical public UAT.
# This script owns only what is host-specific:
#
#   1. the installed distribution (STUDIO_HOME, build if absent)
#   2. the `studio doctor` diagnostic contract
#   3. delegating the acceptance flow and propagating its verdict verbatim
#
# Removing the duplicate flow deleted ~90 lines and, more importantly, the
# second chance to certify a run that never happened.
#
# ## Usage
#
#   STUDIO_HTTP=https://127.0.0.1:7778 STUDIO_CA_CERT=<pem> ./scripts/e2e-host.sh
#   STUDIO_HOME=./studio-cli/build/install/studio-cli ./scripts/e2e-host.sh
#
#   STUDIO_HTTP                required; passed to the canonical UAT
#   STUDIO_HTTP_INSECURE=1     allow plain (test) http://
#   STUDIO_CA_CERT             PEM to trust for the Studio's loopback cert
#   STUDIO_HOME                install dir; defaults to the Gradle installDist
#                              output, which is also the only case where a
#                              missing binary triggers an automatic build
#
# Exit codes are the canonical UAT's: 0 PASSED · 1 FAILED · 2 usage/prerequisite
# · 3 BLOCKED. A blocked runtime never reports success here.

set -uo pipefail

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
bad()    { say "  ❌ $*"; }
banner() { printf '\n== %s ==\n' "$*"; }

die() { say "E2E_HOST_ERROR: $*" >&2; exit 2; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
DEFAULT_HOME="$REPO_ROOT/studio-cli/build/install/studio-cli"
STUDIO_HOME="${1:-$DEFAULT_HOME}"
UAT="$REPO_ROOT/scripts/uat-from-distribution.sh"

[ -n "${STUDIO_HTTP:-}" ] || die "STUDIO_HTTP is required (e.g. https://127.0.0.1:7778); the canonical UAT has no implicit default"
[ -f "$UAT" ] || die "canonical UAT not found at $UAT"

# --- 1. Installed distribution -------------------------------------------
banner "1. Studio distribution at $STUDIO_HOME"
BIN="$STUDIO_HOME/bin/studio-cli"
if [ ! -x "$BIN" ] && [ "$STUDIO_HOME" = "$DEFAULT_HOME" ]; then
    say "  studio-cli not found at $BIN"
    say "  building via :studio-cli:installDist (default install path only)"
    (cd "$REPO_ROOT" && ./gradlew :studio-cli:installDist --console=plain 2>&1 | tail -3) \
        || die "installDist failed"
fi
[ -x "$BIN" ] || die "no executable at $BIN (pass STUDIO_HOME=<install dir> or build :studio-cli:installDist)"
ok "studio-cli present: $BIN"

# --- 2. doctor (host-specific diagnostics) -------------------------------
banner "2. studio doctor"
DOCTOR="$("$BIN" doctor 2>&1 || true)"
DOCTOR_RC=0
# label:key pairs — the label is the operator-facing contract, the key is the
# wire contract `studio doctor` must keep emitting.
for pair in "version reported:studio_cli_version" "db writable:db_path_writable" "data dir perms:data_dir_permissions"; do
    label="${pair%%:*}"
    key="${pair#*:}"
    if printf '%s' "$DOCTOR" | grep -q "$key"; then
        ok "doctor: $label ($key)"
    else
        bad "doctor: $label missing from 'studio doctor' output (expected key $key)"
        DOCTOR_RC=1
    fi
done
if [ "$DOCTOR_RC" -ne 0 ]; then
    printf '%s\n' "$DOCTOR" | tail -20 | sed 's/^/    /'
    say "E2E_HOST: FAILED (doctor contract)"
    exit 1
fi

# --- 3. Canonical acceptance flow ----------------------------------------
banner "3. Delegate to the canonical UAT (scripts/uat-from-distribution.sh)"
say "  the verdict below is produced by the canonical UAT, not by this wrapper"
STUDIO_CLI_BIN="$BIN" bash "$UAT"
RC=$?
case "$RC" in
    0)
        banner "e2e summary"
        ok "ALL CHECKS PASSED (host-mode e2e)"
        say "  distribution: $STUDIO_HOME"
        say "  verdict:      see UAT_VERDICT: PASSED above"
        exit 0
        ;;
    3)
        banner "e2e summary"
        say "  ⚠️  BLOCKED — the canonical UAT did not certify a run (see UAT_VERDICT: BLOCKED)"
        say "E2E_HOST: BLOCKED (not a pass)"
        exit 3
        ;;
    2)
        banner "e2e summary"
        say "E2E_HOST: usage/prerequisite error from the canonical UAT"
        exit 2
        ;;
    *)
        banner "e2e summary"
        say "  ❌ FAILED — the canonical UAT reported UAT_VERDICT: FAILED"
        say "E2E_HOST: FAILED"
        exit 1
        ;;
esac
