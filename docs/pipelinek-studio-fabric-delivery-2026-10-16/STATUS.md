# STATUS — `docs/pipelinek-studio-fabric-delivery-2026-10-16/`

**STATUS: PROPOSED — NOT APPLIED — VERIFY BEFORE USE** (2026-10-17, E3)

Este directorio contiene un **kit de integración propuesto** entre
PipelineK Studio y el backend Fabric (`run-control`, `model`,
`observation`). Es un delta operativo del ciclo F0–F4 fechado
2026-10-16.

## Por qué este directorio NO está mergeado en `main`

- El kit precede a los ADR-014..018, que ya están ratificados en el
  repo (`docs/03-adrs/`, commit `2951164`) y son la **autoridad vigente**
  para decisiones sobre Fabric.
- Los artefactos Fabric que el kit presupone (`run-control`, `model`,
  `observation 0.5.0-R1-alpha.8`) siguen **NO publicados** en Maven
  Central (ver `odd/tasks/operational-verification.md` L17, L19, T13;
  bloqueador `G0` en `docs/04-roadmap/ROADMAP_NUEVO_2026-10-17.md`).
- El propio README y `INTEGRACION.md` del kit están escritos como
  propuesta de ciclo; el árbol activo (`main` al `59d6532`) lleva la
  realidad reconciliada en su lugar.

## Qué hacer antes de aplicar nada de aquí

1. Confirmar que `io.jenkins.pipelinek.fabric:{model,run-control,observation}`
   ya resuelve en Maven Central. Si devuelve 404, **no se aplica nada
   del kit** — el Studio caería al `:studio-infra-fabric:test`
   `EXECUTION_BLOCKED` documentado en `odd/tasks/operational-verification.md`
   L19/T13.
2. Releer `docs/03-adrs/ADR-014..ADR-018.md` y reconciliar con
   `BASELINE_Y_TRAZABILIDAD.md` del kit. Si hay conflicto, **los ADR
   ganan** (son la autoridad).
3. Aplicar el delta **en una rama dedicada** (`feature/f-delta-apply`),
   no en `main`, y re-correr `./gradlew test detekt
   -x :publishFabricContractsLocal --offline --console=plain`. Esperar
   la integración con la suite completa antes de fusionar.

## Si decides que el kit nunca se va a aplicar

Bórralo. Esta nota entonces se puede eliminar junto con el kit.

— Studio Bootstrap Agent, ciclo R0.6 (2026-10-17)
