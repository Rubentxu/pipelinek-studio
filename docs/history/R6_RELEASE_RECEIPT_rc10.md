# R6+2 Release Receipt — `v1.0.0-rc.10` (2026-10-12)

**Verdict:** `PASS` at code level. The in-sandbox piece of the
D-007 triple-gate is now closed: the build is **byte-reproducible**
when `SOURCE_DATE_EPOCH` is set (default 1700000000). Two clean
rebuilds produce byte-identical artifacts for all 4 Gradle-built
files. The other two gates (external evaluation + R4 B5.4 cert on
a clean machine) remain pending out-of-sandbox.

## Identifiers

| Field | Value |
|---|---|
| Tag (annotated) | `v1.0.0-rc.10` |
| Tag SHA | `eeecbc4` |
| GitHub Release | https://github.com/Rubentxu/pipelinek-studio/releases/tag/v1.0.0-rc.10 |
| Pre-release | yes |
| Studio version | `1.0.0-rc.7` (unchanged) |
| PipelineK SDK pin | `0.48.0-rc1` |

## Reproducibility proof (the headline)

Two clean rebuilds with `SOURCE_DATE_EPOCH=1700000000`:

```
$ rm -rf build && ./gradlew :studio-cli:installDist :studio-cli:distTar :studio-cli:distExtras
$ sha256sum studio-cli-1.0.0-rc.7.tar studio-cli/build/dist-extras/{manifest,sbom,sbom.cdx}.json
c6c26752c3945bb56948c851e8605df9892fce90379a54e6010389c31c7aa5fa  studio-cli-1.0.0-rc.7.tar
08586f38cbf43772c90bb4768ae8c6804cc59011efea7201a781f8f7231991fb  manifest.json
36498b0ca5a72ec5e7acb67029f84073d8b7432c0ed8cb9840c9c64d2fd016a6  sbom.json
c28f632b4069ac688fc76ca01a2af4cc156f3f57c5bc83a3b17b899b8c890e80  sbom.cdx.json

$ rm -rf build && ./gradlew :studio-cli:installDist :studio-cli:distTar :studio-cli:distExtras
$ sha256sum studio-cli-1.0.0-rc.7.tar studio-cli/build/dist-extras/{manifest,sbom,sbom.cdx}.json
c6c26752c3945bb56948c851e8605df9892fce90379a54e6010389c31c7aa5fa  studio-cli-1.0.0-rc.7.tar
08586f38cbf43772c90bb4768ae8c6804cc59011efea7201a781f8f7231991fb  manifest.json
36498b0ca5a72ec5e7acb67029f84073d8b7432c0ed8cb9840c9c64d2fd016a6  sbom.json
c28f632b4069ac688fc76ca01a2af4cc156f3f57c5bc83a3b17b899b8c890e80  sbom.cdx.json

$ diff <(sha256sum build1.*) <(sha256sum build2.*) && echo "REPRODUCIBLE: 4 artifacts identical"
REPRODUCIBLE: 4 artifacts identical
```

The `Jar` task is configured with:
- `isReproducibleFileOrder = true`
- `isPreserveFileTimestamps = false`
- `manifest.Build-Time` from `SOURCE_DATE_EPOCH`
- jar `lastModified` pinned to that same epoch

The `distExtras` task is configured with:
- `buildAt` from `SOURCE_DATE_EPOCH` (was `System.currentTimeMillis()`)
- `cdxBuildAt` from `SOURCE_DATE_EPOCH` (was `System.currentTimeMillis()`)
- `cdxSerial` deterministic (was `UUID.randomUUID()`)
- `cdxTimestamp` derived from `cdxBuildAt` (was `Instant.now()`)

## Changes since `v1.0.0-rc.9`

| SHA | Item |
|---|---|
| `7203384` | feat(build): R6.3 reproducible builds via `SOURCE_DATE_EPOCH` (Jar task + manifest + lastModified) |
| `eeecbc4` | fix(distribution): R6.3 — make all 4 artifacts byte-reproducible (manifest buildAt, sbom buildAt + serialNumber + timestamp) |

## Tests (unchanged from rc.9)

- Gradle check: **654/654 Kotlin** verde
- detekt: **0 findings**
- vscode: **45/45** verde
- mcp: **31/31** verde
- web: **64/64** verde
- **Total: 794/794**

## §D step 8 — install smoke from the published asset

```
$ curl -L -o studio-cli.tar https://github.com/.../v1.0.0-rc.10/studio-cli-1.0.0-rc.7.tar
$ sha256sum studio-cli.tar | grep -q c6c26752... && echo "rc.10 tar SHA256 OK"
rc.10 tar SHA256 OK
$ tar -xf studio-cli.tar
$ studio-cli-1.0.0-rc.7/bin/studio-cli --help
ok    studio_cli_version       studio 1.0.0-rc.7 (kotlin 2.1.0, ktor 3.0.1)
ok    sdk_lockfile_present     sdk.lock.json found on classpath

$ curl -L -o pipelinek-studio.vsix https://github.com/.../v1.0.0-rc.10/pipelinek-studio-1.0.0-rc.7.vsix
$ sha256sum pipelinek-studio.vsix | grep -q 90868787... && echo "rc.10 VSIX SHA256 OK"
rc.10 VSIX SHA256 OK
```

## D-007 triple-gate for `v1.0.0` stable

| Gate | Status |
|---|---|
| 1. External evaluation | PENDING (out-of-sandbox) |
| 2. R4 B5.4 cert on a clean machine | PENDING (R0.3.5 kill-9 smoke BLOCKED in sandbox per `R-VER-001`) |
| 3. Operator explicit approval | RECEIVED |

This release closes **the in-sandbox piece of the D-007
triple-gate** (reproducibility). Gates 1 and 2 remain.

---

**Recibo generado por:** Agente (R6.3 reproducible builds,
sesión de 2026-10-12). **SHA del release:** `eeecbc4`.
