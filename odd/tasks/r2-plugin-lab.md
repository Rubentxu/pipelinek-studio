# R2 — Step & Plugin Lab con SDK real — plan vivo

**META:** llevar R2 desde "sólo item 4 PASS" hasta un release `v1.0.0-rc.4` con fixture externo, admission lifecycle completo, TestKit y seam de extensibilidad.

**Autoridad de secuenciación:** este documento y `docs/02-specifications/STEP_PLUGIN_LAB.md` (no los receipts antiguos).
**Recibo previo:** `docs/history/R2_ITEM_4_RELEASE_RECEIPT.md` (item 4 ya PASS — Core*Step.class detection eliminada).

## Estado por item (snapshot 2026-10-11, HEAD `11698b8`) — **R2 CERRADO**

| # | Item | Estado | SHA cierre |
|---|---|---|---|
| **R2.1** | External fixture project | **PASS** | `e4129f8` + `c56d782` |
| **R2.2** | Manifest inspection sin class loading | **PASS** | `3d1d4a9` + `0bf7421` |
| **R2.3** | Admission lifecycle (DISCOVERED/COMPATIBLE/ADMITTED/REFUSED/UNAVAILABLE) | **PASS** | `b043f90` + `6da7fb7` |
| **R2.4** | Sustituir Core*Step.class por registry del SDK | **PASS** | (R2.4) |
| **R2.5** | Congelar plugin set + digest por run | **PASS** | `b4b4dae` + `3c743f9` |
| **R2.6** | Resolver colisiones de Step keys + versions | **PASS** | `8ccebb7` + `f828867` |
| **R2.7** | Exponer StepContract + provenance + motivo de rechazo | **PASS** | `258e1b2` + `226a330` |
| **R2.8** | Step TestKit (input, scenario, real invocation, events, result, artifacts) | **PASS** | `add0022` + `41af81f` |
| **R2.9** | Mismo dispatch para Step oficial y externo | **PASS** | `206b8c6` + `75241c7` |
| **R2.10** | HTTP endpoints + MCP tools sólo cuando realmente conectados | **PASS** | `e782892` + `c9ef962` |
| **R2.11** | Extensibility seam para Policies/Assurance sin dependencias | **PASS** | `65d22fb` + `c9f7505` |
| **R2 closure** | Version bump + tag + release notes | **PASS** | `df84f41` + `11698b8` |

## R2 — CIERRE

**Tag:** `v1.0.0-rc.7` (anotado en `origin`).
**Recibos:** `docs/history/R2_{1,3,5,6,8,9,10,11}_RELEASE_RECEIPT.md` + `R2_ITEM_4_RELEASE_RECEIPT.md` + `R2_CLOSURE_v1_0_0_rc_7.md`.
**Tests:** 644/644 Kotlin + 31/31 MCP + 64/64 web + 10/10 VersionCoherenceB0Test.
**Riesgos cerrados:** RK-R2-01..08 (todos resueltos en R2).
**Riesgos abiertos (siguen a R3/R6):**
- RK-R2-09: SDK subprocess no vivo en este build (R6.1).
- RK-R2-10: cross-validación `Scenario.pluginLockDigest` ↔ `RunBinding.pluginSetDigest` (R3 retroactiva).
- RK-R2-11: `Wiring.admissionLock` queda null hasta R6.1.
- RK-R2-12: `findRunBinding` no expuesto en HTTP hasta R6.1.

## Next milestones (per `docs/04-roadmap/ROADMAP.md`)

- **R3 retroactiva** — `v1.0.0-rc.5` está taggeada pero sin release público (RK-02). Sub-tarea: crear el release público en GitHub (dragón de la release page).
- **R5 (distribución)** — milestone abierto (sin items PASS).
- **R6 (open)** — milestone abierto (sin items PASS).
- **v1.0.0 stable** — gate triple per D-007:
  1. External evaluation del RC `v1.0.0-rc.7`.
  2. R4 B5.4 cert on a clean machine (no sandbox).
  3. Operator approval explícito para el push a `v1.0.0`.

## Cómo se actualiza este plan

- Plan vivo en modo "cerrado": la tabla de items es de referencia;
  el siguiente operador puede:
  - Reanotar items cerrados con un nuevo SHA si un re-roll ocurre.
  - Abrir un nuevo plan vivo `odd/tasks/r3-retroactiva.md` para R3.
  - Continuar con R5 / R6 abriendo sus propios planes vivos.

## Riesgos cerrados (R2)

| ID | Riesgo | Cierre | SHA |
|---|---|---|---|
| RK-R2-01 | SDK publicado puede no estar en el portal | Compilado contra `studio-infra-sdk/src/main/resources/sdk.lock.json` | R2.1 |
| RK-R2-02 | El `PluginAdmissionUseCase` necesita persistencia (lock) | `FilePluginAdmissionLock` en `~/.pipelinek-studio/plugin.lock.json` | R2.3 |
| RK-R2-03 | El fixture necesita Java compilation contra SDK real | 3 JARs construidos en `examples/sdk-external-execution/build/libs/` | R2.1 |
| RK-R2-04 | El TestKit necesita un step registry vivo del SDK | TestKit corre contra el adapter estático (`PipelineKStepCatalogReadPort`) | R2.8 |
| RK-R2-05 | El freeze de plugin set debe coordinarse con el snapshot del `RunBinding` | V2 migration + `INSERT ... ON CONFLICT DO NOTHING` (B1.2) | R2.5 |
| RK-R2-06 | El collision resolver necesita una semver parser | Regex `(\d+)\.(\d+)\.(\d+)` minimal | R2.6 |
| RK-R2-08 | El unified dispatch necesita verificar que NO hay `when (step.discovery == ...)` en producción | Code review + 8 tests AAT-024 + grep guard | R2.9 |
