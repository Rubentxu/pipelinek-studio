# ADR-001 — Studio web local + Kotlin headless

**Estado:** ACCEPTED (diseño propuesto; implementación pendiente) · **Fecha:** 2026-10-09 · **Proyecto:** PipelineK Studio.

## Contexto

El destino es reproducir el bucle interactivo de Groovy Console y elevarlo a Steps, plugins y runs. Existe tentación de arrancar en Theia, IntelliJ o en una extensión VS Code completa; las tres introducen complejidad de plataforma antes de validar el laboratorio.

## Decisión

Frontend React + TypeScript + Monaco servido localmente; backend Kotlin/JVM separado como API headless. No portar DSL ni ejecutar Step handlers en TS. Monaco es componente de edición, no fuente de semántica. La UI VS Code es una posibilidad posterior.

## Alternativas y trade-offs

Alternativas: Theia (gran superficie inicial), IntelliJ (integración Kotlin excelente pero menos autónomo), Tauri (packaging opcional), plugin VS Code-first (limita UX especializada). Elegir Chrome/Web local-first ahora no descarta clientes futuros.

## Invariantes verificables / criterios de invalidez

Caso vertical web y cliente no web usan el mismo endpoint y producen el mismo outcome. UAT-001/039; AAT-API-001/002.

## Implicaciones para agentes

El agente debe verificar antes de implementar que el contrato upstream sigue existiendo en el SHA que consume, producir una prueba que distinga la decisión correcta de un falso positivo, documentar cambios de alcance sin alterar el sentido de la ADR y abrir nueva ADR si una medición real la contradice. Nunca modificar arquitectura de PipelineK upstream por comodidad de Studio ni convertir el presente diseño en certificado de compatibilidad.

## Referencias

`../01-architecture/ARCHITECTURE.md`, `../../ROADMAP.md`, `../05-testing/AAT_AND_FITNESS.md`, `../07-research/UPSTREAM_GROUNDING.md`.
