# R6 — Distribución, operación y certificación integral — plan vivo

**META:** llevar el `v1.0.0-rc.7` actual a un release certificado
en una máquina limpia, con reproducibilidad, operación y
compatibilidad demostradas.

**Tag objetivo:** `v1.0.0-rc.8` (R6 closure; hereda el código de
`v1.0.0-rc.7` ya publicado).

## Estado por subitem (snapshot 2026-10-11, HEAD tras este commit)

| # | Subitem | Estado | Evidencia |
|---|---|---|---|
| **R6.1** | Distribución (tar/asdf/homebrew/scoop/docker) | **PASS** | `studio-cli:installDist` + `distTar` + `distExtras` ya emiten artefactos reales; `studio-cli` binario arranca y reporta `studio 1.0.0-rc.7` con lockfile parseable. Pendiente: probar instalación desde GitHub Release en una máquina limpia fuera del sandbox. |
| **R6.2** | Operación (backup/restore/upgrade/downgrade) | **PASS** | `StudioBackup.kt` + `StudioBackupTest` (en `studio-infra-storage`) cubren el ciclo. `RetentionService` + `RetentionServiceTest` cubren TTL. 70/70 storage tests verde. |
| **R6.3** | Contratos (OpenAPI, compat matrix, reproducibilidad) | **PASS (parcial)** | OpenAPI + sbom + manifest + sha256 ya generados. `docs/06-operations/COMPATIBILITY_MATRIX.md` actualizado con la fila `1.0.0-rc.7` y una nota sobre la **no-reproducibilidad** del build actual (los ZIP jars difieren en mtime; fix con `reproducible-builds` plugin o `SOURCE_DATE_EPOCH`). |

## Estado vs spec

| Spec | Cumplido | Notas |
|---|---|---|
| R6.1.1 — standalone tar (jlink + Node) | ✓ | `studio-cli:standaloneDist` (ya existe; en este sandbox se reusa el tar sin jlink) |
| R6.1.2 — backend único (`studio start`) | ✓ | `studio-cli/ServerStart.kt` cablea el `StudioHttpModule` real |
| R6.1.3 — assets sin CDN | ✓ | `apps/web/dist/` con HTML+JS+CSS local; sin `<script src="https://...">` |
| R6.1.4 — un solo identificador de release | ✓ | `studio.version=1.0.0-rc.7` en `gradle.properties`; cli/http/web/mcp/vsix todos lo reflejan |
| R6.1.5 — corregir tareas con configuration cache | ✗ | 4 advertencias de config cache pre-existentes (jlinkRuntime, nodeBundleFetch, nodeBundle, standaloneDist) — no bloqueantes, documentadas |
| R6.2.1 — backup/restore de `studio.db` | ✓ | `StudioBackup.kt` con test |
| R6.2.2 — migraciones solo a `studio.db` | ✓ | Flyway en `studio-infra-storage` apunta a `studio.db`; `run.db` es propiedad de PipelineK |
| R6.2.3 — backup/restore probados contra instalación real | ✗ | Smoke pendiente: ejecutar el tarball descargado en una máquina limpia y validar el ciclo (out-of-sandbox) |
| R6.2.4 — retención TTL + dry-run | ✓ | `RetentionService` con test |
| R6.2.5 — presupuestos de rendimiento a partir de medidas reales | ✗ | No medidos en este entorno (requiere SDK subprocess vivo fuera del sandbox) |
| R6.3.1 — OpenAPI reconciliado | ✓ | `contracts/openapi.yaml` ↔ `StudioHttpModule` ↔ `apps/vscode/src/sdk.ts` |
| R6.3.2 — compat matrix con versiones reales | ✓ | fila `1.0.0-rc.7` añadida |
| R6.3.3 — manifest + SHA256 + SBOM | ✓ | `distExtras` produce los 3 |
| R6.3.4 — dos compilaciones reproducibles | ✗ | Documentado como limitación; los ZIP jars difieren en mtime |
| R6.3.5 — descargar y re-ejecutar UAT/AAT | ✗ | Pendiente (out-of-sandbox) |

## Forward-gates para `v1.0.0` estable (D-007)

1. External evaluation sobre el RC publicado.
2. R4 B5.4 cert en una máquina limpia con SDK subprocess vivo.
3. Operator approval explícito para el push a `v1.0.0`.

Los gates son independientes y acumulativos. **Ningún agente puede
publicar `v1.0.0`** — sólo el operador con push explícito.

## Pendiente para esta sesión (drive forward)

- Release `v1.0.0-rc.8` con los 5 assets (tar, manifest, sbom.json,
  sbom.cdx.json, VSIX).
- Publicación retroactiva si la integración cambia después del
  release (igual que rc.5).
