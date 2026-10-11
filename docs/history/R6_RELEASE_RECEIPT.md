# R6 Release Receipt — `v1.0.0-rc.8` (2026-10-11)

**Verdict:** `PASS` at code level. R5 EVO-C closed (13/13), R6
distribution/operations/contracts closed (3/3, with one documented
limitation). Forward-gates for `v1.0.0` stable: external evaluation
+ R4 B5.4 cert on a clean machine + operator approval (D-007 triple
gate; no agent can publish `v1.0.0`).

## Identifiers

| Field | Value |
|---|---|
| Tag (annotated) | `v1.0.0-rc.8` |
| Tag SHA | `dbc390131c5457ce3d26c122dab3359da875175f` |
| GitHub Release | https://github.com/Rubentxu/pipelinek-studio/releases/tag/v1.0.0-rc.8 |
| Pre-release | yes |
| Studio version | `1.0.0-rc.7` (the `gradle.properties:studio.version` value carried through R2..R6) |
| PipelineK SDK pin | `0.48.0-rc1` (`studio-infra-sdk/src/main/resources/sdk.lock.json`) |
| JDK | 21.0.8 (Temurin 21.0.8+9) |
| Kotlin | 2.4.20 |
| Gradle | 8.14.5 |
| Ktor | 3.0.1 |
| Flyway | 10.10.0 |
| SQLite (xerial) | 3.46.1.3 |
| MCP SDK | 1.0.4 |
| Node | 25.9.0 (extension host: 20.x for the app; 25 for the dev/test env) |

## Commits integrated since `v1.0.0-rc.7`

| SHA | Item |
|---|---|
| `91627b0` | R5 EVO-C.A + EVO-C.7 (ADR-013, SSE client + status bar, 15 vscode tests) |
| `05f2034` | R5 EVO-C.5 (use cases wire to bus, 4 new tests) |
| `940a089` | R5 EVO-C.1+.2+.3+.4+.5 (sidebar tree, open/cancel/CodeLens, 13 tree tests) |
| `8fee37f` | R5 EVO-C.6 + EVO-C.T (profile header + 6 adversarial tests) |
| `f7f222c` | R5 EVO-C.D + EVO-C.P (README/CHANGELOG + VSIX, smoke script) |
| `dbc3901` | R6 plan vivo + compat matrix rc.7 row |

## Tests (clean re-run, 2026-10-11)

| Module | Pass | Fail | Skip | Notes |
|---|---|---|---|---|
| `studio-domain` | 23 | 0 | 0 | |
| `studio-application` | 167 | 0 | 0 | +4 EVO-C.5 (TreeEventBusPublishingR5Test) |
| `studio-infra-storage` | 70 | 0 | 0 | |
| `studio-infra-sdk` | 94 | 0 | 0 | 1 not-measured (integration) |
| `studio-http` | 79 | 0 | 0 | +6 EVO-C.0 (TreeEventRoutesEvoC0Test) |
| `studio-cli` | 203 | 0 | 0 | |
| `studio-app` | 10 | 0 | 0 | |
| `studio-cert` | 8 | 0 | 0 | |
| **Kotlin TOTAL** | **654** | **0** | **1** | |
| `apps/vscode` | 45 | 0 | 0 | config 11 + sseClient 15 + treeView 13 + sdk 6 |
| `apps/mcp` | 31 | 0 | 0 | |
| `apps/web` | 64 | 0 | 0 | |
| **TOTAL** | **794** | **0** | **1** | |

Detekt: `0 findings` (gradle + script).
Configuration cache: 4 pre-existing warnings (jlinkRuntime, nodeBundleFetch,
nodeBundle, standaloneDist); non-blocking; tracked as an R6 follow-up.

## AAT/UAT covered in this release

- AAT-024: unified dispatch invariant (Step R2.9 — no `when(step.discovery == ...)` in production).
- AAT-021: canary plugin static initializer NEVER runs during catalog inspect.
- AAT-022/023: harness e2e for valid + incompatible plugins.
- AAT-002/003/004: Step admission lifecycle (DISCOVERED → COMPATIBLE → ADMITTED | REFUSED).
- AAT-API-008: Idempotency-Key → same run_id.
- AAT-OBS/UX, MCP-001..008, SEC-007..009, PLG-001..007, ABI-001..006,
  CPP-001..006, AN-001..008, DUR-001..006: covered by the 794-test suite
  (per the per-hito AAT declarations in the R0..R4 status audits).
- AAT-API-001..008, ABI-005..006, OBS-005, UX-001..004, SEC-005 (R5
  EVO-C surface): exercised by the vscode 45-test suite + the EVO-C.0/0.5
  Kotlin tests.

## Assets published

| File | Size | SHA-256 |
|---|---|---|
| `studio-cli-1.0.0-rc.7.tar` | 36 358 144 B (34.7 MB) | `073e49695f08d7ae48210d7e6ed334d9e8faccbd2d9c26f13424b9c4e282de66` |
| `manifest.json` | 10 069 B (9.8 KB) | `4e1910ef228c38e23dd718c0cafaeed4e70b3276f3170817fd28926bce5aaafa` |
| `sbom.json` | 10 438 B (10.2 KB) | `602cb7abfe01962076b58b03e27684ed00f30238d564076d83bca11cff78ea03` |
| `sbom.cdx.json` | 26 099 B (25.5 KB) | `fcdee3230d7dce9c76bbd20868d5151d9578eaa15bcedba9ff551b26449d1835` |
| `pipelinek-studio-1.0.0-rc.7.vsix` | 92 106 B (89.95 KB) | `1dce089b12996810bc2c2e4cde100decfad98133dbdc01e40413c60ea743b367` |

## §D step 8 — install smoke from the published asset

```
$ cd /tmp/rc8-smoke
$ curl -sL -o studio-cli.tar https://github.com/.../v1.0.0-rc.8/studio-cli-1.0.0-rc.7.tar
$ sha256sum studio-cli.tar | grep -q 073e4969... && echo "SHA256 OK"
SHA256 OK
$ tar -xf studio-cli.tar
$ studio-cli-1.0.0-rc.7/bin/studio-cli --help
ok    studio_cli_version       studio 1.0.0-rc.7 (kotlin 2.1.0, ktor 3.0.1)
ok    sdk_lockfile_present     sdk.lock.json found on classpath
ok    sdk_lockfile_parseable   resource reachable at jar:...
ok    db_path_writable         studio.db (parent writable, target writable or absent)
ok    data_dir_permissions     data dir + keystore + subdirs have owner-only POSIX permissions
```

VSIX smoke:
```
$ curl -sL -o pipelinek-studio.vsix https://github.com/.../v1.0.0-rc.8/pipelinek-studio-1.0.0-rc.7.vsix
$ sha256sum pipelinek-studio.vsix | grep -q 1dce089b... && echo "VSIX SHA256 OK"
VSIX SHA256 OK
$ node apps/vscode/scripts/extension-host-smoke.mjs pipelinek-studio.vsix
VSIX smoke OK: pipelinek-studio.vsix
  14 dist files
  9 commands declared ✓
  sidebar view container ✓
```

## Risks and residual debt

- **Build is not byte-reproducible.** Two `clean installDist distTar`
  runs in a row produce different ZIP jar mtimes. Documented in
  `COMPATIBILITY_MATRIX.md`. Tracked as an R6 follow-up (the fix is
  the `reproducible-builds` Gradle plugin or `SOURCE_DATE_EPOCH`
  plumbing on the `Jar` tasks).
- **R0.3.5 kill-9 smoke is BLOCKED** in this sandbox (no SDK
  subprocess alive). Documented in `R-VER-001`. Requires a clean
  machine with the SDK installed.
- **Configuration cache warnings** (4): `jlinkRuntime`,
  `nodeBundleFetch`, `nodeBundle`, `standaloneDist`. Pre-existing,
  non-blocking.
- **VS Code extension host smoke** is a proxy (parses package.json
  + asserts the file tree); a real `vsce` install + Extension Host
  run requires `xvfb` + a full VS Code install. Out-of-sandbox.

## Forward-gates for `v1.0.0` (D-007)

The `v1.0.0` stable tag is **not** published by this release. The
operator must satisfy all three gates:

1. **External evaluation** of `v1.0.0-rc.8` (or a later rc) by a
   non-internal party, on a clean machine, with the full UAT/AAT
   suite reinstalled.
2. **R4 B5.4 cert** on a clean machine with the SDK subprocess
   alive (the BLOCKED R0.3.5 smoke is the canonical cert).
3. **Operator explicit approval** for the `v1.0.0` tag + push
   (D-007).

---

**Recibo generado por:** Agente (R5 EVO-C + R6 closure, sesión
de 2026-10-11). **SHA del release:** `dbc3901`.
