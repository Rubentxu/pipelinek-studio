# ADR-012 — VS Code consume servicios; nunca componentes React como contrato

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

Se requiere una futura extensión sin rehacer backend. Si la lógica del producto vive en hooks React o WebView privadas será muy caro reutilizarla.

## Decisión

Toda semántica y casos de uso están en Kotlin application + OpenAPI, contrato MCP separado. Client TS generado sirve web y futuro VS Code. Pueden variar presentadores y LSP bridge, no la máquina de estados ni los Step schemas.

## Alternativas y trade-offs

Requiere tests contract-first y API estable antes del cliente externo. En S5 un cliente independiente prueba discovery, run, stream, cancel y error sin importar UI.

## Invariantes verificables / criterios de invalidez

UAT-039/040; AAT-API-007/008.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
