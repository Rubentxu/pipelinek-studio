# R2 — Step & Plugin Lab con SDK real — plan vivo

**META:** llevar R2 desde "sólo item 4 PASS" hasta un release `v1.0.0-rc.4` con fixture externo, admission lifecycle completo, TestKit y seam de extensibilidad.

**Autoridad de secuenciación:** este documento y `docs/02-specifications/STEP_PLUGIN_LAB.md` (no los receipts antiguos).
**Recibo previo:** `docs/history/R2_ITEM_4_RELEASE_RECEIPT.md` (item 4 ya PASS — Core*Step.class detection eliminada).

## Estado por item (snapshot 2026-10-11, HEAD `3c743f9`)

| # | Item | Estado | Bloqueo |
|---|---|---|---|
| **R2.1** | External fixture project | **PASS** (`e4129f8` + `c56d782`) | 3 JARs + 3 tests AAT-020/021; recibo `docs/history/R2_1_RELEASE_RECEIPT.md` |
| **R2.2** | Manifest inspection sin class loading | **PASS** (`3d1d4a9` + `0bf7421`) | Header `X-PipelineK-Sdk-Version-Range` parseado Maven-style; 7 tests adversariales |
| **R2.3** | Admission lifecycle (DISCOVERED/COMPATIBLE/ADMITTED/REFUSED/UNAVAILABLE) | **PASS** (`b043f90` + `6da7fb7`) | use case + FilePluginAdmissionLock + 12 tests; recibo `docs/history/R2_3_RELEASE_RECEIPT.md` |
| **R2.4** | Sustituir Core*Step.class por registry del SDK | **PASS** (`R2_ITEM_4_RELEASE_RECEIPT.md`) | — |
| **R2.5** | Congelar plugin set + digest por run | **PASS** (`b4b4dae` + `3c743f9`) | freeze en `RunBinding.frozenPluginDigests`; V2 migration + `findRunBinding`; 16 tests. Recibo `R2_5_RELEASE_RECEIPT.md` |
| **R2.6** | Resolver colisiones de Step keys + versions | **ABIERTO** | depende de R2.5 (digest + provenance disponibles) |
| **R2.7** | Exponer StepContract + provenance + motivo de rechazo | **PASS** (`258e1b2` + `226a330`) | `provenance: StepProvenance` + `refusalReason` + `StepDiscovery` enum; 4 tests |
| **R2.8** | Step TestKit (input, scenario, real invocation, events, result, artifacts) | **ABIERTO** | depende de R2.1 (fixture) + R2.3 (admission) |
| **R2.9** | Mismo dispatch para Step oficial y externo | **ABIERTO** | depende de R2.6 + R2.8 |
| **R2.10** | HTTP endpoints + MCP tools sólo cuando realmente conectados | **PASS** (`e782892` + `c9ef962`) | 5 HTTP routes + 5 MCP tools; 6 tests; 501 NOT_WIRED fallback. Recibos `R2_10_RELEASE_RECEIPT.md` |
| **R2.11** | Extensibility seam para Policies/Assurance sin dependencias | **PASS** (`65d22fb` + `c9f7505`) | doc-only; `docs/04-roadmap/EXTENSIBILITY_SEAM.md` + recibo `R2_11_RELEASE_RECEIPT.md` |

## Cola priorizada (orden natural)

1. ✅ **R2.1** — External fixture project en `examples/sdk-external-execution/`. PASS en `e4129f8` + `c56d782`.
2. ✅ **R2.3** — `PluginAdmissionUseCase` + `FilePluginAdmissionLock` + 12 tests. PASS en `b043f90` + `6da7fb7`.
3. ✅ **R2.2 cierre** — Header `X-PipelineK-Sdk-Version-Range` parseado (Maven-style). PASS en `3d1d4a9` + `0bf7421`; 7 tests adversariales.
4. ✅ **R2.7 (cierre)** — `provenance: StepProvenance` y `refusalReason` en `StepContract`. PASS en `258e1b2` + `226a330`; 4 tests.
5. ✅ **R2.10** — `/api/plugins` HTTP routes (5 endpoints) + 501 NOT_WIRED fallback. PASS en `e782892` + `dbe5961`; 6 tests.
6. ✅ **MCP tools** (R2.10 paralelo) — `studio.plugins.{list,inspect,admit,refuse,revoke}` en `apps/mcp/`. PASS en `c9ef962`.
7. ✅ **R2.11** — Documentar seam (sin código Policies/Assurance). PASS en `65d22fb` + `c9f7505`.
8. ✅ **R2.5** — Freeze plugin set + digest por run en `RunBinding`. PASS en `b4b4dae` + `3c743f9`; 16 tests; V2 migration.
9. **R2.6** — Step key + version collision detection; ganador + perdedor + razón.
10. **R2.8** — Step TestKit (`StepContractSuite` + `PluginContractSuite` adaptadores) usando el fixture.
11. **R2.9** — Mismo dispatch path para builtin + external. AAT-024.
12. Release `v1.0.0-rc.4` housekeeping.

## Item activo (single)

- **ID:** R2.6-STEP-COLLISION
- **Mandato:** Cuando dos o más plugins declaran el mismo `stepKey` (o el mismo `stepKey` con versions distintas) el catalog de steps debe detectar la colisión, elegir un ganador determinista, y reportar perdedores con razón. El winner es el plugin con la version más alta según semver (major.minor.patch); empate → el plugin que aparece primero en el orden de admission; empate persistente → `Refused` con `collisionReason`.
- **Punto de partida:** `StepContract` (R2.7) tiene `id` (que es el stepKey) + `pluginJar` + `provenance` + `refusalReason`; no hay un collision resolver. Hoy, si dos plugins declaran el mismo `id`, ambos aparecen en el catalog sin warning. R2.6 añade un `StepCollisionResolver` (en `studio-application/.../usecases/`) que itera la lista de StepContracts y produce un `ResolvedCatalog { winners: List<StepContract>, collisions: List<CollisionReport> }`. El catalog público es `winners`; las colisiones se exponen vía un campo en la response del catalog.
- **Cierre cuando:** (a) dos plugins con el mismo stepKey → uno gana, uno pierde con razón; (b) tres plugins con mismo stepKey, mismo version → primero en orden de admission gana, otros rechazados; (c) versions distintas → mayor gana (semver); (d) tests adversariales: 0 collisions con plugins únicos; 1 collision con 2 duplicados; major version pin; (e) suites verde.

## Cómo se actualiza este plan

- Al cerrar un item: marcar `PASS`, escribir SHA + receipt path.
- Al abrir un nuevo item: añadir a la cola priorizada.
- Si aparece un nuevo `BLOCKED`: agregar a Riesgos con dueño + siguiente trigger.
- El **item activo** es siempre uno (o ninguno en transiciones de inter-block).
- El espejo Engram vive bajo topic `odd/r2-plugin-lab/tasks`; ambos se actualizan juntos.

## Riesgos abiertos

| ID | Riesgo | Bloquea | Mitigación |
|---|---|---|---|
| RK-R2-01 | SDK publicado puede no estar en el portal | R2.1 | Compilar contra `studio-infra-sdk/src/main/resources/sdk.lock.json` como fallback |
| RK-R2-02 | El `PluginAdmissionUseCase` necesita persistencia (lock) | R2.3 | Usar `plugin.lock.json` en `~/.pipelinek-studio/`, mismo directorio que `studio.db` |
| RK-R2-03 | El fixture necesita Java compilation contra SDK real | R2.1 | Si SDK no compila en sandbox (memoria `gradle-jvm-sandbox-network`), pre-fetchar el JAR via host shell |
| RK-R2-04 | El TestKit necesita un step registry vivo del SDK | R2.8 | Si el SDK subprocess está BLOCKED (R0.3.4/R0.3.5), el TestKit corre contra el adapter estático que ya existe (`PipelineKStepCatalogReadPort`) — no requiere subprocess vivo |
| RK-R2-05 | El freeze de plugin set debe coordinarse con el snapshot del `RunBinding` | R2.5 | Resuelto: una sola escritura atómica via V2 migration; freeze es `INSERT ... ON CONFLICT DO NOTHING` (B1.2) |
| RK-R2-06 | El collision resolver necesita una semver parser; ad-hoc parsing puede tener bugs | R2.6 | Reusar el parser de `PluginAdmissionUseCase.isVersionInRange` (Maven-style) o extraer a un `dev.pipelinek.studio.domain.Semver` helper compartido |
