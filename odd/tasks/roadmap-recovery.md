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
| **R3** Diagnósticos + IR Explorer | `v1.0.0-rc.5` | **R3.3 PASS** (HOUSEKEEPING en `73e2c47`) | 9 items restantes (diagnóstico tipado, parsers→SDK, separar COMPILED/ADMITTED/OBSERVED, Detekt aislado, Monaco markers, no ejecutar user code, UNSUPPORTED honesto, LSP spike) |
| **R4** Agent-first | `v1.0.0-rc.6` | abierto | 18 items en 3 sub-bloques (R4.1 protocolo, R4.2 experimentación, R4.3 seguridad) |
| **R5** EVO-C VS Code | `v1.0.0-rc.7` | abierto | 12 items; spec en `docs/04-roadmap/EVO-C_VSCODE_SPEC.md` |
| **R6** Distribución + cert integral | `v1.0.0-rc.8` | abierto | R6.1 distribución, R6.2 operación+seguridad, R6.3 contratos publicación |

## Cola priorizada (orden natural)

1. ✅ **R3.3 receipt housekeeping** — commit `73e2c47`; `ROADMAP.md` sync; tag `v1.0.0-rc.5@bd7f7ce` verificado.
2. **[NOW] R3.1 — diagnóstico tipado completo** (fase, severidad, source digest, localización, toolchain, versión, capabilities, procedencia). Existe tipo `Diagnostic` parcial en `application.ports`; hay que cerrar los 8 campos del mandato.
3. **R3.2** — parsers regex → decodificadores contra contrato SDK auténtico.
4. **R3.4** — separar `COMPILED / ADMITTED / OBSERVED` en IR.
5. **R3.5** — Detekt aislado (versiones pinneadas, fixtures +/-).
6. **R3.7 / R3.9** — integración Monaco + UNSUPPORTED honesto.
7. **Cierre R3 → release `v1.0.0-rc.5`** con VSIX EVO-C aún no requerido.
8. **R2** completo → `rc.4` (tras R3 si se reordena por valor: R2 antes que R3.5 porque habilita SDK real sobre el que R3 diagnostica).
9. **R4** → `rc.6`.
10. **R5** → `rc.7`.
11. **R6** → `rc.8`.
12. **Evaluación independiente sobre RC** → promover a `v1.0.0`.

> Nota: R2 antes que R3.5 es razonable porque R3.2 (decodificadores SDK) consume el mismo SDK cuyo plugin lab se construye en R2. Reconfirmar con el operador al cerrar R3.3.

## Item activo (single)

- **ID:** R3.1-TYPED-DIAGNOSTIC
- **Mandato:** diagnóstico tipado con 8 campos — fase, severidad, source digest, localización, toolchain, versión, capabilities, procedencia.
- **Punto de partida:** `Diagnostic` ya existe en `dev.pipelinek.studio.application.ports` con `phase`, `severity`, `line`, `column`, `path`. Falta: `sourceDigest`, `toolchain`, `capabilities`, `version` (procedencia ya enlazada vía `parseCompilationDiagnostics`).
- **Bloqueos:** ninguno (no requiere SDK subprocess vivo).
- **Cierre cuando:** tipo extendido, adapter SDK rellenando los 8 campos desde journal, ≥2 tests adversariales, gate verde, recibo.

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