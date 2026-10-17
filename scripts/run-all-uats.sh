#!/usr/bin/env bash
# scripts/run-all-uats.sh — S5 regression aggregator (UAT-039/040 subset).
#
# Every `scripts/uat-*.sh` is one operator scenario. This aggregator runs each
# ONE TIME and reports a per-scenario verdict. It adds no UAT logic of its own:
# a new green UAT joins the set automatically (no central list to maintain).
#
# ## Verdict contract
#
#   0  every child reported PASSED
#   1  at least one child FAILED
#   2  usage error, or no UAT was selected (an empty selection is not a pass)
#   3  no child failed, but at least one was BLOCKED (runtime absent)
#
# A child is classified by its own exit code, which is the documented UAT
# verdict contract (see docs/05-testing/UAT_CATALOG.md):
#
#   0 = PASSED · 1 = FAILED · 2 = usage/prerequisite · 3 = BLOCKED
#
# Blocks and failures are never folded into "ALL PASSED": an aggregator that
# reports green when a child could not run is the same false-PASS channel the
# individual scripts no longer have.
#
# ## Selection (one selection point per UAT)
#
# `uat-podman-all.sh` already runs every `uat-podman-NNN.sh`; running those
# again here booted each Podman UAT twice (two containers, two Studio boots,
# two chances to leave state behind). The per-scenario Podman scripts are
# therefore excluded here and reached through their aggregator. `UAT_SKIP`
# takes a space-separated list of basenames to leave out.
#
# ## Cost
#
# The previous implementation slept a blind 30s after every child (~9 minutes
# for the current set) to stay under the Studio's in-process 60 req / 60 s
# rate limit. That limit only matters when the children share ONE live server,
# and it resets per Podman container, so the blind wait bought nothing for the
# majority of children. The wait is gone; `UAT_STAGGER_SECONDS` remains as an
# explicit opt-in for operators who point a whole run at a single live server.
#
# Usage: ./scripts/run-all-uats.sh [--help]
#   UAT_SKIP="uat-kill9.sh"     space-separated basenames to skip
#   UAT_STAGGER_SECONDS=0       optional pause between children
#   STUDIO_HTTP / STUDIO_HTTP_INSECURE / UAT_TIMEOUT / ... are passed through
#
# Child logs live in a per-run directory under TMPDIR and are removed on exit;
# the summary prints the last lines of each failing child before cleanup.

set -uo pipefail

say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
bad()    { say "  ❌ $*"; }
warn()   { say "  ⚠️  $*"; }
banner() { printf '\n== %s ==\n' "$*"; }

usage() {
    say "usage: $0 [--help]"
    say ""
    say "Runs every scripts/uat-*.sh once and aggregates the verdicts."
    say "There is no scenario selector: a per-scenario invocation is"
    say "  bash scripts/uat-<name>.sh   (or STUDIO_HTTP=... bash scripts/uat-from-distribution.sh)"
    say "Environment: UAT_SKIP (basenames), UAT_STAGGER_SECONDS (default 0)."
}

case "${1:-}" in
    --help | -h) usage; exit 0 ;;
    "") ;;
    *) say "usage error: unsupported argument '$1'" >&2; usage >&2; exit 2 ;;
esac

REPO_ROOT="${REPO_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
UAT_STAGGER_SECONDS="${UAT_STAGGER_SECONDS:-0}"
case "$UAT_STAGGER_SECONDS" in '' | *[!0-9]*) say "usage error: UAT_STAGGER_SECONDS must be an integer" >&2; exit 2 ;; esac

[ -d "$REPO_ROOT/scripts" ] || { say "usage error: scripts/ not found under $REPO_ROOT" >&2; exit 2; }

# --- selection ------------------------------------------------------------
mapfile -t DISCOVERED < <(find "$REPO_ROOT/scripts" -maxdepth 1 -name "uat-*.sh" -type f -executable | sort)

UAT_SCRIPTS=()
SKIPPED=()
for candidate in "${DISCOVERED[@]}"; do
    name="$(basename "$candidate")"
    # Nested aggregation: uat-podman-all.sh is the single entry point for the
    # per-scenario Podman UATs, so they are not selected a second time here.
    case "$name" in
        uat-podman-[0-9]*.sh) continue ;;
    esac
    case " ${UAT_SKIP:-} " in
        *" $name "*) SKIPPED+=("$name"); continue ;;
    esac
    UAT_SCRIPTS+=("$candidate")
done

if [ "${#UAT_SCRIPTS[@]}" -lt 1 ]; then
    say "usage error: no uat-*.sh scenarios selected under $REPO_ROOT/scripts (nothing to certify)" >&2
    exit 2
fi

# --- per-run owned logs under TMPDIR -------------------------------------
LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/uat-aggregator-XXXXXX")" || { say "cannot create log dir" >&2; exit 2; }
FAILURE_TAIL=""
# shellcheck disable=SC2329
cleanup() { [ -n "${LOG_DIR:-}" ] && rm -rf "$LOG_DIR"; }
trap cleanup EXIT

banner "UAT-039..040: regression aggregator — ${#UAT_SCRIPTS[@]} scenario(s)"
for skipped in "${SKIPPED[@]:-}"; do
    [ -n "$skipped" ] && warn "skipped by UAT_SKIP: $skipped"
done

PASSED=0
FAILED=0
BLOCKED=0
FAILED_NAMES=()
BLOCKED_NAMES=()

for UAT in "${UAT_SCRIPTS[@]}"; do
    NAME="$(basename "$UAT")"
    LOG="$LOG_DIR/$NAME.log"
    say "  → running $NAME"
    "$UAT" >"$LOG" 2>&1
    RC=$?
    case "$RC" in
        0)
            ok "$NAME: PASSED"
            PASSED=$((PASSED + 1))
            ;;
        3)
            warn "$NAME: BLOCKED (the runtime did not certify a run)"
            BLOCKED=$((BLOCKED + 1))
            BLOCKED_NAMES+=("$NAME")
            FAILURE_TAIL+="$(printf '\n--- %s (blocked) ---\n' "$NAME")"$'\n'"$(tail -n 8 "$LOG")"$'\n'
            ;;
        *)
            bad "$NAME: FAILED (rc=$RC)"
            FAILED=$((FAILED + 1))
            FAILED_NAMES+=("$NAME")
            FAILURE_TAIL+="$(printf '\n--- %s (failed, rc=%s) ---\n' "$NAME" "$RC")"$'\n'"$(tail -n 8 "$LOG")"$'\n'
            ;;
    esac
    # A 429 from the shared Studio is an environment signal, not a product
    # verdict; surface it instead of letting it look like a plain failure.
    if grep -qE 'RATE_LIMITED|HTTP 429' "$LOG" 2>/dev/null; then
        warn "$NAME hit the Studio rate limit; re-run with UAT_STAGGER_SECONDS=<n> when sharing one live server"
    fi
    [ "$UAT_STAGGER_SECONDS" -gt 0 ] && sleep "$UAT_STAGGER_SECONDS"
done

banner "UAT-039..040: summary"
say "  passed:  $PASSED / ${#UAT_SCRIPTS[@]}"
say "  blocked: $BLOCKED"
say "  failed:  $FAILED"
[ -n "$FAILURE_TAIL" ] && say "$FAILURE_TAIL"

if [ "$FAILED" -gt 0 ]; then
    say "  failed scenarios:"
    for n in "${FAILED_NAMES[@]}"; do say "    - $n"; done
fi
if [ "$BLOCKED" -gt 0 ]; then
    say "  blocked scenarios (not a pass):"
    for n in "${BLOCKED_NAMES[@]}"; do say "    - $n"; done
fi

if [ "$FAILED" -gt 0 ]; then
    say ""
    say "UAT_VERDICT: FAILED"
    exit 1
fi
if [ "$BLOCKED" -gt 0 ]; then
    say ""
    say "UAT_VERDICT: BLOCKED (partial: $PASSED passed / $BLOCKED blocked)"
    exit 3
fi
say ""
say "UAT_VERDICT: PASSED"
say "UAT-039..040: ALL PASSED"
say "  • every selected uat-*.sh is green"
exit 0
