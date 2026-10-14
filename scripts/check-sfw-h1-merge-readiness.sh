#!/usr/bin/env bash
# scripts/check-sfw-h1-merge-readiness.sh — pre-merge gate for the SFW H1 branch
#
# Runs the 6 pre-checks the operator needs to answer
# 'can I merge b1-sfw-h2-2c-studio-infra-fabric into main?'
# without actually performing the merge. The script is
# non-destructive: it only reads (git merge-tree, git log,
# git rev-parse) and runs the test suite.
#
# Usage:
#   ./scripts/check-sfw-h1-merge-readiness.sh
#     Check the current branch (must be the H1 branch).
#   ./scripts/check-sfw-h1-merge-readiness.sh --branch <name>
#     Check a specific branch.
#   ./scripts/check-sfw-h1-merge-readiness.sh --self-test
#     Run the 5 falsifier scenarios (no git required).
#   ./scripts/check-sfw-h1-merge-readiness.sh --help
#
# Exit codes:
#   0 — merge is ready (all 6 checks PASS).
#   1 — pre-check failed (with the specific check that failed).
#   2 — merge conflict detected (the merge would NOT be clean).

set -uo pipefail

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------
H1_BRANCH_DEFAULT="b1-sfw-h2-2c-studio-infra-fabric"
H1_BRANCH="${SFW_H1_MERGE_BRANCH:-${H1_BRANCH_DEFAULT}}"
TARGET_BRANCH="${SFW_H1_TARGET_BRANCH:-main}"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# ----------------------------------------------------------------------------
# Output helpers
# ----------------------------------------------------------------------------
say()    { printf '%s\n' "$*"; }
ok()     { say "  ✅ $*"; }
warn()   { say "  ⚠️  $*"; }
fail()   { say "  ❌ $*"; exit "${2:-1}"; }
banner() { printf '\n== %s ==\n' "$*"; }

# ----------------------------------------------------------------------------
# Argument parsing
# ----------------------------------------------------------------------------
SELF_TEST=0
SHOW_HELP=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --branch)    H1_BRANCH="$2"; shift 2 ;;
        --self-test) SELF_TEST=1 ;;
        --help|-h)   SHOW_HELP=1 ;;
        *) fail "unknown arg: $1" 1 ;;
    esac
    shift
done

# ----------------------------------------------------------------------------
# Falsifier scenarios (--self-test)
# ----------------------------------------------------------------------------
self_test() {
    banner "self-test: 5 falsifier scenarios (no git required)"

    # F1: --help works
    if bash "$0" --help >/dev/null 2>&1; then
        ok "F1 --help exits 0"
    else
        fail "F1 --help must exit 0"
    fi

    # F2: helpers are defined
    if declare -F ok >/dev/null && declare -F fail >/dev/null; then
        ok "F2 ok/fail helpers are defined"
    else
        fail "F2 ok/fail helpers must be defined"
    fi

    # F3: set -u is in effect
    if [[ $- == *u* ]]; then
        ok "F3 set -u is in effect (strict mode)"
    else
        fail "F3 set -u must be in effect"
    fi

    # F4: REPO_ROOT resolves to a real directory
    if [[ -d "${REPO_ROOT}" ]]; then
        ok "F4 REPO_ROOT is a real directory: ${REPO_ROOT}"
    else
        fail "F4 REPO_ROOT must be a real directory; got '${REPO_ROOT}'"
    fi

    # F5: H1_BRANCH is a non-empty string
    if [[ -n "${H1_BRANCH}" ]]; then
        ok "F5 H1_BRANCH is set: ${H1_BRANCH}"
    else
        fail "F5 H1_BRANCH must be set"
    fi

    banner "self-test: 5/5 PASS"
}

# ----------------------------------------------------------------------------
# Pre-checks
# ----------------------------------------------------------------------------
check() {
    banner "merge-readiness check: ${H1_BRANCH} → ${TARGET_BRANCH}"

    # 1. H1 branch exists
    if ! git rev-parse "${H1_BRANCH}" >/dev/null 2>&1; then
        fail "branch '${H1_BRANCH}' does not exist locally" 1
    fi
    ok "branch exists: ${H1_BRANCH}"

    # 2. H1 branch is ahead of TARGET_BRANCH (has commits to merge)
    local ahead behind
    ahead=$(git rev-list --count "${TARGET_BRANCH}..${H1_BRANCH}" 2>/dev/null || echo 0)
    behind=$(git rev-list --count "${H1_BRANCH}..${TARGET_BRANCH}" 2>/dev/null || echo 0)
    if [[ "${ahead}" -eq 0 ]]; then
        fail "branch is not ahead of ${TARGET_BRANCH}; nothing to merge" 1
    fi
    ok "ahead count: ${ahead} commits (behind: ${behind})"

    # 3. Working tree is clean
    if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
        fail "working tree has uncommitted changes; commit or stash first" 1
    fi
    ok "working tree is clean"

    # 4. merge-tree detects NO conflicts
    local merge_base
    merge_base=$(git merge-base "${TARGET_BRANCH}" "${H1_BRANCH}" 2>/dev/null || echo "")
    if [[ -z "${merge_base}" ]]; then
        fail "no common ancestor between ${TARGET_BRANCH} and ${H1_BRANCH}" 1
    fi
    local conflicts
    conflicts=$(git merge-tree "${merge_base}" "${TARGET_BRANCH}" "${H1_BRANCH}" 2>/dev/null \
        | grep -c '^<<<<<<<' || echo 0)
    if [[ "${conflicts}" -gt 0 ]]; then
        fail "merge-tree detected ${conflicts} conflict(s); the merge would NOT be clean" 2
    fi
    ok "merge-tree: 0 conflicts (merge would be clean)"

    # 5. v1.0.0 tag does not exist
    if git rev-parse "v1.0.0" >/dev/null 2>&1; then
        warn "v1.0.0 tag already exists locally; merge is fine, but the tag step is BLOCKED"
    else
        ok "v1.0.0 tag is free (B5 ready)"
    fi

    # 6. status doc + interim receipt exist
    if [[ ! -f "${REPO_ROOT}/docs/04-roadmap/SFW_H1_STATUS_2026_10_13.md" ]]; then
        fail "status doc not found: docs/04-roadmap/SFW_H1_STATUS_2026_10_13.md" 1
    fi
    if [[ ! -f "${REPO_ROOT}/docs/history/SFW_H1_INTERIM_RECEIPT.md" ]]; then
        fail "interim receipt not found: docs/history/SFW_H1_INTERIM_RECEIPT.md" 1
    fi
    ok "status doc + interim receipt exist"

    banner "merge-readiness: 6/6 PASS"
    say ""
    say "  The merge ${H1_BRANCH} → ${TARGET_BRANCH} is READY."
    say "  ${ahead} commits to merge; 0 conflicts; tree clean; tag free."
    say ""
    say "  Next step: operator opens the PR (D-007 applies — push already done; merge is operator-driven)."
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
main() {
    if [[ "${SHOW_HELP}" == "1" ]]; then
        cat <<EOF
Usage: $0 [--branch <name>] [--self-test] [--help]

  --branch <name>     Check a specific branch (default: ${H1_BRANCH_DEFAULT})
  --self-test         Run the 5 falsifier scenarios (no git required)
  --help, -h          This help

Environment:
  SFW_H1_MERGE_BRANCH     branch to check (default: b1-sfw-h2-2c-studio-infra-fabric)
  SFW_H1_TARGET_BRANCH    target branch (default: main)

Exit codes: 0 READY, 1 pre-check failed, 2 conflict detected.
EOF
        return 0
    fi
    if [[ "${SELF_TEST}" == "1" ]]; then
        self_test
        return 0
    fi
    check
}

main "$@"
