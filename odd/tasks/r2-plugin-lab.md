# R2 — Step & Plugin Lab con SDK real — plan vivo

**META:** llevar R2 desde "sólo item 4 PASS" hasta un release `v1.0.0-rc.4` con fixture externo, admission lifecycle completo, TestKit y seam de extensibilidad.

**Autoridad de secuenciación:** este documento y `docs/02-specifications/STEP_PLUGIN_LAB.md` (no los receipts antiguos).
**Recibo previo:** `docs/history/R2_ITEM_4_RELEASE_RECEIPT.md` (item 4 ya PASS — Core*Step.class detection eliminada).

## Estado por item (snapshot 2026-10-11, HEAD `c9f7505`)

| # | Item | Estado | Bloqueo |
|---|---|---|---|
| **R2.1** | External fixture project | **PASS** (`e4129f8` + `c56d782`) | 3 JARs + 3 tests AAT-020/021; recibo `docs/history/R2_1_RELEASE_RECEIPT.md` |
| **R2.2** | Manifest inspection sin class loading | **PASS** (`3d1d4a9` + `0bf7421`) | Header `X-PipelineK-Sdk-Version-Range` parseado Maven-style; 7 tests adversariales |
| **R2.3** | Admission lifecycle (DISCOVERED/COMPATIBLE/ADMITTED/REFUSED/UNAVAILABLE) | **PASS** (`b043f90` + `6da7fb7`) | use case + FilePluginAdmissionLock + 12 tests; recibo `docs/history/R2_3_RELEASE_RECEIPT.md` |
| **R2.4** | Sustituir Core*Step.class por registry del SDK | **PASS** (`R2_ITEM_4_RELEASE_RECEIPT.md`) | — |
| **R2.5** | Congelar plugin set + digest por run | **ABIERTO** | depende de R2.3 (ADMITTED) |
| **R2.6** | Resolver colisiones de Step keys + versions | **ABIERTO** | depende de R2.5 |
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
8. **R2.5** — Freeze plugin set + digest por run en `RunBinding`.
9. **R2.6** — Step key + version collision detection; ganador + perdedor + razón.
10. **R2.8** — Step TestKit (`StepContractSuite` + `PluginContractSuite` adaptadores) usando el fixture.
11. **R2.9** — Mismo dispatch path para builtin + external. AAT-024.
12. Release `v1.0.0-rc.4` housekeeping.

## Item activo (single)

- **ID:** R2.5-RUNBINDING-FREEZE
- **Mandato:** Cuando un run ejecuta, congelar el plugin set + digest por run en `RunBinding` para que un re-run sea reproducible bit-a-bit. El freeze ocurre en el momento que se inicia la ejecución; cualquier cambio posterior en el admission state (revoke, new admit) no afecta al run en curso.
- **Punto de partida:** `RunBinding` existe en `studio-application/.../ports/` (R0.3.6); no congela plugin set. La decision: (a) `RunBinding` añade un campo `frozenPluginDigests: List<PluginDigest>` (sha256 + jar filename + admittedBy + frozenAt); (b) el use case de run execution clona el plugin set actual al iniciar; (c) el freeze se serializa a `~/.pipelinek-studio/runs/<runId>.binding.json` (vía `RunBindingStore` port, mismo patrón que `FilePluginAdmissionLock`).
- **Cierre cuando:** (a) `RunBinding` tiene `frozenPluginDigests`; (b) al iniciar un run, el freeze se persiste; (c) un re-run del mismo `runId` retorna exactamente el mismo `frozenPluginDigests` aunque el admission state haya cambiado; (d) tests adversariales: revoke mid-run no afecta al run en curso; new admit mid-run no aparece; (e) suites verde.

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
| RK-R2-05 | El freeze de plugin set debe coordinarse con el snapshot del `RunBinding` | R2.5 | Persistir el freeze en el mismo `RunBindingStore` que ya pineaba el binding; una sola escritura atómica |
