#!/usr/bin/env bash
# scripts/verify-sandbox-namespace.sh
#
# S5 E5.4 hardening — namespace verification (operator-driven).
#
# Proves the recipe in docs/05-testing/SANDBOX_NAMESPACE.md actually
# works on the local kernel. We use `unshare` (user + pid namespaces
# work without sudo on modern Linux) to demonstrate the layered
# defence the operator can apply.
#
# This is NOT the same as applying the namespace to a running
# studio-app. The docs give the operator the production recipe
# (overlay + unshare + capability drop); this script proves that
# the components the docs reference are available and behave as
# documented.
#
# Exit codes:
#   0 = every check passed
#   1 = at least one check failed

set -euo pipefail

say() { printf '%s\n' "$*"; }
ok()   { say "  ✅ $*"; }
fail() { say "  ❌ $*"; exit 1; }

# 1. unshare is available and supports user namespaces
say "=== 1. unshare availability ==="
command -v unshare >/dev/null 2>&1 || fail "unshare not on PATH"
unshare --user --map-root-user --fork echo "user namespace ok" >/dev/null 2>&1 \
    || fail "unshare --user --map-root-user failed; check kernel.unprivileged_userns_clone"
ok "unshare + user namespace work without sudo"

# 2. pid namespace isolation works
say "=== 2. pid namespace isolation ==="
OUTSIDE_PID=$$
INNER_PID=$(unshare --user --map-root-user --pid --fork --mount-proc \
    sh -c 'echo $$' 2>/dev/null | tr -d '[:space:]')
[ -n "$INNER_PID" ] || fail "could not start a pid-namespace child"
[ "$INNER_PID" != "$OUTSIDE_PID" ] || fail "pid namespace did not isolate (got $INNER_PID == $OUTSIDE_PID)"
ok "pid namespace isolated (outside=$OUTSIDE_PID, inside=$INNER_PID)"

# 3. mount namespace + tmpfs works
say "=== 3. mount namespace + tmpfs ==="
unshare --user --map-root-user --mount --fork \
    sh -c 'mount -t tmpfs tmpfs /tmp && df -h /tmp | tail -1' >/dev/null 2>&1 \
    || fail "tmpfs mount inside user+mount namespace failed"
ok "tmpfs mount works inside a user+mount namespace"

# 4. seccomp filter actions are available
say "=== 4. seccomp availability ==="
ACTIONS=$(cat /proc/sys/kernel/seccomp/actions_avail 2>/dev/null || echo "")
[ -n "$ACTIONS" ] || fail "kernel seccomp disabled (read /proc/sys/kernel/seccomp/actions_avail)"
# The minimum set we need for the SANDBOX_SECCOMP.md profile:
for required in allow trap kill_process; do
    echo "$ACTIONS" | tr ' ' '\n' | grep -qx "$required" \
        || fail "seccomp action '$required' not available; got: $ACTIONS"
done
ok "seccomp actions available: $ACTIONS"

# 5. /proc/self/status has the fields SANDBOX_SECCOMP.md expects.
# Note: 'SeccompFilters' is only present when a filter is actually
# attached. We check the always-present fields here; the operator can
# verify the filter count after applying the profile with `cat
# /proc/<pid>/status | grep Seccomp`.
say "=== 5. /proc fields the seccomp profile references ==="
for f in Seccomp NoNewPrivs; do
    grep -q "^$f:" /proc/self/status || fail "/proc/self/status missing field: $f"
done
ok "Seccomp + NoNewPrivs readable; SeccompFilters appears after a filter is attached"

# 6. capabilities we plan to drop
say "=== 6. capability drop surface ==="
CAP_LAST_CAP=$(cat /proc/sys/kernel/cap_last_cap 2>/dev/null || echo "")
[ -n "$CAP_LAST_CAP" ] && [ "$CAP_LAST_CAP" -gt 0 ] \
    || fail "kernel reports cap_last_cap=$CAP_LAST_CAP (no caps defined?)"
ok "kernel exposes $CAP_LAST_CAP capabilities; the namespace drop set is real"

say
say "All namespace / seccomp / capabilities surface checks passed."
say "The operator can apply docs/05-testing/SANDBOX_NAMESPACE.md and"
say "docs/05-testing/SANDBOX_SECCOMP.md against a running studio-app."
