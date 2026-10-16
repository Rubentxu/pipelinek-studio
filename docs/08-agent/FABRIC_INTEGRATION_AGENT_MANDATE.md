# MANDATO al agente — integración real Studio → Fabric gRPC

**Primer documento a leer:** `ROADMAP.md` raíz de Studio, única autoridad R0–R6. Este mandato es un DELTA con orden F0–F4, no permiso para reabrir hitos cerrados o adelantar el roadmap Fabric. Leer luego `docs/04-roadmap/FABRIC_EXECUTION_DELTA_2026-10-16.md`, `docs/02-specifications/FABRIC_INTEGRATION_SPEC.md`, ADR-014..018 y el delta instalado de Fabric `docs/studio-bridge/`.

## Goal

Construir, publicar y certificar una integración **real**: snapshot Studio → `FabricClient` → gRPC TLS → Fabric → PipelineK worker → hechos y consola → Studio, sin segundo motor y sin tests permanentemente inflados. Cada F0–F4 exige entrega usable, integración en remoto y release real verificada desde consumidor descargado.

## Inicialización y descubrimiento obligatorio

1. Leer `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, `git ls-remote origin refs/heads/main`, `git tag --list` y releases/registro; no confiar en los SHA del kit como actuales.
2. Verificar estado SDDK global de los tres proyectos `STATE/CURRENT/JOURNAL` en carpeta de usuario por proyecto, sin añadir `.pipeline.kts`, SDDK state ni ficheros de agente al repo sin razón.
3. Mirar código **implementado** primero: `LocalRunControl.start`, `RunRegistry.start`, `StartRunRequest`, `WorkerWork`, `PipelineCatalog`, `FabricClient`, `FabricServerApplication`, `StartRunUseCase`, wiring real Studio y lectores. Validar las hipótesis del informe contra HEAD; si ya están resueltas, marcar hecho sin recrear código.
4. Verificar versiones **remotamente publicadas** del cliente Fabric y distribución PipelineK, digest y POM transitivo. No consumir project dependency `mavenLocal` en certificación.
5. Resolver siguiente item verdaderamente pendiente del `ROADMAP.md` actual y enganchar F0–F4 sin segunda autoridad. Si conflicto de agenda, conservar roadmap vigente y reportar bloqueo breve con propuesta mínima, no cambiar secuencia unilateralmente.

## Hitos y stop rules

**F0** corregir dispatch idempotente/params y publicar Fabric. **F1** CAS durable+PreparePipeline y Studio headless real, publicar Fabric antes que Studio. **F2** eliminar runner read-side Studio, live observación real y UX sobre Fabric, publicar. **F3** features sobre capabilities reales, versiones y proyecciones, publicar. **F4** retirar duplicados/test harness y certificar distribución instalada, publicar. Leer criterios y referencias exactos en `docs/04-roadmap/FABRIC_EXECUTION_DELTA_2026-10-16.md`.

**Prohibiciones:** no escribir `run.db` desde Studio, no emitir terminal sintético, no lanzar PK desde Studio, no reimplementar cliente gRPC, no convertir loss/timeout/unknown en FAIL/SUCCESS, no tratar `StartRun` aceptado como ejecución finalizada, no falsificar `live` ni `input` con un test que nunca toca un worker real, no force push/no reescritura de tags publicados.

## Rutina skill `delivery-first-testing` — usarla de verdad

- Ubicación de la skill **en repo** (no asumir conexión instalada): `Rubentxu/agent-skill/skills/delivery-first-testing/SKILL.md`; leer su `references/navigation.md`, `lens-registry.json` y **solo las lentes `ready` pertinentes**. Si skill no está instalada en el entorno agente, informar y aplicar playbooks documentados, sin afirmar que se invocó.
- Input al router: `intent=feature|merge|integration|release|audit`, `changed=api,domain,protocol,persistence,security,ui,release,harness` según diff, `risk`, `impact`, `basis`, `affected`, `uat`, `regression`, `artifact`, `evidence_commit` reales. Ningún campo omitido equivale a PASS; `planned` no sirve.
- Feature: `change-impact` → affected unit/contracts → `ephemeral-uat` real cuando haga falta → `regression-promotion` PROMOTE/REPLACE/RETIRE/DEFER → retirar temporal. Para fallos `failure-triage` antes de repetir. Merge `merge-assurance`, integración `integration-assurance`, release `release-assurance`; auditoría de harness `harness-economics`.
- UAT/E2E/AAT se escriben en `$TMPDIR` sesión/hito. Si detectan un fallo crítico, conservar **contrato mínimo** y registrar resultado antes de borrar script. No eliminar tests preexistentes de integridad, security o recovery sin sustituto probado.
- No construir un nuevo CI/gate; reutilizar Gradle/npm/PipelineK CI existente y medir costes reales.

## Orientación funcional Haskell-style

- Tipos algebraicos cerrados por significado, no `String` con valores mágicos ni `Map<String,Any>` para estado interno.
- `fold(state, fact) -> Either<DomainError, State>` puro, total y con `when` exhaustivo; proyecciones solo en owner Fabric, Studio solo mapeo/presentación.
- Efectos IO/red/clock/hash/UUID en intérpretes/puertos. Persistencia de intención antes de IO remota; rechazo tipado y transporte diferenciado; separar ADTs `Command`, `Admission`, `Dispatch`, `Observation`, `Outcome`, `Cancellation`.
- No reusar identidades de ámbitos distintos y no crear `nullable`/`Boolean` cuando 3+ estados requieren ADT. Evitar generalidad especulativa y no añadir librería FP sin beneficios medidos.
- Refactor-safe tests: validar garantías, no conteos de archivos/orden interno/strings log.

## Handoff entre repos y agentes

- Una sola rama/owner por superficie. Fabric core/codec/client se construye y publica antes de pin Studio. Cambios ABI PK solo por consumidor demostrado; integrar y publicar upstream primero.
- Handoff mínimo: firma/version/proto hash, contraejemplo test, semántica de rechazos, SHA commit/artefacto remote, compatibilidad N/N-1, propietariado de contrato, siguiente acción. No sincronización por mensajes verbales.
- Agent teams permitidos solo en partes disjuntas; ver `FABRIC_AGENT_TEAMS.md`. No merge de agents con HEAD drift.

## Cierre de hito

1. Demostrar recorrido del usuario y AAT que de verdad fallaría con el defecto.
2. Ejecutar suite afectada, merge gate y full suite/integ. real conforme autoridad existente. Registrar `NOT_RUN/BLOCKED` sin inventar PASS.
3. Decidir tests permanentes mínimos y eliminar temporales de `$TMPDIR` tras registrar evidencia.
4. Integrar a `origin/main` con commits Conventional Commits y permisos adecuados; verificar HEAD remoto y tag no reutilizado.
5. Publicar release de binarios/SDK en canal real, descargar y verificar **completitud + integridad** y ejecutar consumidor limpio. `gh release create` solo tras autorización del operador tal y como indica `ROADMAP.md`.
6. Actualizar `ROADMAP.md` **solo estado del bloque que realmente cerró**, conservar trazabilidad y dejar siguiente tarea sin ambigüedad. Estado SDDK y logs pesados fuera del repositorio.

## Política antibucles

Dos iteraciones sin efecto verificable nuevo (diff útil, prueba causal, merge o artefacto): `PAUSED`, informar bloqueo único con razón y siguiente acción concreta. No volver a correr la full suite para diagnosticar un fallo unitario ni dedicar ciclos a scripts históricos sin valor.
