# ADR-014 — Fabric es el único backend de ejecución de Studio

**Estado:** ACCEPTED (decisión ratificada y registrada en F4 Post-Bump Part 10, 2026-10-16).
**Fecha:** 2026-10-16.
**Proyecto:** PipelineK Studio.
**Transición:** este ADR precisa la topología de `ADR-002-single-runtime-runner.md` (un motor, ejecución JVM separada) hacia "Fabric como único backend de ejecución de Studio".

## Contexto

`ADR-002-single-runtime-runner.md` protege la unicidad de PipelineK, pero permite que Studio lance un proceso aislado. Ya existe `FabricClient` gRPC con `start/cancel/read/watch` y el motor/worker Fabric. Mantener los dos ejecutores permitiría deriva de semántica, dobles escritores y duplicación de recovery, credenciales y logs. El F-delta F0–F4 (`docs/pipelinek-studio-fabric-delivery-2026-10-16/overlays/pipelinek-studio/docs/04-roadmap/FABRIC_EXECUTION_DELTA_2026-10-16.md`) cierra la cadena con esta decisión.

## Decisión

Studio conserva editor/experimentos/snapshots/escenarios/clientes. **Fabric** es propietario de admisión, agenda de ejecución, workers, recuperación, hechos, consola y resultado; **PipelineK** es propietario del runtime y su semántica. En producción Studio no crea procesos PipelineK ni escribe `run.db`. Un `StudioFabricGateway` (puerto aplicación) no importa gRPC; el adapter `FabricClient` es su única implementación productiva. Fabric inaccesible = indisponibilidad explícita. No fallback local.

## Alternativas rechazadas

1. Dos backends local/Fabric intercambiables en Studio: duplica oráculos y topologías, crea divergencia.
2. Cliente generado Protobuf en cada interfaz: filtra infraestructura a UI y duplica codecs.
3. Mover lógica de Studio a Fabric: acopla plataforma a casos de UX, invierte ownership.

## Consecuencias y migración

- Sustituir gradualmente `PipelineRunnerPort`, `PipelineKRunReadPort`, `PipelineKOutputReadPort` por el gateway, y retirar los antiguos cuando no tengan consumidores. Estado actual (F4 Post-Bump Part 11, commit `5d2b48d`): el gateway canónico `StudioFabricGateway` existe en `studio-application/ports/Ports.kt`; el adapter `StudioFabricGatewayAdapter` en `studio-infra-fabric` lo implementa delegando a `FabricRunnerPort` (H2.2c). El `RuntimeBackendSelector` decide qué backend sirve cada submit; la rotación por defecto a Fabric es operator-gated (B0/B1).
- Mantener lectura legacy de metadatos históricos (`runDbRef/controlRootRef` en `RunBinding`) solamente si tiene uso probado.
- Coordinar nuevos RPC con Fabric y su roadmap; tests legales de arquitectura deben impedir `ProcessBuilder`/escritura en `run.db` bajo paquetes Studio.

## Leyes

A. Un evento final solo procede de observación Fabric.
B. Fallo TLS no activa runner local.
C. No aparece ninguna dependencia de Fabric en `studio-domain`.
D. Un run real muestra el mismo `FabricRunId` vía Web/CLI/MCP.

**Referencias:** `../../ROADMAP.md`, `../01-architecture/ARCHITECTURE.md`, `ADR-002-single-runtime-runner.md`, `ADR-003-sqlite-output-authorities.md`, Fabric `adapters/control-api/client`, `docs/04-roadmap/SFW_H1_RELEASE_INVENTORY.md`, F-delta F0–F4.
