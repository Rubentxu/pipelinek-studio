# studio-cert — Independent certification harness

**Block:** B5.4 (S5 — Product readiness)
**Mandate:** `ROADMAP.md` §"S5: Certificación independiente de
artefacto instalado (sin src checkout): fresh clone, tests de
dist, MCP stdio v2, browser offline, runner isolated, metadata
SQLite restore, recovery."

## Purpose

The certification harness is an out-of-tree suite that
exercises the *installed* Studio distribution (not the
src). It downloads the published artifact, untars it into a
scratch directory, and runs the protocol below.

The harness lives outside the main Gradle graph on purpose:
a fresh certification run must NOT depend on the build
classes, the build cache, or any test code from the
distribution under test. The harness is the source of truth
for "is the artifact certified?".

## Protocol (CertProtocol)

The certification protocol is six ordered steps. A step's
failure short-circuits the run with a non-zero exit code and
a clear `FAIL <step>: <reason>` line on stderr.

1. **fresh_clone** — download the published
   `studio-cli-<version>.tar` (and the matching
   `manifest.json` + `sbom.json` from B5.1) into a scratch
   dir. Verify the SHA-256 of the tar against the
   `manifest.json` entry.
2. **dist_tests** — run `studio doctor` against the
   installed distribution. The doctor must exit 0
   (every check OK or WARN, no FAIL).
3. **mcp_v2** — start the server (`studio start`), run the
   MCP v2 stdio adapter (`studio mcp`), send a
   `studio.health` tool call, expect a 200 response with
   the documented body shape, then send SIGTERM.
4. **browser_offline** — open the SPA at
   `https://127.0.0.1:7778/` in a headless browser. The SPA
   must load (no console errors), and the IR Explorer
   placeholder must render. Network requests are blocked
   (offline); only the bundle's local assets are allowed.
5. **runner_isolated** — submit a run against a snapshot
   that uses `pipeline { ... }` with a single Step. The
   runner must spawn the SDK subprocess (no Kotlin eval
   inside the studio JVM), the run must terminate
   `SUCCEEDED`, and the Event Plane must contain the
   canonical events.
6. **metadata_sqlite_restore** — backup the `studio.db`
   via `studio backup --db studio.db --dest backup.db`,
   then `studio restore --from backup.db --to restored.db`,
   then run a smoke query that the schema is intact (e.g.
   list experiments). The SHA-256 of `restored.db` must
   match `backup.db`.

A step that fails reports the reason + the partial output.
A step that passes records its duration.

## Files

- `CertProtocol.kt` — the protocol ADT + the
  `verifyChecksums(scratchDir, manifest)` helper.
- `CertProtocolTest.kt` — pins the protocol step names +
  order, pins the verifyChecksums helper against a
  known-input vector, and pins the failure short-circuit
  contract.
- `README.md` — this file.

## Running the harness

```bash
# From a fresh clone, OUTSIDE the main Gradle graph:
cd studio-cert
./run.sh 1.0.0-rc.1
```

The harness exits 0 on PASS and non-zero on FAIL. The
`run.sh` is out of scope for v1.0.0-rc.1; B5.7 wires the
release-tag step that runs the harness.

## Out of scope for v1.0.0-rc.1

- The live network download step (fresh_clone) is
  operator-driven: the harness consumes a local
  `studio-cli-<version>.tar` and `manifest.json`. The
  download + signature-verify step is the B5.7 release
  script's job.
- `studio-cert/` is NOT in the main Gradle graph. A
  future B5.x pass can promote it to a standalone
  Gradle project with its own wrapper.
