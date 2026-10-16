# Índice ADR — PipelineK Studio

Los ADR de este directorio fijan decisiones de Studio, no cambian las autoridades del upstream. Todas las decisiones se validan en implementación por fitness tests. Una ADR aceptada no certifica código ni exime de UAT.

| ADR | Estado | Decisión |
|---|---|---|
| ADR-001 | ACCEPTED | Studio web local React+Monaco, backend headless Kotlin |
| ADR-002 | ACCEPTED | Un motor PipelineK; ejecución JVM separada |
| ADR-003 | ACCEPTED | Dos SQLite y separación Event/Output Plane |
| ADR-004 | ACCEPTED | API HTTP canónica + adaptadores MCP/CLI |
| ADR-005 | ACCEPTED | Source snapshots inmutables y runs idempotentes |
| ADR-006 | ACCEPTED | Análisis estático ≠ evaluación de DSL |
| ADR-007 | ACCEPTED | Seguridad, profiles y autorización fail-closed |
| ADR-008 | ACCEPTED | Plugin metadata primero, carga de clases aislada |
| ADR-009 | ACCEPTED | Resultados tipados genéricos; especializaciones futuras |
| ADR-010 | ACCEPTED | Detekt adapter; LSP spike y fallback |
| ADR-011 | ACCEPTED | Local CI y release por bloques integrada en main |
| ADR-012 | ACCEPTED | Compatibilidad VS Code por contratos, sin UI acoplada |
| ADR-013 | ACCEPTED | SSE Tree-Events contract para la extensión VS Code (R5 EVO-C.0) |
| ADR-014 | ACCEPTED | Fabric como único backend de ejecución de Studio (F4 Post-Bump Part 10) |
| ADR-015 | PROPOSED | Identidades separadas (StudioRunId/StartRunId/FabricRunId/ProducerRunId) y `UnknownEffect` (F-delta) |
| ADR-016 | PROPOSED | Fabric autoridad de hechos y salida; Studio solo presenta (F-delta) |
| ADR-017 | PROPOSED | Capacidades reales del worker, no exploración local de JAR (F-delta) |
| ADR-018 | PROPOSED | UAT/AAT/E2E de ciclo con `delivery-first-testing` como router (F-delta) |
