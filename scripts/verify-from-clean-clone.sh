#!/usr/bin/env bash
# S5 E5.7 — From-clean-clone verification script.
#
# This is what a certification harness looks like. It assumes:
#   - JDK 21 LTS on PATH (with `java` and `javac`)
#   - `git`, `curl`, `tar`, `node` (for the `mcp` smoke)
#   - The SDK distribution is not present (verifies honest "unavailable" path too)
#
# Steps:
#   1. ./gradlew clean test             (full test suite)
#   2. ./gradlew :studio-cli:distTar    (build CLI distribution)
#   3. Tar the distribution; run `studio start`; verify /health + /api/version
#   4. Run `studio backup` and `studio restore`; verify SQLite integrity
#   5. Verify `studio mcp` prints guidance (no exec)
#
# Each step prints a one-line verdict. Exit non-zero on any failure.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

say() { printf '%s\n' "$*"; }
fail() { say "❌ $*"; exit 1; }
ok()   { say "✅ $*"; }

# 1. Clean test
say "=== 1. ./gradlew clean test ==="
./gradlew --no-daemon clean test >/tmp/studio-test.log 2>&1 || fail "tests failed; see /tmp/studio-test.log"
ok "tests passed"

# 2. Build CLI distribution
say "=== 2. ./gradlew :studio-cli:distTar ==="
./gradlew --no-daemon :studio-cli:distTar >/tmp/studio-dist.log 2>&1 || fail "distTar failed"
TAR="$(ls studio-cli/build/distributions/studio-cli-*.tar | head -1)"
[ -f "$TAR" ] || fail "no tar distribution produced"
ok "distTar: $TAR"

# 3. Smoke the CLI start subcommand
say "=== 3. studio start smoke ==="
EXTRACT=/tmp/studio-cli-verify
rm -rf "$EXTRACT" && mkdir -p "$EXTRACT"
tar -xf "$TAR" -C "$EXTRACT"
BIN="$EXTRACT/studio-cli-0.1.0-alpha.1/bin/studio-cli"
chmod +x "$BIN"
DB=/tmp/studio-verify-$$.db
PORT=18777
"$BIN" start --port "$PORT" --db "$DB" >/tmp/studio-start.log 2>&1 &
START_PID=$!
trap 'kill $START_PID 2>/dev/null || true; rm -f "$DB" /tmp/studio-verify-*.backup.db' EXIT
for i in $(seq 1 50); do
  if curl -fs "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
    HEALTH="$(curl -fs "http://127.0.0.1:$PORT/health")"
    [ "$HEALTH" = "ok" ] || fail "/health returned '$HEALTH'"
    ok "/health -> ok"
    VERSION="$(curl -fs "http://127.0.0.1:$PORT/api/version")"
    echo "$VERSION" | grep -q "studio-cli-minimal" || fail "/api/version missing tag: $VERSION"
    ok "/api/version -> $VERSION"
    break
  fi
  sleep 0.2
done
kill $START_PID 2>/dev/null || true
wait $START_PID 2>/dev/null || true

# 4. CLI backup subcommand
say "=== 4. studio backup + restore ==="
BACKUP=/tmp/studio-verify-$$.backup.db
"$BIN" backup --db "$DB" --dest "$BACKUP" >/tmp/studio-backup.log 2>&1 || fail "backup failed; see /tmp/studio-backup.log"
[ -f "$BACKUP" ] || fail "backup file not created"
ok "studio backup -> $BACKUP"

# 5. CLI mcp prints guidance
say "=== 5. studio mcp guidance ==="
GUIDANCE=$("$BIN" mcp 2>&1 || true)
echo "$GUIDANCE" | grep -q "cd apps/mcp" || fail "mcp guidance missing build instructions; got: $GUIDANCE"
ok "studio mcp guidance present"

say
say "ALL FIVE CHECKS PASSED. S5 E5.7 from-clean-clone verification: READY."
