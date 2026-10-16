# Delta ejecutable F0–F4: Fabric como **único** motor de Studio

**Carácter:** complemento de `ROADMAP.md` (R0–R6), nunca su sustituto. El primer hito pendiente en la agenda existente conserva prioridad salvo ratificación explícita de reordenación por el operador. Esta secuencia F es una cadena de **dependencias de integración** que cierra los huecos R0.3/R1/R2/R3/R4/R5/R6 relacionados con Fabric; no reabre funcionalidades históricas ya entregadas. No reciclar tags publicados ni deducir DONE de receipts previos.

## Resultado demostrable

Un usuario edita en Studio, sella `SourceSnapshot` y pulsa ejecutar. Fabric verifica los bytes exactos, registra una sola admisión, usa su worker PipelineK, expone salida y hechos auténticos, acepta cancelación y conserva identidad/procedencia; web, CLI, MCP y VS Code comparten la misma API Studio. Tras reiniciar Studio, la ejecución se recupera consultando Fabric. **Nunca** existe un runner de Studio paralelo.

## TODO de hitos de valor (cada uno termina en release remota)

- [ ] **F0 — Fabric fiable como producto consumidor, sin Studio en el hot path**. Resolver doble lanzamiento en `RunRegistry`/`LocalRunControl` (idempotencia efecto, no solo ID), parámetros de `StartRunRequest`→`WorkerWork`→PipelineK, cancelación sin evento sintético y certeza de estado `Queued/Starting/Running/Lost/Terminal`. Reproducir por socket real, dos llamadas concurrentes y timeout/reinicio. Publicar release de Fabric con `FabricClient`/contratos JVM resolubles en Maven; validar desde un cliente externo limpio. PK solo se república si ABI genérica cambia. **Entregable:** `StartRunId` repetido ejecuta una sola vez y preserva un solo `RunId`. **Integración:** `origin/main` Fabric, tag nuevo, Maven+release, consumer smoke remoto. **Refs:** Fabric `ROADMAP.md` J1/J2/J3; `docs/decisions/ADR-0016...`, `docs/fabric-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`. **Aceptación:** F0 UAT/AAT en `docs/05-testing/FABRIC_EPHEMERAL_ACCEPTANCE.md`.
- [ ] **F1 — Snapshot ejecutable y verificable end-to-end**. Proponer/enlazar `PutSnapshot`/`PreparePipeline` gRPC con digest + deduplicación + autorización y catálogo durable; `StartRun` sigue siendo el comando de ejecución. Mantener un solo owner del catálogo y del contenido; resolver TOCTOU, reanudación y limpieza GC. Implementar `FabricExecutionGateway` y headless Studio: crear snapshot, preparar, arrancar y consultar desde **CLI y HTTP/MCP con un Fabric real**. Persistir relación Studio↔Fabric y resolver timeouts sin duplicados. **Entregable:** el script exacto de Studio se ejecuta sin compartir disco con Fabric. **Integración:** release Fabric primero y Studio después, par certificado cuando cambia protocolo, artefactos remotos instalados. **Refs:** Studio `ADR-005`, `INTEROPERABILITY_AND_VERSIONING`; Fabric `PAIR_RELEASE_FLOW`, `PipelineCatalog`, `RunAdmission`. **Aceptación:** F1 UAT/AAT/E2E.
- [ ] **F2 — Consola real y control desde web/VS Code/MCP**. Enrutar todas las interfaces Studio por el gateway; retirar lectores ad hoc y lanzamiento/cancelación local; separar avisos SSE de cursores/bytes Fabric. Verificar `ObserveRun` con stage observable **antes del terminal**; si no, abrir cambio incremental Fabric→PK con publicación versionada, o etiquetar explícitamente no-live. Leer consola usando `ConsoleRef`/byte offsets y reconectar desde token emitido por Fabric. Mostrar intención de cancelación y resultado por separado. **Entregable:** ejecutar, cancelar y reconectar sesión real desde navegador/VS Code/CLI. **Integración:** Fabric si cambia contrato, Studio release instalada y descarga remota. **Refs:** Studio `ADR-003`, `ADR-013`, `HTTP_AND_STREAMING_API`; Fabric `ProjectionSource`, `ConsoleSource`, `ADR-0011`, `ADR-0023`. **Aceptación:** F2 UAT/AAT/E2E.
- [ ] **F3 — Funcionalidades de Studio sobre capacidades reales de Fabric**. Conservar experimentos, escenarios, variantes, Plugin Lab, MCP y diagnóstico en Studio; catalogar pasos efectivos, SDK/runtime instalado en worker y protocolos soportados desde Fabric. Añadir `GetRunProjection` **solo si** su ausencia obliga a duplicar el fold de eventos; snapshot completo + delta incremental. Demostrar `ResolveInteraction`→respuesta efectiva al worker o marcar capacidad `UNSUPPORTED`, no pintar una pregunta como resuelta. Comparación de variantes por `FabricRunId`/snapshot digest, sin mezclar `StudioRunId`. **Entregable:** explorar resultado y comparar dos ejecuciones reales, con degradaciones visibles en capacidades no soportadas. **Integración:** releases necesarias y certificación remota. **Refs:** Studio `STEP_PLUGIN_LAB`, `MCP_AND_AGENT_PROTOCOL`, `ANALYSIS_DETEKT_LSP`, ADR-006; Fabric `ReadModels.kt`, `FabricClient.kt`. **Aceptación:** F3 UAT/AAT.
- [ ] **F4 — Retirada de duplicación y distribución real**. Eliminar código local sin consumidores (`PipelineKRunner`, synthetic event writer, adapters regex/run.db, probes por JAR), migrar datos antiguos `runDbRef/controlRootRef` preservando lectura legacy solo si hay datos que salvar; reducir el harness sin perder garantías críticas, corregir UAT falsos verdes/scripts anclados a RC antiguas y dividir gates en feature/merge/integration/release. Aplicar `delivery-first-testing` y `distribution-first-release`; publicar binarios reales y Maven SDK (no source.zip como producto). **Entregable:** instalación limpia Studio+Fabric/PK de versiones certificadas, un recorrido completo y recibo con inventario remoto íntegro. **Integración:** release Studio, Fabric solo si hay cambios; verificar hash, coordenadas Maven, artifacts y smoke instalado. **Refs:** Studio `RELEASE_AND_INTEGRATION_PROTOCOL`, `UAT_CATALOG`, `AAT_AND_FITNESS`; Fabric `PAIR_RELEASE_FLOW`. **Aceptación:** F4 E2E release, AAT seguridad y regresión.

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
