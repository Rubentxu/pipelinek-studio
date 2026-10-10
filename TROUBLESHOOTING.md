# Troubleshooting — PipelineK Studio

This document is the operator's first stop when something is wrong. It
is intentionally procedural: **symptom → quick check → next step**.
Each section names the feature, the diagnostic, and the recovery
command.

## 0. The first 60 seconds

When in doubt, run the doctor probe:

```bash
studio doctor
# or, hitting a remote server:
curl -fsS http://127.0.0.1:7070/health
```

A green `200 OK` from `/health` proves the JVM is up and the SQLite
DB is reachable. Anything else, jump to the matching section below.

## 1. "Address already in use" on `studio start`

**Symptom:** `BindException: Address already in use: 7070`.

**Quick check:**

```bash
ss -tlnp 'sport = :7070'    # who owns the port
lsof -i :7070 2>/dev/null   # macOS / BSD
```

**Next step:** either kill the offending process or pick a different
port:

```bash
studio start --port 7071
```

If you ran a previous `studio start` and it crashed, the JVM may
still own the port until the kernel reaps it. Wait 30 seconds and
retry, or use a fresh port.

## 2. "database is locked"

**Symptom:** SQLite error `SQLITE_BUSY` or `database is locked`.

**Cause:** two writers tried the DB at once. Studio enforces a
single-writer rule; the second writer is rejected with HTTP 409.

**Quick check:**

```bash
# How many writers does the OS see on the file?
fuser dist/studio.db 2>/dev/null
```

**Next step:** identify the writer with `studio doctor --verbose`,
stop the duplicate, and retry. If the lock is held by a zombie
process that no longer responds, take a fresh `studio.db.bak` from
the last successful backup (`scripts/` doesn't ship one yet — use
SQLite Online Backup via the `StudioBackup` Kotlin API).

## 3. "rate limit exceeded" (HTTP 429)

**Symptom:** clients get `429 Too Many Requests` for short bursts.

**Cause:** the per-IP token bucket is set to 60 requests / 60
seconds by default.

**Quick check:**

```bash
grep -i 'studio.rate' /var/log/studio.log | tail -20
```

**Next step:** for tests or local dev, disable the limiter with:

```bash
STUDIO_RATE_LIMIT_DISABLED=1 studio start
```

In production, raise the bucket from the composition root or front
the API with a reverse proxy that throttles at the edge.

## 4. "request body too large" (HTTP 413)

**Symptom:** large snapshots are rejected with 413.

**Cause:** the 1 MiB request body limit is exceeded.

**Next step:** split the snapshot into smaller pieces, or override
the limit from the composition root. The default is intentionally
low to prevent runaway memory; do not raise it without a code review.

## 5. MCP stdio: "no MCP server" or "MCP server not found"

**Symptom:** `studio mcp` prints build guidance and exits.

**Cause:** `apps/mcp/dist/server.js` was not built.

**Quick check:**

```bash
ls -l apps/mcp/dist/server.js
```

**Next step:** build the MCP adapter:

```bash
cd apps/mcp && npm install && npm run build
```

`studio mcp` then picks it up automatically via the `findMcpServer`
heuristic, or you can force a path:

```bash
PIPELINEK_STUDIO_MCP=$(pwd)/apps/mcp/dist/server.js studio mcp
```

## 6. SPA: "loading editor forever"

**Symptom:** Monaco never appears in the browser.

**Cause 1:** the SPA is offline and Monaco's CDN is unreachable.
**Cause 2:** the `baseUrl` setting in the VS Code extension points
to a wrong backend.

**Quick check (browser dev tools → Network):**

- Look for a `monaco-editor` loader that hangs.
- Look for 404 on `/sdk.ts` (the generated SDK).

**Next step:**

- For offline use, vendor Monaco locally (S5 polish — not yet shipped).
- Verify the SPA `baseUrl` matches the running backend:
  `localStorage.setItem('studio.baseUrl', 'http://127.0.0.1:7070')`
  in the browser console and reload.

## 7. Run stuck in `RUNNING` after `kill -9`

**Symptom:** a run never reaches a terminal state; the UI shows
`RUNNING` forever.

**Cause:** the runner process was killed before it could write the
terminal event. Studio deliberately does NOT auto-retry a non-terminal
run on restart (D-007 + R-07).

**Next step:**

1. Inspect the run events with `studio inspect <run-id>` to confirm
   the last event is non-terminal.
2. Mark the run as `CANCELLED` explicitly via the API:
   `POST /runs/<id>/cancel` (idempotent).
3. If the run holds a per-run DB, free it with
   `rm $(studio run-db-path <id>)` after the cancellation is
   persisted.

## 8. DB schema drift ("migration failed")

**Symptom:** startup fails with `FlywayException: Validate failed:
Migration checksum mismatch`.

**Cause:** the `studio.db` was written by a newer version than the
one starting.

**Next step:**

- Either upgrade Studio to the version that wrote the DB.
- Or roll back the DB from the last known-good backup (the SQLite
  Online Backup API is the supported mechanism; use
  `StudioBackup.backupTo`).

Never hand-edit `studio.db` — Studio will detect the schema version
mismatch on next start and refuse to open it.

## 9. Suspiciously-fast runs with empty output

**Symptom:** a step that should take seconds finishes in < 10ms with
zero stdout/stderr.

**Cause:** the step was either not executed at all (D-005: refuse
silent execution paths) or the Output Plane seal was bypassed.

**Next step:** inspect the run events. If `outcome.type` is `OK`
but the output is empty and the step was a no-op, treat it as a
potential bypass — file a security report per `SECURITY.md` /
`docs/05-testing/SECURITY_POSTURE.md`.

## 10. "Out of memory" on a long run

**Symptom:** the JVM dies with `OutOfMemoryError` during a long
pipeline.

**Quick check:** the run's Output Plane size. Studio streams the
output with backpressure; an unbounded loop in a Step is the usual
culprit.

**Next step:**

- Cap output with the `--output-bytes-limit` CLI flag (or
  `STUDIO_OUTPUT_BYTES_LIMIT` env var) — default is 100 MiB.
- Set JVM heap explicitly when launching:
  `JAVA_OPTS="-Xmx1g" studio start`.
- Profile the Step: most OOMs are downstream of an infinite loop in
  user code, not Studio itself.

## Where to file a real bug

After ruling out the cases above, file an issue with:

- `studio doctor` output (or `/health` JSON)
- The last 200 lines of the JVM log
- The exact command line that reproduces
- The `studio` version (`studio --version`)

Sprint-grade issues belong in the issue tracker; security-class
issues go to the channel in `MAINTAINERS.md` (private disclosure).

## 7. `studio start` / `stop` / `status` (B5.0)

The B5.0 release introduced the lock file at
`--data-dir/.studio.lock` + the `studio stop` + `studio status`
subcommands.

### Symptom: "a live studio server is already running (pid=N)"

**Cause:** a previous `studio start` crashed without clearing the
lock, or a different studio process is bound to the same data dir.

**Quick check:**

```bash
studio status --data-dir ~/.pipelinek-studio
```

**Next step:**

- If the listed PID is your intended studio, run `studio stop`.
- If the PID is something else (a leftover from a crash), `rm
  ~/.pipelinek-studio/.studio.lock` and `studio start` again.

### Symptom: `studio stop` says "no lock under <dir>"

**Cause:** the data dir has no lock. The server is either not
running, or was started with a different `--data-dir`.

**Quick check:**

```bash
studio status --data-dir /the/data/dir/you/used
```

**Next step:** pass the correct `--data-dir` to `studio stop`.

### Symptom: `studio status` says running but the server does not answer

**Cause:** the PID in the lock is alive but no longer bound to
the studio port (the process was forked or replaced).

**Quick check:**

```bash
studio status --json | jq .pid
lsof -iTCP:7778 -sTCP:LISTEN -P -n
```

**Next step:** kill the listed PID (`kill <pid>`) and `rm
<data-dir>/.studio.lock`, then `studio start` again.
