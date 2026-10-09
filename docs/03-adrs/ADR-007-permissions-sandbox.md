# ADR-007 — Trust profiles y control de capacidades fail-closed

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

La funcionalidad principal ejecuta scripts del usuario y providers. El proceso JVM no limita red, filesystem ni subprocess por sí solo; los agentes incrementan superficie de entrada.

## Decisión

`managed-isolated` predeterminado solamente si sandbox OS queda certificado; si no existe, bloqueo de ejecución no confiable, con `trusted-local` opt-in claramente identificado. Mismas ACL web/MCP/CLI. Leases `Managed/Attached` de PipelineK se respetan; ninguna autorización se deduce de nombres de rutas.

## Alternativas y trade-offs

Puede restringir la primera adopción en kernels sin namespaces, pero evita prometer seguridad inexistente. Uso de secretos exclusivamente por handles y redacción verificada; Origin/Host, CSRF y protección de MCP HTTP opt-in.

## Invariantes verificables / criterios de invalidez

AAT-SEC-001..010: symlink, traversal, loopback, host-bound, env leakage, exit handler, workspace root delete, credential canary.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
