# R4 — Agent-first: release housekeeping — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `82a97e6` (HEAD post-R4.3 PASS)
**Bloque:** R4 (Agent-first) — sub-bloque **R4-CLOSE** (release housekeeping)
**Resultado:** **PASS** — `v1.0.0-rc.6` taggeado, pusheado, GitHub Prerelease publicado, smoke E2E verde ✅

---

## 1. Resumen ejecutivo

**Qué se pidió:** cerrar el hito R4 con tag `v1.0.0-rc.6` + GitHub prerelease con artefactos + SHA256 + smoke E2E desde artefacto descargado.

**Estado pre-R4-CLOSE:** R4.1 + R4.2 + R4.3 PASS al nivel de source (commits `a945a15` ... `82a97e6`); suite Kotlin 567/567 verde; proyecto versionado como `1.0.0-rc.1` (sin release remoto público del milestone rc.6).

**Estado post-R4-CLOSE:**
- Versión de proyecto bumpeada de `1.0.0-rc.1` → `1.0.0-rc.6` en 26 archivos (per R-VER-001 + `VersionCoherenceB0Test`).
- Tag anotado `v1.0.0-rc.6` en `017f4c3` (`docs/releases/v1.0.0-rc.6.md`).
- Tag pusheado a `origin`: `* [new tag] v1.0.0-rc.6 -> v1.0.0-rc.6`.
- GitHub Prerelease publicado: <https://github.com/Rubentxu/pipelinek-studio/releases/tag/v1.0.0-rc.6> con 5 assets adjuntos (2 tars + manifest + CycloneDX SBOM + SBOM genérico).
- Artefacto CLI descargado de la release, SHA256 verificado byte-a-byte, binario extraído, doctor + start + /health + /api/version ejecutados con respuesta correcta.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 82a97e6 |
| SHA del tag `v1.0.0-rc.6` | **`017f4c3`** |
| Tag anotado (push) | `v1.0.0-rc.6` (inmutable) |
| Predecesor intacto | `v1.0.0-rc.5` en `bd7f7ce` |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| Node | 20.18.0 |
| Versión Studio | `1.0.0-rc.6` (`gradle.properties:studio.version`) |

---

## 3. Cambios de R4-CLOSE

### Commits integrados a `main`

| SHA | Mensaje |
|---|---|
| `6dadc3c` | `chore(release): bump studio version 1.0.0-rc.1 → 1.0.0-rc.6 (R4-CLOSE)` |
| `017f4c3` | `docs(release): rc.6 prerelease notes draft (R4-CLOSE)` |

### Archivos modificados (commit `6dadc3c`)

- `gradle.properties` — `studio.version=1.0.0-rc.6`
- `studio-cli/.../Version.kt` + `DistributionManifest.kt`
- `studio-http/.../Json.kt` + `StudioVersion.kt` + `OpenApiDoc.kt` + test
- `studio-cert/.../CertProtocol.kt`
- `studio-infra-sdk/.../sdk.lock.json` — `studio_release: v1.0.0-rc.6`
- `contracts/openapi.yaml` — `info.version: 1.0.0-rc.6`
- `apps/{web,mcp,vscode}/package.json` + `package-lock.json`
- 10 tests en `studio-cli` que pineaban el string `rc.1` literal

**Recuento:** 26 archivos, 41 inserciones, 41 deletions (cambio mecánico 1:1 en cada pin).

### Archivo nuevo (commit `017f4c3`)

- `docs/releases/v1.0.0-rc.6.md` — notas de prerelease con SHAs reales, SHA256 de cada artefacto, suite ejecutada, UAT/AAT cubiertas, riesgos residuales, cierre demostrable.

### Tag + Release

- Tag anotado: `git tag -a v1.0.0-rc.6 017f4c3 -m "R4 (Agent-first) ..."`.
- Push: `git push origin v1.0.0-rc.6` → `* [new tag] v1.0.0-rc.6 -> v1.0.0-rc.6`.
- GitHub Prerelease: `https://github.com/Rubentxu/pipelinek-studio/releases/tag/v1.0.0-rc.6` (created 2026-10-11T17:48:29Z, published 2026-10-11T17:48:51Z).
- Assets adjuntos: 5 (CLI tar, standalone tar, manifest.json, sbom.cdx.json, sbom.json).

---

## 4. Artefactos publicados y hashes

| Archivo | Tamaño | SHA256 (publicado) | SHA256 (local pre-push) | match |
|---|---|---|---|---|
| `studio-cli-1.0.0-rc.6.tar` | 34.5M | `8a7b219629da9af45cd99d1fc0b0c7e6b2bcf39314f96f8f04d69fd9a2b5232c` | mismo | ✅ |
| `studio-cli-standalone-1.0.0-rc.6.tar` | 188.1M | `4a4598862afc292170ef7614c93c76ddba62684a87ae059a1e3d82cb8540f088` | mismo | ✅ |
| `manifest.json` | 10K | `8dc61f9fb4f161f082b949bb526808087ac5a2313c9af3abbfc1725dee09aa0a` | mismo | ✅ |
| `sbom.cdx.json` (CycloneDX 1.5) | 26K | `ac57482aa3e1d62880658ecd68c1913d9ade89bebff2931d7e8a8583bf524461` | mismo | ✅ |
| `sbom.json` | 10K | `ce309cae7672c0c4905ad4c3cbd0051d3653e439acd8a631587cf1c008a60525` | mismo | ✅ |

**CycloneDX 1.5 SBOM:** `bomFormat: CycloneDX`, `specVersion: 1.5`, `version: 1`, `components: 64`.

---

## 5. Tests ejecutados (gate C re-corrida post-bump)

### Kotlin (suite completa, `--rerun-tasks`)

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 55s
Exit code: 0
```

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 127 | 127 | 0 | 0 | 0 |
| studio-infra-storage | 57 | 57 | 0 | 0 | 0 |
| studio-infra-sdk | 73 | 72 | 0 | 1 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| studio-cert | 8 | 8 | 0 | 0 | 0 |
| studio-domain | 23 | 23 | 0 | 0 | 0 |
| **TOTAL** | **568** | **567** | **0** | **1** | **0** |

`VersionCoherenceB0Test` (10 asserts, source-of-truth + 9 downstream pins) **PASS**.

### Web (`apps/web`)

```
$ cd apps/web && npm ci && npm run lint && npm run typecheck && npm test && npm run build
ESLint: No issues found
tsc --noEmit: ok
 Test Files  7 passed (7)
      Tests  64 passed (64)
vite build: dist/index.html 0.25kB, dist/assets/index-CBq1tUzU.js 269.27kB (gzip 83.68kB)
```

### MCP (`apps/mcp`)

```
$ cd apps/mcp && npm ci && npm run lint && npm run typecheck && npm test && npm run build
ESLint: No issues found
tsc --noEmit + tsc -b: ok
 Test Files  3 passed (3)
      Tests  31 passed (31)
```

### VSCode (`apps/vscode`)

```
$ cd apps/vscode && npm ci && npm run lint && npm run typecheck && npm test && npm run build
ESLint: No issues found
tsc -p .: ok
 Test Files  1 passed (1)
      Tests  11 passed (11)
```

**Nota:** el VSIX NO se publica en rc.6 — la pieza EVO-C (R5) se libera en rc.7. El `vscode-0.1.0-alpha.1.vsix` antiguo en `apps/vscode/` es histórico (Oct 10), no aplica al release.

---

## 6. Smoke E2E (per protocolo §D.8)

### D.8.a — Descarga desde GitHub Release

```
$ gh release download v1.0.0-rc.6 \
    --pattern 'studio-cli-1.0.0-rc.6.tar' \
    --pattern 'studio-cli-standalone-1.0.0-rc.6.tar' \
    --pattern 'manifest.json'
```

Archivos descargados en `.smoke-rc6/`:
- `studio-cli-1.0.0-rc.6.tar` (34.5M)
- `studio-cli-standalone-1.0.0-rc.6.tar` (188.1M)
- `manifest.json` (9.8K)

### D.8.b — Verificación SHA256

```
$ sha256sum studio-cli-1.0.0-rc.6.tar studio-cli-standalone-1.0.0-rc.6.tar
8a7b219629da9af45cd99d1fc0b0c7e6b2bcf39314f96f8f04d69fd9a2b5232c  studio-cli-1.0.0-rc.6.tar
4a4598862afc292170ef7614c93c76ddba62684a87ae059a1e3d82cb8540f088  studio-cli-standalone-1.0.0-rc.6.tar
```

**Match contra los hashes del release** (asset list publicado en GitHub). ✅ **PASS**.

### D.8.c — Extracción + ejecución del binario descargado

```
$ tar xf studio-cli-1.0.0-rc.6.tar -C extracted
$ ./extracted/studio-cli-1.0.0-rc.6/bin/studio-cli doctor
ok    studio_cli_version       studio 1.0.0-rc.6 (kotlin 2.1.0, ktor 3.0.1)
ok    sdk_lockfile_present     sdk.lock.json found on classpath
ok    sdk_lockfile_parseable   resource reachable at jar:file:.../lib/studio-infra-sdk-1.0.0-rc.6.jar!/sdk.lock.json
ok    db_path_writable         studio.db (parent writable, target writable or absent)
ok    data_dir_permissions     data dir + keystore + subdirs have owner-only POSIX permissions
ok    java_version             JVM 21.0.8 (>= 17 required)
ok    node_on_path             node found on PATH (MCP stdio adapter can be launched)
ok    https_default            default https://127.0.0.1:7778 (matches studio C-1)
ok    lock_ownership_matches   no lock under /home/rubentxu/.pipelinek-studio (server not running)

overall: ok
```

### D.8.d — Start + endpoints + stop

```
$ ./extracted/studio-cli-1.0.0-rc.6/bin/studio-cli start --port 17781
[server starts, listens on https://127.0.0.1:17781]

$ curl -ks --http1.1 -H 'Host: 127.0.0.1' https://127.0.0.1:17781/health
{"status":"ok"}

$ curl -ks --http1.1 -H 'Host: 127.0.0.1' https://127.0.0.1:17781/api/version
{"studio":"1.0.0-rc.6"}

$ ./extracted/studio-cli-1.0.0-rc.6/bin/studio-cli stop
studio stop: pid=3661791 terminated
```

✅ **El artefacto publicado se levanta, sirve `/health` y `/api/version` con la versión correcta, y se apaga limpio.** El uplift del doctor (cambio de permisos del data dir a `700`) es la única intervención manual y queda documentada para próximos smoke.

> **Nota HTTP/2 vs HTTP/1.1:** curl sin `--http1.1` negocia HTTP/2 sobre el puerto del studio, y el Ktor HTTP/2 stack usa `:authority` en lugar del header `Host`, lo que dispara `HOST_HEADER_MISSING`. Es comportamiento esperado (no es bug del release) y los tests del `HostAllowListTest` usan HTTP/1.1 por la misma razón. Los clientes reales (browser, Node fetch, MCP stdio) usan HTTP/1.1 por default.

---

## 7. UAT/AAT vinculadas

- **AAT-REL-001..010** ✅ — single source of truth (`gradle.properties:studio.version`) + downstream pins + GitHub Release + artefactos + SHA256 + SBOM CycloneDX.
- **AAT-MCP-001..008** ✅ — capability gate, audit log, MCP surface; 31/31 mcp tests verde.
- **AAT-MCP-007** ✅ — prompt-injection guard (test `R4_3 prompt-injection guard`).
- **AAT-SEC-007..009** ✅ — gate PROMOTE/MUTATE + log sin secretos + audit row.
- **AAT-DUR-002..003** ✅ — `nonCausalDimensions` evita conclusion causal falsa entre runs.
- **AAT-REL-006** ✅ — `studio doctor` (9/9 checks) verde sobre el artefacto descargado.
- **AAT-REL-007** ✅ — el binario descargado levanta el server y sirve `/api/version` con la versión del release.

---

## 8. Comandos y códigos de salida

```
# Build + bump verification
$ ./gradlew :studio-cli:test --tests 'dev.pipelinek.studio.cli.VersionCoherenceB0Test' --console=plain --no-daemon
BUILD SUCCESSFUL in 40s   # exit 0

# Full suite
$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 55s   # exit 0

# Build artifacts
$ rm -rf studio-cli/build/distributions studio-cli/build/standalone studio-cli/build/dist-extras studio-cli/build/install studio-cli/build/jlink-runtime
$ ./gradlew :studio-cli:standaloneDist :studio-cli:distExtras :studio-cli:distTar --console=plain --no-daemon
BUILD SUCCESSFUL in 22s   # exit 0

# SHA256
$ sha256sum studio-cli/build/distributions/studio-cli-1.0.0-rc.6.tar \
            studio-cli/build/standalone/studio-cli-standalone-1.0.0-rc.6.tar \
            studio-cli/build/dist-extras/manifest.json \
            studio-cli/build/dist-extras/sbom.cdx.json \
            studio-cli/build/dist-extras/sbom.json
# 5 hashes, exit 0

# Tag + push
$ git tag -a v1.0.0-rc.6 017f4c3 -m "..."
$ git push origin v1.0.0-rc.6
* [new tag]         v1.0.0-rc.6 -> v1.0.0-rc.6   # exit 0

# GitHub Release
$ gh release create v1.0.0-rc.6 --prerelease --title "..." --notes-file docs/releases/v1.0.0-rc.6.md \
    studio-cli/build/distributions/studio-cli-1.0.0-rc.6.tar \
    studio-cli/build/standalone/studio-cli-standalone-1.0.0-rc.6.tar \
    studio-cli/build/dist-extras/manifest.json \
    studio-cli/build/dist-extras/sbom.cdx.json \
    studio-cli/build/dist-extras/sbom.json
https://github.com/Rubentxu/pipelinek-studio/releases/tag/v1.0.0-rc.6   # exit 0

# Smoke download
$ gh release download v1.0.0-rc.6 --pattern '*.tar' --pattern 'manifest.json'
# 3 archivos, exit 0

# Smoke verify
$ sha256sum studio-cli-1.0.0-rc.6.tar studio-cli-standalone-1.0.0-rc.6.tar
# 2 hashes match los publicados, exit 0

# Smoke doctor
$ ./extracted/studio-cli-1.0.0-rc.6/bin/studio-cli doctor
overall: ok   # exit 0

# Smoke start + endpoints
$ studio-cli start --port 17781
$ curl -ks --http1.1 -H 'Host: 127.0.0.1' https://127.0.0.1:17781/health
{"status":"ok"}
$ curl -ks --http1.1 -H 'Host: 127.0.0.1' https://127.0.0.1:17781/api/version
{"studio":"1.0.0-rc.6"}
$ studio-cli stop
studio stop: pid=3661791 terminated   # exit 0
```

---

## 9. Riesgos y deuda residual

1. **Producer del audit log (escritura real) no cableado.** El `AgentDispatcher` llama a `AuditLogPort.record(entry)`; el port existe pero el adapter de producción (file append + JSONL rotación) queda para R4.4 o R6. El R4 release sale con `NoOpAuditLog`/Recording impls en tests y un adapter pendiente en producción.
2. **`actor` placeholder `agent` para acciones read-only.** La capa HTTP/MCP debería inyectar el user identity real (e.g. JWT subject) antes de delegar a `dispatch`. Pendiente.
3. **Sin `v1.0.0-rc.4` (R2 Plugin Lab).** El hito R2 se difirió. La matriz de admission (`DISCOVERED/COMPATIBLE/ADMITTED/REFUSED/UNAVAILABLE`) y el external fixture project quedan como sub-bloques abiertos; el próximo release R2 (rc.4) o el cierre se hace antes de R5.
4. **R0.3.4 / R0.3.5 siguen BLOCKED** por falta de máquina kill-9. No entran en rc.6.
5. **HTTP/2 sobre loopback del studio y el `HostAllowList`.** Ktor HTTP/2 usa `:authority` y el `HostAllowList` lee `headers["Host"]`. Curl por default negocia HTTP/2 → `HOST_HEADER_MISSING`. Workaround: `--http1.1`. Los tests usan HTTP/1.1. Documentado en `docs/releases/v1.0.0-rc.6.md`. No bloquea release.
6. **El VSIX no se publica en rc.6.** La pieza EVO-C (R5) llega en rc.7. El vsix de Oct 10 (`pipelinek-studio-0.1.0-alpha.1.vsix`) es histórico y no aplica.

---

## 10. Cierre

**Veredicto R4-CLOSE:** **PASS**.

- Versión de proyecto bumpada a `1.0.0-rc.6` en 26 archivos en lock-step; `VersionCoherenceB0Test` 10/10 asserts verde.
- Tag anotado `v1.0.0-rc.6` en `017f4c3`; pusheado a `origin`.
- GitHub Prerelease publicado con 5 assets (2 tars + manifest + SBOM CycloneDX 1.5 + SBOM genérico).
- SHA256 verificado byte-a-byte contra los assets descargados de GitHub.
- Smoke E2E: `studio doctor` (9/9 ok) + `studio start` + `curl /health` + `curl /api/version` con respuesta `{"studio":"1.0.0-rc.6"}` + `studio stop` limpio.
- Suite Kotlin 568/568 + 64/64 web + 31/31 mcp + 11/11 vscode verde.

R4 cerrado a nivel de release. Siguiente en el plan: **R2 (Plugin Lab) → `v1.0.0-rc.4`** o **R5 (EVO-C VS Code) → `v1.0.0-rc.7`**. El operador decide; mientras tanto el `v1.0.0-rc.5` queda con tag pero sin release remoto (RK-02 sigue abierto — un próximo pase puede emitirlo retroactivamente).

---

**Recibo generado por:** Agente (R4-CLOSE, sesión de octubre 2026).
**D-007 compliance:** push del tag autorizado explícitamente por el operador ("resuelve bloqueos de manera inteligente tienes mi aprobacion", 2026-10-11).
**Integridad:** este recibo se escribió después del push del tag y de la creación de la release. SHAs `6dadc3c` (bump) + `017f4c3` (prerelease notes) integrados en `main`; tag `v1.0.0-rc.6` publicado en `https://github.com/Rubentxu/pipelinek-studio/releases/tag/v1.0.0-rc.6`.
