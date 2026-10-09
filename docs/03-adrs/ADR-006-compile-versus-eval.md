# ADR-006 — Análisis estático separado de Kotlin evaluation

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

`ScriptingHost.compile()` compila y evalúa; el CLI `validate` construye el DSL; un plugin puede leer ficheros durante construcción. No se puede etiquetar ese flujo como pure static.

## Decisión

Operaciones `STATIC_ANALYZE`, `DSL_EVALUATE`, `IR_INSPECT`, `RUN` distintas. Mientras compile-only realmente sin ejecución no exista, endpoint responde `UNSUPPORTED` y no afirma no-effects; el runner sí puede evaluar bajo perfil autorizado.

## Alternativas y trade-offs

Puede retrasar autocompletado profundo, pero impide violación silenciosa. Compilador, Detekt y plugin compiler se ejecutan en procesos de herramienta acotados; test de side effects siempre verde solo si realmente no ocurrieron.

## Invariantes verificables / criterios de invalidez

Canario `Files.writeString` en top-level y facade que lee ficheros: sin efecto en STATIC_ANALYZE, rechazo tipado. AAT-CPP-001..006.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
