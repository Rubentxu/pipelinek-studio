# Roadmap Recovery — R0 → R6 (plan vivo)

**META:** llevar el repo `pipelinek-studio` desde `main@bd7f7ce` hasta `v1.0.0` estable, mediante siete hitos verificables publicados como prerelease GitHub.

**Autoridad de secuenciación:** este documento y `ROADMAP.md` raíz (no los receipts antiguos).
**Reconciliación de nombres (pendiente):** B0–B7 (histórico en `docs/history/`), S0–S5 (`AGENT_HANDOFF.md`, `BLOCK_EXECUTION_MATRIX.md`), R0–R6 (roadmap activo). Resolver en R0 antes de empezar.

## Reglas del loop (valen para todos los hitos)

1. **Reutilizar** implementación válida; no reescribir módulos por patrón.
2. **Hexagonal**: puertos + ADTs/`sealed interface` + datos inmutables.
3. **PipelineK es el dueño** de journal, outcomes y Steps; Studio no duplica motor.
4. **Sin mocks, sin regex sobre código, sin archivos como evidencia.** Test no ejecutado = `NOT_MEASURED`.
5. **Commit work-unit** por item; RDD evalúa cada commit (no acumular para una sola review).
6. **Item PASS** requiere: recibo en `docs/history/` + commit integrado + tag anotado + GitHub Release **descargada e instalada**, no solo tag local.
7. **`BLOCKED`** requiere causa técnica concreta (p.ej. "kill-9 smoke requiere máquina con SDK subprocess vivo").
8. **`UNKNOWN` está prohibido** como cierre.
9. **`v1.0.0` estable** requiere evaluación externa independiente sobre la RC; ningún hito puede brincar de rc.N a estable por sí solo.
10. **D-007**: ningún `git push` sin aprobación explícita del operador.

## Procedimiento por item (idéntico en R0–R6)

```
A. ANTES   — git status, fetch, ls-remote, log -1; documentar SHA, plataforma,
              toolchain, SDK PipelineK + digest, Node/pnpm.
B. BLOQUE  — rama de bloque; commits atómicos Conventional Commits; tests focales
              y adversariales durante desarrollo.
C. GATE    — ./gradlew clean check --rerun-tasks; npm ci / lint / typecheck / test
              / build (en cada app Node aplicable); UAT/AAT del bloque + regresión
              de hitos previos.
D. REMOTO  — reconciliar origin/main; integrar rama; subir; tag anotado nuevo e
              inmutable; GitHub prerelease con artefactos + SHA256 + SBOM/manifest;
              descargar release y smoke desde instalación limpia.
E. EVIDENCIA — receipt en docs/history/<BLOQUE>_RELEASE_RECEIPT.md con
                comandos, exit codes, recuentos, UAT/AAT por ID, riesgos residuales,
                veredicto exclusivo PASS|FAIL|BLOCKED.
```

## Estado por hito (snapshot 2026-10-11, HEAD `bd7f7ce`)

| Hito | Tag objetivo | Estado | Items abiertos / próximos |
|---|---|---|---|
| **R0** Núcleo headless + cert | `v1.0.0-rc.2` | parcial | R0.1 (6 items, 0 PASS), R0.2 (6 items; 2.1/2.4/2.5/2.6 PASS, 2.2/2.3 diferidos), R0.3 (8/9 PASS; **R0.3.4 + R0.3.5 BLOCKED** por falta de máquina kill-9) |
| **R1** Consola + observabilidad | `v1.0.0-rc.3` | **PASS** | R1.1 EventStream, R1.2 OutputViewer, R1.3 Cmd-K + Monaco loader — receipts `R1_*_RECEIPT.md` |
| **R2** Plugin Lab con SDK real | `v1.0.0-rc.4` | abierto | 11 items — fixture externo, manifests sin class-loading, `DISCOVERED/COMPATIBLE/ADMITTED/REFUSED/UNAVAILABLE`, etc. |
| **R3** Diagnósticos + IR Explorer | `v1.0.0-rc.5` | **R3.3, R3.1 type, R3.2, R3.4, R3.5 PASS** (`73e2c47`, `c50c39c`, `49a9e06`, `22202e7`+`dfa4c46`, `333cbb4`+`f4c02d9`) | R3.1 wiring (toolchain+sourceDigest), R3.7/3.9 Monaco+UNSUPPORTED, LSP spike |
| **R4** Agent-first | `v1.0.0-rc.6` | abierto | 18 items en 3 sub-bloques (R4.1 protocolo, R4.2 experimentación, R4.3 seguridad) |
| **R5** EVO-C VS Code | `v1.0.0-rc.7` | abierto | 12 items; spec en `docs/04-roadmap/EVO-C_VSCODE_SPEC.md` |
| **R6** Distribución + cert integral | `v1.0.0-rc.8` | abierto | R6.1 distribución, R6.2 operación+seguridad, R6.3 contratos publicación |

## Cola priorizada (orden natural)

1. ✅ **R3.3 receipt housekeeping** — commit `73e2c47`; `ROADMAP.md` sync; tag `v1.0.0-rc.5@bd7f7ce` verificado.
2. ✅ **R3.1 type extension** — commit `c50c39c`; `Diagnostic.sourceDigest` añadido (8/8 campos del mandato en el tipo).
3. ✅ **R3.2 structured decoder** — commit `49a9e06`; `parseCompilationDiagnostics` ahora recorre el árbol JSON de kotlinx.serialization (sin regex); 2 tests adversariales nuevos; 11/11 verde.
4. ✅ **R3.4 phase separation** — commits `22202e7` + `dfa4c46`; 3 tests adversariales pinneando COMPILED/ADMITTED/OBSERVED; 14/14 verde; recibo `docs/history/R3_4_RELEASE_RECEIPT.md`.
5. ✅ **R3.5 Detekt aislado** — commits `333cbb4` (deliverables) + `f4c02d9` (recibo); fixtures `FixturePositive` (clean) + `FixtureNegativeLongMethod` (78/60); `./gradlew detektFixtures` valida ambos; `detekt.yml` anotado con `# Why:` por regla; bug pre-existente `SpreadOperator` corregido.
6. **[NOW] R3.7 / R3.9 — integración Monaco + UNSUPPORTED honesto** (mandato: integrar diagnósticos y markers en Monaco, evitar obsoletos, no ejecutar user code, `UNSUPPORTED` cuando PipelineK no tenga static analysis seguro).
7. **R3 LSP spike** — spike Kotlin LSP, no release requirement si no aporta precisión.
8. **R3 cierre → release `v1.0.0-rc.5`** con VSIX EVO-C aún no requerido.
9. **R2** completo → `rc.4` (R3 antes que R2 decidido: R3.2/R3.4/R3.5 se pudieron hacer sin SDK subprocess vivo).
10. **R4** → `rc.6`.
11. **R5** → `rc.7`.
12. **R6** → `rc.8`.
13. **Evaluación independiente sobre RC** → promover a `v1.0.0`.

> Pendiente residual R3.1: el adapter todavía NO rellena `toolchainVersion`/`toolchainDigest`/`sourceDigest` desde el journal — los campos están en el tipo pero el adaptador los deja `null`. Decisión arquitectural pendiente: ¿cruzar a studio.db para el source-digest, o exponer un `ProvenanceContext` por puerto? Lo trato como sub-item al cerrar R3.7/3.9.

## Item activo (single)

- **ID:** R3.7-R3.9-MONACO-UNSUPPORTED
- **Mandato:** integrar diagnósticos y markers en Monaco; evitar diagnósticos obsoletos; no ejecutar código Kotlin del usuario para análisis estático; si PipelineK no tiene static analysis seguro, publicar `UNSUPPORTED`.
- **Punto de partida (a verificar):**
  - `apps/web` ya tiene Monaco (R1.3 evidence); habría que leer el código de markers / decorations.
  - `StaticAnalyzeCapability` sealed type ya existe (`application.ports.Diagnostics.kt`) con `Available(analyzerVersion, analyzerDigest, rulesetVersion)` y `Unavailable(reason)`. La rama `Unavailable` ya cubre el `UNSUPPORTED` honesto mandated.
- **Acción propuesta:** (a) leer el wiring actual de Monaco markers en `apps/web`; (b) asegurar que el endpoint backend devuelve `StaticAnalyzeCapability.Unavailable(reason)` cuando Detekt no está disponible, no una lista vacía (cerrar el anti-pattern 'no findings = clean'); (c) añadir test que prueba que el endpoint expone `Unavailable` cuando Detekt está deshabilitado por env (`STUDIO_STATIC_ANALYZE_DISABLED=1`); (d) asegurar que Monaco descarta marcadores cuyo `sourceDigest` no coincide con el snapshot actual.
- **Bloqueos:** ninguno (no requiere SDK subprocess vivo).
- **Cierre cuando:** (a) comportamiento `UNSUPPORTED` visible al SPA cuando corresponde; (b) tests adversariales para el anti-pattern 'empty == clean'; (c) stale-marker guard contra snapshot-edit; gate verde; recibo.

## Riesgos abiertos

| ID | Riesgo | Bloquea | Mitigación |
|---|---|---|---|
| RK-01 | R0.3.4/R0.3.5 sin máquina kill-9 | cierre R0 | conseguir runner smoke antes de promover rc.2 |
| RK-02 | Tag `v1.0.0-rc.5` no publicado como GitHub Release | release verificable R3 | abrir prerelease con artefactos+SHA256 antes de cerrar R3 |
| RK-03 | Nomenclatura B/S/R sin reconciliar | confusión de receipts | actualizar `ROADMAP.md` y `BLOCK_EXECUTION_MATRIX.md` en R0 |
| RK-04 | Sin `v1.0.0-rc.1` Release remoto publicado | trazabilidad histórica | emitir release retroactiva o documentar impedimento |
| RK-05 | Detekt `gradlew detekt` no existe | CI verde | exponer `detekt-run.sh` o crear tarea real |

## Cómo se actualiza este plan

- Al cerrar un item: marcar `PASS`, escribir SHA + receipt path, mover al estado del hito.
- Al abrir un nuevo item: añadir al hito correspondiente; nunca sobreescribir items PASS.
- Si aparece un nuevo `BLOCKED`: agregar a Riesgos con dueño + siguiente trigger.
- El **item activo** es siempre uno (o ninguno en transiciones de inter-block).
- El espejo Engram vive bajo topic `odd/roadmap-recovery/tasks`; ambos se actualizan juntos.

## Forma del loop (selección operador)

- **Tipo:** Plan + autoarranque dinámico `/loop` (ScheduleWakeup autónomo).
- **Cadencia:** primer wakeup 20 min; cadencia posterior ajustada por la propia iteración.
- **Sentinel:** `<<autonomous-loop-dynamic>>` (resuelto por el runtime a instrucciones autonomous-loop en cada wakeup).
- **Reglas adicionales del autonomous loop:** nunca `git push`, nunca borrar/mover tags, nunca `v1.0.0` directo desde rc.N, mantener `D-007`. STOP ante historial divergente.