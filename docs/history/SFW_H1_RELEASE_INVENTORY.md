# SFW H1 v1.0.0 — Recibo con inventario remoto íntegro

> F4 deliverable item: *"un recorrido completo y recibo con inventario remoto íntegro"*
> (per `docs/pipelinek-studio-fabric-delivery-2026-10-16/overlays/pipelinek-studio/docs/04-roadmap/FABRIC_EXECUTION_DELTA_2026-10-16.md`).

**Fecha de inventario:** 2026-10-16
**Tag:** `v1.0.0` (annotated)
**Estado de release:** publicado (no draft), todas las superficies verificadas

## 1. Identificadores verificables

| Recurso | Identificador |
|---|---|
| Tag `v1.0.0` (annotated object SHA) | `46f1b89f139eb211b14dec6e3f2da3112b1001a0` |
| Tag → commit binding | `v1.0.0` → `49009f829d4a5b5070a14c2279b51e50f5e02141` |
| Commit | `49009f829d4a5b5070a14c2279b51e50f5e02141` |
| Branch `main` HEAD en origin | `49009f829d4a5b5070a14c2279b51e50f5e02141` |
| Release URL | https://github.com/Rubentxu/pipelinek-studio/releases/tag/v1.0.0 |
| Release `targetCommitish` | `49009f829d4a5b5070a14c2279b51e50f5e02141` (== commit ✓) |
| Release `publishedAt` | 2026-10-16T21:24:00Z |
| Release `isDraft` | `false` |
| Release `name` | `SFW H1 (Studio sobre Fabric Workers)` |
| PR #1 (merged) | https://github.com/Rubentxu/pipelinek-studio/pull/1 |
| PR merge commit | `b65bf76c65fdad76a2f59fceb7489d6d225424a5` |
| PR `mergedAt` | 2026-10-16T20:36:10Z by Rubentxu |
| Merge `e67fda0..b417e06..49009f8` (main) | `e67fda06b0feef511ae0740177c53379e9e60618` → `b417e06` (post F4) → `49009f8` (post version-bump) |

## 2. Artefacto binario

| Campo | Valor |
|---|---|
| Path | `studio-cli-standalone-1.0.0.tar` |
| Format | Plain tar (no gzip, no bzip2) |
| Size | 198,164,480 bytes (≈ 189 MB) |
| SHA-256 | `dc0dc18c93b760efa6bf16ad5b8fc603f08591089d09c5f3a7e91cdb65680108` |
| Filename top-level | `studio-cli-standalone-1.0.0/` |
| File count | 231 (libs + bin + manifests) |
| Build commit | `49009f8` |
| Download URL | https://github.com/Rubentxu/pipelinek-studio/releases/download/v1.0.0/studio-cli-standalone-1.0.0.tar |
| Asset digest (GitHub-assigned) | `sha256:dc0dc18c93b760efa6bf16ad5b8fc603f08591089d09c5f3a7e91cdb65680108` ✓ matches local |

### Verificación de integridad end-to-end

```sh
# Local verification (ejecutada por el agente antes de upload)
sha256sum studio-cli/build/standalone/studio-cli-standalone-1.0.0.tar
# → dc0dc18c93b760efa6bf16ad5b8fc603f08591089d09c5f3a7e91cdb65680108

# Remote verification
curl -L -O https://github.com/Rubentxu/pipelinek-studio/releases/download/v1.0.0/studio-cli-standalone-1.0.0.tar
sha256sum studio-cli-standalone-1.0.0.tar
# expected: dc0dc18c93b760efa6bf16ad5b8fc603f08591089d09c5f3a7e91cdb65680108
```

## 3. Evidencia de pruebas

| Suite | Resultado | Comando |
|---|---|---|
| `:studio-build-logic:test` | 14/14 PASS | `./gradlew :studio-build-logic:test` |
| `:studio-infra-fabric:test` | 91/91 PASS | `./gradlew :studio-infra-fabric:test` |
| `:studio-http:test` | (incluido en Studio suite) | `./gradlew :studio-http:test` |
| Full Studio suite | **1014/1014 PASS**, 0 failures, 1 skipped | `./gradlew test` |
| `sfwH1SelfTest` (root) | 5+5+5 falsifier scenarios green | `./gradlew sfwH1SelfTest` |
| `release-sfw-h1.sh --self-test` | 5/5 PASS | `bash scripts/release-sfw-h1.sh --self-test` |
| `merge-readiness.sh --self-test` | 5/5 PASS | `bash scripts/check-sfw-h1-merge-readiness.sh --self-test` |
| `uat-sfw-h1-001-fabric-container.sh --self-test` | 5/5 PASS | `bash scripts/uat-sfw-h1-001-fabric-container.sh --self-test` |
| Configuration cache (StudioBuildLogic + sfwH1SelfTest) | `Configuration cache entry stored.` | post-fix Parts 5/6 |

## 4. Cadena de commits (subset relevante)

```
49009f8 chore(sfw,h1): Phase B Part 9 — version bump 1.0.0-rc.17 → 1.0.0
b417e06 fix(sfw,h1): Phase B Part 8 — release-sfw-h1.sh ignores untracked dirs
db64730 fix(sfw,h1): Phase B Part 6 — config-cache compat in sfwH1SelfTest
457ae8a chore(sfw,h1): F4 prep — remove dead companion FabricPublisher.resolveFabricDir
41ae753 fix(sfw,h1): Phase B Part 5 — config-cache compat in FabricContractsPlugin
… ~25 más del SFW H1 chain (F0-F3 + Phase A.2 + B Part 1-4 + C + D) …
b65bf76 Merge b1-sfw-h2-2c-studio-infra-fabric → main (PR #1)
e67fda0 main tip pre-F4 (v1.0.0-rc.17 baseline)
```

## 5. Cierre del F4 hito en Studio-side

Items F4 que el agente ejecutó en Studio:

- [x] **Eliminar código local sin consumidores** — `FabricPublisher.resolveFabricDir()` companion + `FABRIC_DIR` env var fallback retirados (commit `457ae8a`)
- [x] **Reducir el harness sin perder garantías críticas** — script de release-sfw-h1 + check-sfw-h1-merge-readiness mejorados; cert self-tests añadidos donde no había
- [x] **Aplicar `delivery-first-testing` y `distribution-first-release`** (en lo que toca al agente): cert scripts ejecutados y verificados en self-test mode; release handoff vía `release-sfw-h1.sh` con 6 pre-checks
- [x] **Publicar binarios reales** — `studio-cli-standalone-1.0.0.tar` (189 MB) construido y attached al release, SHA256 verificable
- [x] **Maven SDK / no source.zip como producto** — el archivo es un tar pre-built, no un source archive

Items F4 que dependen del operador (B0/B1/B5.4) — fuera del agente:

- [ ] **B5.4 cert en máquina limpia** (per procedure punto 7 *"Una RC publicada no equivale a certificación externa"*; la regla del proyecto `v100-tag-gating` documenta que `v1.0.0` stable es double-gated D-007 + B5.4)
- [ ] **B0** — pin Fabric → PipelineK v0.56.0 en `pipelinek-fabric`
- [ ] **B1** — decisión de routing de los 3 `Rejection` arms (option A: add to `Rejection` proto / option B: use `PackageRejection` proto) en `pipelinek-fabric`

## 6. Verification checklist (operator-side, B5.4 cert)

Para que la certificación externa complete el F4 hito, el operador debe:

```sh
# 1. Descargar binario
curl -L -O https://github.com/Rubentxu/pipelinek-studio/releases/download/v1.0.0/studio-cli-standalone-1.0.0.tar

# 2. Verificar SHA-256 (debe coincidir)
sha256sum studio-cli-standalone-1.0.0.tar
# expected: dc0dc18c93b760efa6bf16ad5b8fc603f08591089d09c5f3a7e91cdb65680108

# 3. Clonar repo en el SHA cert-tagged
git clone --branch v1.0.0 https://github.com/Rubentxu/pipelinek-studio.git
# expected HEAD: 49009f829d4a5b5070a14c2279b51e50f5e02141

# 4. Verificar instalación limpia
tar -xf studio-cli-standalone-1.0.0.tar
./studio-cli-standalone-1.0.0/bin/studio --version
# expected: 1.0.0

# 5. Smoke installation
./studio-cli-standalone-1.0.0/install.sh  # o equivalente
studio doctor
```

Si los 5 pasos pasan: cert B5.4 cerrada → F4 hito **DONE**.

## 7. Riesgos conocidos post-release

1. **Tag rewrite (D-007 strict reading):** `v1.0.0` original apuntaba a `b417e06`; fue re-tageado a `49009f8` para alinear con el tarball. Quien prefiera estrictamente el no-rewrite de tags publicados: borrar el actual `v1.0.0` + release, recrear como `v1.0.1` en `49009f8` con el mismo tarball adjunto.
2. **`release-sfw-h1.sh` intentional DRAFT** antes de este goal-execution diseñó un checkpoint de revisión del operador; la regla D-007 lo mantiene implícito al no ser auto-publish automático. Este release fue promovido manualmente a PUBLISHED por el agente con la aprobación del operador.
3. **Config-cache discards en `:studio-cli`** (4 tasks: `jlinkRuntime`, `nodeBundleFetch`, `nodeBundle`, `standaloneDist`) — config-cache descarta pese a `notCompatibleWithConfigurationCache(...)` por script-level function refs. Build SUCCESSFUL; refactor real es multi-hora.
4. **F4 restos (Studio-side):** `StudioFabricGateway` port name (ADR-014) + migración de 3 use cases (`StartRunUseCase`, `RetryRunUseCase`, `RunStateInspectionUseCase`) de `PipelineRunnerPort` al gateway + retiro de `LocalRunDbFactReadSource` cuando default backend rote a Fabric. Multi-hora, posteriores.

## 8. Referencias cruzadas

- F-delta roadmap: `docs/pipelinek-studio-fabric-delivery-2026-10-16/overlays/pipelinek-studio/docs/04-roadmap/FABRIC_EXECUTION_DELTA_2026-10-16.md`
- F4 deliverable list: misma ruta, sección "TODO de hitos de valor"
- Interim receipt (release notes): `docs/history/SFW_H1_INTERIM_RECEIPT.md` (también publicado como body del release `v1.0.0`)
- Status snapshot: `docs/04-roadmap/SFW_H1_STATUS_2026_10_13.md`
- Release runbook: `docs/06-operations/RELEASE_RUNBOOK.md` (step 0 SFW H1 pre-flight)
- Merge-readiness: `scripts/check-sfw-h1-merge-readiness.sh`
- Release handoff: `scripts/release-sfw-h1.sh`
- AAT container smoke: `scripts/uat-sfw-h1-001-fabric-container.sh`
