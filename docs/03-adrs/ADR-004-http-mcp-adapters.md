# ADR-004 — API de aplicación canónica, HTTP + MCP + CLI

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

El agente necesita invocar funcionalidades sin navegador. Un MCP que implementase directamente negocio en Node divergiría de Ktor y abriría bypass de permisos.

## Decisión

Kotlin application layer define casos de uso; Ktor HTTP/JSON + SSE los expone; MCP TS v2/stdio traduce a HTTP, CLI thin client hace lo mismo. `studio.discover` y GET /api/v1 responden a un modelo común de affordances verificadas.

## Alternativas y trade-offs

Hay coste pequeño de transporte HTTP entre MCP y backend, a cambio de contratos portables, separación de privilegios y continuidad hacia VS Code. MCP Tools no son Steps y no crecen por plugin.

## Invariantes verificables / criterios de invalidez

Paridad de requests/errores por dos clientes, stdout MCP limpio, protocol-wire 2026-07-28. UAT-013, AAT-MCP-001..008.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
