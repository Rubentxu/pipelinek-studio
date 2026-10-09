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
