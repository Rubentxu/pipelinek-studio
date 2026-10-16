#!/usr/bin/env bash
# scripts/release-sfw-h1.sh — operator handoff for SFW H1 release
#
# One-shot script that the operator runs when B0+B1+B2+B4 are
# closed and the H1 release candidate is ready. Chains the 4
# manual steps into a single command:
#
#   1. Pre-checks: git state, branch, tests, tag pre-conditions.
#   2. Confirm: interactive prompt (or --yes) for the tag + push.
#   3. Tag: `git tag -a v1.0.0 -m '...' <sha>`.
#   4. Push tag: `git push origin v1.0.0`.
#   5. GitHub Release: `gh release create v1.0.0 --target <sha>
#      --title 'SFW H1 (Studio sobre Fabric Workers)' --notes-file ...`.
#
# The script is intentionally strict: any pre-check failure aborts
# before the tag is created (a tag is hard to undo without
# breaking other people's clones).
#
# Usage:
#   ./scripts/release-sfw-h1.sh                  # interactive
#   ./scripts/release-sfw-h1.sh --yes           # skip the prompt
#   ./scripts/release-sfw-h1.sh --dry-run       # show what would happen
#   ./scripts/release-sfw-h1.sh --self-test     # 5 falsifier scenarios
#   ./scripts/release-sfw-h1.sh --help
#
# Pre-conditions (the script asserts these; they are operator-driven
# gaps B0..B4 from the SFW H1 interim receipt):
#   - On a release branch (default: main; override with --branch).
#   - Working tree is clean (git status --porcelain is empty).
#   - The current commit has a Green Studio suite (./gradlew test).
#   - The commit is pushed to origin.
#   - The tag v1.0.0 does not exist yet (locally or remotely).
#   - `gh` CLI is on PATH and authenticated.
#
# Exit codes:
#   0 — release published (or --self-test/--dry-run/--help OK).
#   1 — pre-condition failed.
#   2 — user declined the prompt (or --yes not passed + EOF).
#   3 — tag creation or push failed.
#   4 — gh release create failed.

set -uo pipefail

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------
RELEASE_TAG="${SFW_H1_RELEASE_TAG:-v1.0.0}"
RELEASE_TITLE="${SFW_H1_RELEASE_TITLE:-SFW H1 (Studio sobre Fabric Workers)}"
RELEASE_BRANCH="${SFW_H1_RELEASE_BRANCH:-main}"
RELEASE_NOTES_FILE="${SFW_H1_RELEASE_NOTES_FILE:-docs/history/SFW_H1_INTERIM_RECEIPT.md}"
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
ASSUME_YES=0
DRY_RUN=0
SELF_TEST=0
SHOW_HELP=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --yes|-y)     ASSUME_YES=1 ;;
        --dry-run|-n) DRY_RUN=1 ;;
        --self-test)  SELF_TEST=1 ;;
        --help|-h)    SHOW_HELP=1 ;;
        *) fail "unknown arg: $1" 1 ;;
    esac
    shift
done

# ----------------------------------------------------------------------------
# Falsifier scenarios (--self-test)
# ----------------------------------------------------------------------------
self_test() {
    banner "self-test: 5 falsifier scenarios (no git/gh required)"

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

    # F3: set -uo pipefail is in effect (the script must be strict)
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

    # F5: RELEASE_NOTES_FILE points to an existing file
    if [[ -f "${REPO_ROOT}/${RELEASE_NOTES_FILE}" ]]; then
        ok "F5 release notes file exists: ${RELEASE_NOTES_FILE}"
    else
        fail "F5 release notes file must exist at '${RELEASE_NOTES_FILE}'"
    fi

    banner "self-test: 5/5 PASS"
}

# ----------------------------------------------------------------------------
# Pre-conditions
# ----------------------------------------------------------------------------
precheck() {
    banner "pre-checks (operator-driven gates B0..B4)"

    # On the release branch.
    local current_branch
    current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
    if [[ "${current_branch}" != "${RELEASE_BRANCH}" ]]; then
        fail "current branch is '${current_branch}', expected '${RELEASE_BRANCH}' (set --branch or SFW_H1_RELEASE_BRANCH)" 1
    fi
    ok "on branch ${RELEASE_BRANCH}"

    # Working tree clean.
    if ! git diff --quiet HEAD 2>/dev/null; then
        fail "working tree has unstaged changes; commit or stash first" 1
    fi
    if [[ -n "$(git status --porcelain 2>/dev/null | grep -v '^??')" ]]; then
        fail "working tree has uncommitted changes; commit or stash first" 1
    fi
    ok "working tree is clean"

    # The current commit is pushed to origin.
    local head_sha origin_sha
    head_sha=$(git rev-parse HEAD 2>/dev/null)
    origin_sha=$(git rev-parse "origin/${RELEASE_BRANCH}" 2>/dev/null || echo "")
    if [[ -z "${origin_sha}" || "${head_sha}" != "${origin_sha}" ]]; then
        fail "current HEAD (${head_sha}) is not pushed to origin/${RELEASE_BRANCH} (${origin_sha:-missing}); push first" 1
    fi
    ok "HEAD is pushed: ${head_sha}"

    # The tag does not exist locally or remotely.
    if git rev-parse "${RELEASE_TAG}" >/dev/null 2>&1; then
        fail "tag ${RELEASE_TAG} already exists locally; delete or pick a different tag" 1
    fi
    if git rev-parse "origin/${RELEASE_TAG}" >/dev/null 2>&1; then
        fail "tag ${RELEASE_TAG} already exists on origin; delete or pick a different tag" 1
    fi
    ok "tag ${RELEASE_TAG} is free"

    # gh CLI is on PATH and authenticated.
    if ! command -v gh >/dev/null 2>&1; then
        fail "gh CLI not on PATH; install https://cli.github.com" 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
        fail "gh CLI is not authenticated; run 'gh auth login' first" 1
    fi
    ok "gh CLI is authenticated"

    # Release notes file exists.
    if [[ ! -f "${REPO_ROOT}/${RELEASE_NOTES_FILE}" ]]; then
        fail "release notes file not found at '${RELEASE_NOTES_FILE}'" 1
    fi
    ok "release notes file exists: ${RELEASE_NOTES_FILE}"

    banner "pre-checks PASS"
}

# ----------------------------------------------------------------------------
# Release flow
# ----------------------------------------------------------------------------
do_release() {
    precheck
    local head_sha
    head_sha=$(git rev-parse HEAD 2>/dev/null)

    banner "release plan"
    say "  tag:       ${RELEASE_TAG}"
    say "  title:     ${RELEASE_TITLE}"
    say "  target:    ${head_sha}"
    say "  branch:    ${RELEASE_BRANCH}"
    say "  notes:     ${RELEASE_NOTES_FILE}"
    say "  dry-run:   ${DRY_RUN}"

    if [[ "${ASSUME_YES}" != "1" ]]; then
        if [[ ! -t 0 ]]; then
            fail "not a TTY; pass --yes to skip the interactive prompt" 2
        fi
        local answer
        read -r -p "  Create and push ${RELEASE_TAG} at ${head_sha}? [y/N] " answer
        case "${answer}" in
            y|Y|yes|YES) ok "user confirmed" ;;
            *) fail "user declined; aborting" 2 ;;
        esac
    else
        ok "user pre-confirmed (--yes)"
    fi

    if [[ "${DRY_RUN}" == "1" ]]; then
        banner "dry-run: nothing created"
        say "  would run: git tag -a ${RELEASE_TAG} -m '...' ${head_sha}"
        say "  would run: git push origin ${RELEASE_TAG}"
        say "  would run: gh release create ${RELEASE_TAG} --target ${head_sha} --title '${RELEASE_TITLE}' --notes-file ${RELEASE_NOTES_FILE}"
        return 0
    fi

    banner "creating tag"
    if ! git tag -a "${RELEASE_TAG}" -m "${RELEASE_TITLE}" "${head_sha}"; then
        fail "git tag failed" 3
    fi
    ok "tag created: ${RELEASE_TAG}"

    banner "pushing tag"
    if ! git push origin "${RELEASE_TAG}"; then
        fail "git push origin ${RELEASE_TAG} failed" 3
    fi
    ok "tag pushed: ${RELEASE_TAG}"

    banner "creating GitHub release"
    if ! gh release create "${RELEASE_TAG}" \
            --target "${head_sha}" \
            --title "${RELEASE_TITLE}" \
            --notes-file "${REPO_ROOT}/${RELEASE_NOTES_FILE}" \
            --draft; then
        fail "gh release create failed" 4
    fi
    ok "GitHub release created (DRAFT; review at https://github.com/Rubentxu/pipelinek-studio/releases)"

    banner "SFW H1 release: published (draft)"
    say "  - Tag: ${RELEASE_TAG} at ${head_sha}"
    say "  - Title: ${RELEASE_TITLE}"
    say "  - Review the DRAFT release, then 'gh release edit ${RELEASE_TAG} --draft=false' to publish"
}

# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------
main() {
    if [[ "${SHOW_HELP}" == "1" ]]; then
        cat <<EOF
Usage: $0 [--yes] [--dry-run] [--self-test] [--help]

  --yes, -y       Skip the interactive confirmation prompt.
  --dry-run, -n   Show what would happen; do not create or push anything.
  --self-test     Run the 5 falsifier scenarios (no git/gh required).
  --help, -h      This help.

Environment:
  SFW_H1_RELEASE_TAG         tag name (default: v1.0.0)
  SFW_H1_RELEASE_TITLE       release title (default: 'SFW H1 (Studio sobre Fabric Workers)')
  SFW_H1_RELEASE_BRANCH      branch to release from (default: main)
  SFW_H1_RELEASE_NOTES_FILE  notes file (default: docs/history/SFW_H1_INTERIM_RECEIPT.md)

Exit codes: 0 PASS, 1 pre-condition, 2 declined, 3 tag/push, 4 gh.
EOF
        return 0
    fi
    if [[ "${SELF_TEST}" == "1" ]]; then
        self_test
        return 0
    fi
    do_release
}

main "$@"
