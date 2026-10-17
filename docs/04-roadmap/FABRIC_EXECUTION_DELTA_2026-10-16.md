# Delta ejecutable F0–F4: Fabric como **único** motor de Studio

**Carácter:** complemento de `ROADMAP.md` (R0–R6), nunca su sustituto. El primer hito pendiente en la agenda existente conserva prioridad salvo ratificación explícita de reordenación por el operador. Esta secuencia F es una cadena de **dependencias de integración** que cierra los huecos R0.3/R1/R2/R3/R4/R5/R6 relacionados con Fabric; no reabre funcionalidades históricas ya entregadas. No reciclar tags publicados ni deducir DONE de receipts previos.

## Resultado demostrable

Un usuario edita en Studio, sella `SourceSnapshot` y pulsa ejecutar. Fabric verifica los bytes exactos, registra una sola admisión, usa su worker PipelineK, expone salida y hechos auténticos, acepta cancelación y conserva identidad/procedencia; web, CLI, MCP y VS Code comparten la misma API Studio. Tras reiniciar Studio, la ejecución se recupera consultando Fabric. **Nunca** existe un runner de Studio paralelo.

## Estado verificado del código (2026-10-17; consultar HEAD antes de ejecutar)

- Studio `origin/main@6bed3390d70e`: P0-03 (identidad `StartRunId` durable) y P0-04 (cancelar por `FabricRunId` persistido) **implementados en módulos y contrastados por pruebas de adaptación/almacenamiento**, pero sin UAT de Fabric gRPC instalado. Ver `odd/tasks/studio-fabric-p0.md`, `SqliteFabricStartIntentStore`, `FabricRunnerAdapter`. **No reimplementar estos dos trabajos**; someterlos a prueba de consumidor real.
- Fabric `origin/main@b1cdf6208829`: `LocalRunControl.start` ya usa `tails.putIfAbsent(runId, sink)` para impedir doble programación en el **mismo proceso**; el claim no es durable y no certifica reintentos tras crash/reinicio. `RunRegistry.start` deduplica el registro, no demuestra por sí solo un único efecto.
- **Bloqueador de compilación/ABI publicado:** `FabricRunnerAdapter` sigue instanciando `StartRunRequest(packageDigest, studioRunId)` y manejando `Rejection.PackageTooLarge/PackageDigestMismatch/AlreadyExistsWithDifferentBytes`; ni el código publicado en `v0.5.0-R1-alpha.8` ni el `main` Fabric revisado exponen esos campos/variantes en sus contratos Kotlin. Studio fija `fabric=0.5.0-R1-alpha.8` y `pipelinek=0.48.0-rc1` en `gradle/libs.versions.toml`. Los JAR de `mavenLocal()` pueden dar un falso verde de compilación si no corresponden al tag.
- Ambos arranques públicos Studio (`studio-app/Main.kt` y `studio-cli/ServerStart.kt`) instancian `PipelineKRunner`; no está demostrado el recorrido productivo Studio → FabricClient → socket gRPC → Fabric → PipelineK. `RemoteSubmitAdapter` todavía fabrica una ruta local `/tmp/fabric-...`.
- No reinterpretar `RunOutcome.Unstable` como `SUCCEEDED`, `RunState.Waiting` como `RUNNING` ni `Interrupted` como cancelación del usuario; `FabricFactReadSource` todavía pierde los campos tipados al emitir `payload = emptyMap()`.
- La release pública `v1.0.0` de Studio (16/10/2026) solo acredita su asset explícito `studio-cli-standalone-1.0.0.tar` anterior a varios commits de `main`; la publicación local Maven de los SDK **no equivale a distribución remota**. Ningún punto pendiente se marca DONE por el tag existente.

**Autoridades:** raíz `ROADMAP.md` (Studio R0–R6); este delta F0–F4 no altera prioridades. Fabric `ROADMAP.md` y `ROADMAP-CONTINUATION.md` (Jenkins J0–J8 y bloques A–H), `docs/fabric-coordinated-evolution/delivery-first/ROADMAP-ADDENDUM.md`. Reutilizar documentos y ADR existentes; no generar otros planes paralelos. Si hay conflicto de agenda, registrar decisión en SDDK antes de reordenar.

## TODO integrado por hitos largos de valor (cada uno con release remota)

### F0 — Fabric inicia **una vez** y el SDK se consume desde remoto (P0; ruta crítica)

**Valor para el usuario:** un cliente gRPC puede iniciar un pipeline PK real, repetir el comando, recuperarlo y cancelar sin duplicar efectos.

- [x] Registro idempotente de comandos en `RunRegistry` y deduplicación `putIfAbsent` de cola en el **mismo proceso** (implementación visible; **no** equivale a E2E certificado).
- [ ] Resolver claim de lanzamiento y recuperación **durables** en Fabric. El mismo `StartRunId` y payload, incluso tras SIGKILL/reinicio, conservan un único `FabricRunId` y no generan segundo efecto. No relanzar automáticamente si se desconoce si hubo efectos.
- [ ] Transportar `RunParameters` a `WorkerWork` y PipelineK, con valores comprobados en el resultado real; no introducir secretos persistentes en ADTs o trazas. Inspeccionar la DSL compatible publicada de PK antes de fabricar fixtures.
- [ ] Certificar la biblioteca oficial `FabricClient` y contratos públicos con dependencias resueltas **sin `mavenLocal`**, identidad de versión/digest y compatibilidad del worker; no prometer Maven externo sin comprobar que existe.
- [ ] **Aceptación efímera única:** un consumidor externo abre socket gRPC a Fabric instalado, arranca el mismo comando dos veces y una vez tras reinicio, observa un único efecto y parámetros verdaderos; AAT con payload diferente y pérdida del ack. Guardar 1–2 leyes permanentes pequeñas de claim y replay, retirar los escenarios temporales.
- [ ] **Cierre:** PR integrado en `origin/main` Fabric; nuevo tag/release Fabric, SDK Maven si es producto público y smoke descargando artefactos remotos. Pareja PK↔Fabric según `PAIR_RELEASE_FLOW` si cambia la interfaz. No esperar Jenkins J8 para verificar un **cliente genérico** ya expuesto, ni declarar B2 Backstage iniciado.

**Equipo paralelo:** A (Fabric admisión/claim, dueño de `LocalRunControl`); B (semántica worker/params, sin editar el mismo archivo); C (SDK/distribución/consumidor limpio). Un integrador valida ABI + release. **Refs:** Fabric `ROADMAP.md` J1/J2/J3, `ROADMAP-CONTINUATION.md` bloque 0, `docs/decisions/ADR-0016-run-outcome-is-one-concept.md`, `docs/fabric-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`, `docs/fabric-coordinated-evolution/coordination/PAIR_RELEASE_FLOW.md`.

### F1 — Ejecutar el **snapshot exacto** de Studio en Fabric (P0; depende de F0)

**Valor:** usuario crea y ejecuta en Fabric un `.pipeline.kts` sellado sin compartir carpetas ni adaptar scripts manualmente.

- [x] Studio persiste la identidad de comando y rechaza contradicciones en su almacenamiento, y resuelve la identidad correcta para cancelar (implementado, **pendiente de validación remota**).
- [ ] Definir y publicar en Fabric la capacidad mínima de preparar fuente/snapshot por bytes UTF-8 y SHA-256, almacenar inmutable, autorizar acceso y devolver clave opaca del catálogo; reutilizar `StartRun` para ejecutar. No incluir campos `packageDigest/studioRunId` en Studio contra un contrato que no los publica.
- [ ] Publicar codec/proto/`FabricClient` compatibles; corregir `StartRunRequest` del consumidor Studio, fijar nuevas coordenadas reales y asegurar build limpio sin artefactos Maven locales de otro commit.
- [ ] Conectar el gateway a los casos de uso públicos Studio de **crear snapshot → preparar → arrancar → consultar/cancelar**; persistir `StudioRunId`, `StartRunId`, `FabricRunId`, `ProducerRunId` separados. Rechazar toda ruta `/tmp/fabric-...` simulada, inconsistencia de digest o efecto incierto.
- [ ] **UAT/E2E efímero compartido:** CLI/HTTP/MCP contra Studio instalado, Fabric gRPC real y worker PK auténtico, ejecutar el mismo código sellado, verificar digest, stdout y run ID; AAT hash falso/reinicio/timeout. Retirar pruebas temporales tras decidir regresión de integridad.
- [ ] **Cierre:** PRs por dueño, Fabric release **antes** de Studio si cambia contrato, `origin/main` verificado, nueva release Studio y smoke consumidor remoto. Si no existe RPC publicado, `BLOCKED`, nunca fake PASS.

**Equipo paralelo:** Fabric almacenamiento/CAS (A), proto/client/ACL (B, distinto módulo), Studio núcleo puro y persistencia (C); Studio adapter/boot (D) solo después del contrato publicado. **Refs:** Studio `docs/02-specifications/FABRIC_INTEGRATION_SPEC.md`, `docs/03-adrs/ADR-005-snapshots-idempotency.md`, `docs/03-adrs/ADR-014-fabric-only-execution-gateway.md`, `docs/03-adrs/ADR-015-run-identities-and-unknown-effects.md`; Fabric `docs/fabric-coordinated-evolution/coordination/PAIR_RELEASE_FLOW.md`.

### F2 — Consola, cancelar y reconectar mediante Fabric real (P1; depende de F1)

**Valor:** Web/VS Code/CLI/MCP muestran stages, logs y resultados correctos; cerrar el navegador no cancela el pipeline.

- [ ] Unificar observación HTTP/MCP/UI sobre `FabricClient`; separar `FactCursor`, `ConsoleRef` + byte offset y `Last-Event-ID`. `SSE` avisa de cambios, nunca pretende ser el journal.
- [ ] Conservar el payload tipado de `ProjectionFact` (eliminar `payload = emptyMap()`) y resultados `Unstable`, `Waiting`, `Interrupted` como variantes distintas; no declarar terminal por `cancel` aceptado ni por red desconectada.
- [ ] Comprobar **observación live antes del terminal**; si Fabric entrega hechos solo al concluir, corregir el producer/adapter versionadamente o mostrar `LIVE_UNSUPPORTED`, sin dar verde a polling postmortem.
- [ ] Retirar `run.db`/stdout y cancelación sintética de los consumidores nuevos; hacer prueba real de TLS en **ambos** ejecutables Studio antes de afirmar transporte seguro.
- [ ] **UAT/E2E efímero:** pipeline con dos etapas y pausa observable, dos lectores, reconexión a cursores y cancelación, con byte UTF-8 partido; AAT `ForeignConsole`, pérdida de red y rechazo de versión. Retirar scripts de ciclo tras evidencia y conservar solo conformance crítica.
- [ ] **Cierre:** integrar `origin/main` en repos tocados, release Studio y Fabric solo si cambia API/producer, verificación instalada con cliente browser/VS Code; no declarar `live` sin prueba anterior al terminal.

**Equipo paralelo:** Fabric observación (A), HTTP/MCP (B), Web/VS Code (C), integración/smoke (D). **Refs:** Studio `docs/03-adrs/ADR-003-sqlite-output-authorities.md`, `docs/03-adrs/ADR-013-sse-tree-events.md`, `docs/03-adrs/ADR-016-fabric-observation-and-console.md`, `docs/02-specifications/HTTP_AND_STREAMING_API.md`; Fabric `docs/decisions/ADR-0011-fact-read-contract.md`, `docs/decisions/ADR-0023-the-event-read-is-the-cursor-the-sdk-already-has.md`.

### F3 — Escenarios, Plugin Lab y agentes basados en capacidades reales (P1; depende de F2)

**Valor:** comparar dos ejecuciones reales y conocer qué soporta el worker, sin duplicar el compilador ni inferir capacidades por JAR local.

- [ ] Recuperar `RunProjection` y capacidades publicadas por Fabric, únicamente cuando la API esté disponible; **no** duplicar su fold ni inventar step catalog/IR/diagnósticos. Si falta una capacidad, representar `Unsupported/Unknown` explícitamente.
- [ ] Reutilizar experimentos, escenarios, variantes y comparador Studio, usando snapshots digeridos, `FabricRunId` y fuente exacta; mostrar `Unstable` distinto de `Success`.
- [ ] Validar `ResolveInteraction` de extremo a extremo (Studio → RPC → worker → PK reanuda). Si la respuesta no alcanza el worker, mantener `UNSUPPORTED`.
- [ ] **UAT/E2E efímero:** dos variantes producen resultados distinguibles consultables por Web y MCP; AAT plugin ausente/versión desconocida o input no entregado. Solo promocionar pequeñas leyes de mapeo de capacidades y outcomes.
- [ ] **Cierre:** PR y nueva release Studio, releases Fabric/PK únicamente si cambian APIs públicas; prueba instalada con consumidor SDK real.

**Equipo paralelo:** Fabric capacidades (A), dominio de comparación (B), UI/MCP (C), con integrador independiente. **Refs:** Studio `docs/02-specifications/STEP_PLUGIN_LAB.md`, `docs/02-specifications/MCP_AND_AGENT_PROTOCOL.md`, `docs/02-specifications/ANALYSIS_DETEKT_LSP.md`, `docs/03-adrs/ADR-017-capability-truth-and-runtime-ownership.md`.

### F4 — Retirar deuda y distribuir el producto que se puede usar (P1; depende de F3)

**Valor:** una única vía de ejecución, SDK y binarios instalables sin checkout, con menos mantenimiento del harness.

- [ ] Medir consumidores reales antes de retirar: `PipelineKRunner`, escrituras de evento sintético, readers SQLite/JSON/regex, `RuntimeProbe`, selector local/Podman, dos interfaces equivalentes y rutas `controlRoot` ficticias. Preservar lectura legacy solo si hay datos migrables; **sin fallback productivo**.
- [ ] Reorganizar dependencias hacia `domain` → `application` → adaptadores; pasar decisiones a funciones puras exhaustivas con ADTs, estados imposibles no representables y side-effects solo en los intérpretes.
- [ ] Revisar falsos verdes de `scripts/e2e-host.sh` (RC antigua, marcador ausente tratado como OK) y `scripts/uat-from-distribution.sh` (409 SDK ausente etiquetado UAT completa), además de `run-all-uats.sh` (`sleep 30` y barrido indiscriminado). **Reemplazar antes de eliminar** cualquier garantía importante.
- [ ] Inventariar binarios/SDK publicables; publicar según canales reales y verificar `POM`/JAR/transitivos desde Maven remoto (no `mavenLocal`); instalación Studio, Fabric y PK desde tags/versiones compatibles, digests y completitud, no solo SHA de assets parciales.
- [ ] **E2E efímero de distribución:** descarga real, arranque, snapshot, ejecución gRPC, consulta, cancelación/recuperación, restart; AAT asset/JAR ausente, hash inválido y TLS inválido. Eliminar UAT/E2E/AAT temporales de este bloque y medir el coste final del harness.
- [ ] **Cierre:** integración `origin/main`, nuevo tag/release Studio con assets reales y consumer smoke, recibo mínimo de producto; Fabric/PK solo se republican si realmente cambian.

**Equipo paralelo:** arquitectura/retirada de adapters (A), economía del harness (B), distribución Maven/release (C), integrador final (D). **Refs:** Studio `docs/06-operations/RELEASE_AND_INTEGRATION_PROTOCOL.md`, `docs/05-testing/UAT_CATALOG.md`, `docs/05-testing/AAT_AND_FITNESS.md`, `docs/03-adrs/ADR-018-delivery-first-ephemeral-acceptance.md`; Fabric `docs/fabric-coordinated-evolution/coordination/PAIR_RELEASE_FLOW.md`.

## Ley de ejecución compartida para F0–F4

1. **Router real**: usar `Rubentxu/agent-skill/skills/delivery-first-testing/SKILL.md`, descubrir `references/lens-registry.json` y `references/navigation.md` del checkout instalado. Seleccionar solo lentes `ready`: `change-impact` + `ephemeral-uat` + `regression-promotion` para feature; `failure-triage` ante rojos; `merge-assurance`, `integration-assurance`, `release-assurance` en sus fases; `harness-economics` al retirar deuda. `planned` **no habilita** una prueba.
2. **Núcleo funcional puro**: `decide : State × Command → Decision` sin I/O; interpretar `Decision` a través de puertos para SQLite, red, reloj, procesos y secretos. ADTs exhaustivos `Accepted | Rejected | UnknownEffect`, `Queued | Running | Waiting | Lost | Terminal(outcome)`; preservación de `Unstable` e identidades diferenciadas. No reimplementar scheduler/recovery de Fabric en Studio.
3. **Ciclo temporal**: 1 UAT de consumidor, 1 E2E real de frontera si no coincide con UAT, 1 AAT negativa discriminante, en directorio `mktemp -d` fuera de Git, aislamiento y deadline por condición observable (sin sleeps fijos). Desarrollo: affected; merge: afectados + contrato; integración: full suite que exija roadmap actual; RC: producto instalado de commit/digest exacto.
4. **Borrado obligatorio de temporales**: antes de cerrar cada hito, registrar commit SHA, binario/digest, comando, resultado PASS/FAIL/BLOCKED, defecto, riesgos y decisión `PROMOTE | REPLACE | RETIRE | DEFER`; conservar la menor regresión permanente necesaria, en módulo propietario. Solo entonces borrar directorio UAT/E2E/AAT de sesión; sin borrar pruebas legacy de seguridad/integridad/recuperación sin prueba equivalente y autorización.
5. **Un solo integrador** por hito: compañeros en worktrees/archivos disjuntos, handoff por contrato y SHA, integración Git remota y release genuina para cada repositorio productivo modificado. Tag inmutable sobre main integrado, verificar artefactos remotos, instalación limpia. `gh release create` requiere autorización del operador (D-007). Si gate crítico falla `BLOCKED`; no prometer release ni avanzar.
6. **Antibucle**: ante fallo, una reproducción focal → causa según `failure-triage` → corrección/decisión. No crear 70 gates nuevos, no reiniciar full suite a cada iteración ni silenciar `UNKNOWN/SKIP` como PASS.

## Relación con agenda actual

| Delta | Tramo Studio R0–R6 que cierra | Tramo Fabric que respeta | No objetivo |
|---|---|---|---|
| F0 | R0.3/R6 calidad de admisión | J1/J2 como fiabilidad transversal; J3 Jenkins continúa prioritario | Reimplementar scheduler en Studio |
| F1 | R0.3/R1 ejecución headless | extensión northbound de J2, compatible con J3 | Abrir B2 Backstage antes de su secuencia |
| F2 | R1 + R5 conexión UI/VS Code | J2 observación/consola + CRIC-M1 cuando aplique | Escribir SQLite de PipelineK |
| F3 | R2/R3/R4 valor UI/agentes | B1 estabilización solo si su secuencia está habilitada; refactor aditivo pequeño antes con autorización | Duplicar SDK interno en Studio |
| F4 | R6 liberación robusta | J8 distribución sin declarar Jenkins COMPLETO prematuramente | 70+ certificaciones siempre activas |

## Protocolo de cierre innegociable

1. Criterios de consumidor con UAT efímero discriminante, una prueba negativa adversarial y leyes de dominio afectadas, sin inventar `PASS`.
2. `delivery-first-testing` `feature` → `merge` → `integration` → `release`; full suite amplia en integración/RC según política actual, NO por cada cambio interno.
3. PR corto con revisión de cambios reales; merge a `origin/main` de cada repo modificado y comprobación de SHA remoto, sin force push.
4. Se prepara un **nuevo** release por repo modificado después del gate; tag al SHA integrado, GitHub Release + Maven/OCI cuando corresponda, autorización de `gh release create` según política; confirmar inventario y checksums de artefactos reales.
5. Descargar en consumer limpio el binario/SDK publicado; si cambia PK↔Fabric aplicar protocolo de release pareja, si cambia Studio↔Fabric registrar matriz de pares compatibles y smoke real.
6. Eliminar scripts UAT/E2E/AAT **efímeros** al final de cada hito y registrar las evidencias mínimas (SHA, digest, oráculos, resultado, decisión PROMOTE/REPLACE/RETIRE). Mantener únicamente regresiones permanentes que protejan garantías críticas y aquellas que la skill aconseje PROMOTE.
7. Si cualquier gate no pasa: `BLOCKED`, preservar diagnóstico y abrir acción correctiva; **sin inventar release ni avanzar secuencia**.

## Glosario rápido

- **Snapshot sellado:** bytes exactos identificados por digest, inmutables.
- **Admisión:** Fabric acepta una solicitud y le asigna un `FabricRunId`, sin afirmar que ya se ejecuta.
- **Claim de lanzamiento:** permiso durable y único para ejecutar trabajo de un `FabricRunId`.
- **Hecho:** evento semántico reportado por Fabric, no texto stdout.
- **Proyección:** vista derivada de hechos por la autoridad de Fabric, no un journal paralelo de Studio.
- **Cursor:** posición y scope emitidos por una fuente para reanudar lectura sin pérdidas ni duplicados.
- **Paquete consumidor:** JAR, SDK o aplicación realmente instalable; un source archive GitHub no sustituye al producto.
