# R2 — Step & Plugin Lab con SDK real — plan vivo

**META:** llevar R2 desde "sólo item 4 PASS" hasta un release `v1.0.0-rc.4` con fixture externo, admission lifecycle completo, TestKit y seam de extensibilidad.

**Autoridad de secuenciación:** este documento y `docs/02-specifications/STEP_PLUGIN_LAB.md` (no los receipts antiguos).
**Recibo previo:** `docs/history/R2_ITEM_4_RELEASE_RECEIPT.md` (item 4 ya PASS — Core*Step.class detection eliminada).

## Estado por item (snapshot 2026-10-11, HEAD `7bb9a0a`)

| # | Item | Estado | Bloqueo |
|---|---|---|---|
| **R2.1** | External fixture project | **ABIERTO** | trabajo nuevo, no integración |
| **R2.2** | Manifest inspection sin class loading | parcial (item 4 cubre el sub-punto Core*Step) | falta: vendor + version range match contra SDK pin |
| **R2.3** | Admission lifecycle (DISCOVERED/COMPATIBLE/ADMITTED/REFUSED/UNAVAILABLE) | **parcial** (sealed interface declarada en `PluginAdmission.kt`, sin use case ni port impl) | falta: `PluginAdmissionUseCase` + transiciones + persistencia de sha256 en lock |
| **R2.4** | Sustituir Core*Step.class por registry del SDK | **PASS** (`R2_ITEM_4_RELEASE_RECEIPT.md`) | — |
| **R2.5** | Congelar plugin set + digest por run | **ABIERTO** | depende de R2.3 (ADMITTED) |
| **R2.6** | Resolver colisiones de Step keys + versions | **ABIERTO** | depende de R2.5 |
| **R2.7** | Exponer StepContract + provenance + motivo de rechazo | **parcial** (`StepContract` existe, falta provenance + refusal reason en `PluginAdmissionState.Refused`) | falta: campo `provenance: StepProvenance` y `refusalReason` en StepContract |
| **R2.8** | Step TestKit (input, scenario, real invocation, events, result, artifacts) | **ABIERTO** | depende de R2.1 (fixture) + R2.3 (admission) |
| **R2.9** | Mismo dispatch para Step oficial y externo | **ABIERTO** | depende de R2.6 + R2.8 |
| **R2.10** | HTTP endpoints + MCP tools sólo cuando realmente conectados | **ABIERTO** | depende de R2.9 |
| **R2.11** | Extensibility seam para Policies/Assurance sin dependencias | **ABIERTO** | depende de R2.9 (el seam); no implementar Policies/Assurance aquí |

## Cola priorizada (orden natural)

1. **R2.1** — External fixture project en `examples/sdk-external-execution/` (3 plugins: válido, API incompatible, canary). Sin este fixture no se puede probar nada del resto.
2. **R2.3** — `PluginAdmissionUseCase` + port impl + transiciones + persistencia de sha256 en `plugin.lock.json`. Es el corazón del lifecycle.
3. **R2.2 (cierre)** — Vendor + version range match entre manifest del plugin y pin del SDK. AAT-021 (canary no corre).
4. **R2.7 (cierre)** — `provenance: StepProvenance` y `refusalReason` en `StepContract`. Sin esto no hay UAT-020 completo.
5. **R2.5** — Freeze plugin set + digest por run en `RunBinding`.
6. **R2.6** — Step key + version collision detection; ganador + perdedor + razón.
7. **R2.8** — Step TestKit (`StepContractSuite` + `PluginContractSuite` adaptadores) usando el fixture.
8. **R2.9** — Mismo dispatch path para builtin + external. AAT-024.
9. **R2.10** — `/api/plugins/{jar}/admit` + `/api/plugins/{jar}/refuse` HTTP + tools MCP.
10. **R2.11** — Documentar seam (sin código Policies/Assurance).
11. Release `v1.0.0-rc.4` housekeeping.

## Item activo (single)

- **ID:** R2.1-EXTERNAL-FIXTURE
- **Mandato:** Construir un proyecto Gradle externo (fuera de `pipeline-kotlin` y de Studio) que produzca 3 JARs de plugins contra el SDK publicado de PipelineK:
  - `valid-plugin` — manifest correcto, vendor = `dev.rubentxu.pipeline.v2`, version dentro del rango del SDK, sin inicializador canario. AAT-020 + AAT-021 happy path.
  - `incompatible-api-plugin` — vendor OK pero `pipelinek-api` version fuera del rango del SDK. AAT-021 unhappy path.
  - `canary-initializer-plugin` — manifest correcto, vendor + version OK, pero contiene un inicializador estático que imprime "CANARY_RAN" a stdout. El inspector NUNCA debe ejecutarlo (AAT-021 explícito: "canario de inicializador no corre").
- **Punto de partida:** No existe. Voy a crearlo desde cero.
- **Bloqueos:** ninguno técnico. El SDK publicado está en `gradle.properties:studio.version` (ahora `1.0.0-rc.6`); el artefacto `dev.rubentxu:pipelinek-plugin-api:1.0.0-rc.6` debería existir en el plugin portal. Si no, fallback: compilar contra el `studio-infra-sdk/src/main/resources/sdk.lock.json` (que ya pineaba la versión en R-VER-001).
- **Cierre cuando:** `./gradlew :examples:sdk-external-execution:build` verde + 3 JARs en `examples/sdk-external-execution/build/libs/` + un test smoke que confirme que el canary NO corre cuando StaticManifestInspector lee el JAR.

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
