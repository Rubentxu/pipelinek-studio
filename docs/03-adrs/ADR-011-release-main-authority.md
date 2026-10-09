# ADR-011 — Main remoto y release real tras cada bloque de valor

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

Ramas prolongadas sin integración crean conflictos, tags divergentes y estado documental ficticio. Studio necesita releases continuas desde el primer prototipo.

## Decisión

Cada bloque S0..S5 se integra en remote main por cambio trazable, se reejecutan gates desde árbol limpio, se publica tag anotado de candidato sobre commit main, GitHub release con ZIP/binarios, sha256, SBOM, manifest y pruebas reales. GitHub Actions no es autoridad de CI.

## Alternativas y trade-offs

El ritmo puede ralentizarse ante fallos; eso es preferible a promociones falsas. No force push, no sobrescribir tags, no considerar test parcial certificación de producto. Bloque con gate fallido permanece abierto.

## Invariantes verificables / criterios de invalidez

AAT-REL-001..010, protocolo de releases y verificación SHA/artefactos por clon limpio.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
