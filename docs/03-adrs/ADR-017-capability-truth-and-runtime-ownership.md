# ADR-017 — Capacidades reales del worker, no exploración local de JAR

**Estado:** PROPOSED, 2026-10-16.

## Decisión

Studio obtiene por Fabric catálogo, versión y digest de PipelineK, steps reales, plugins admitidos y capacidades opcionales (live observation, input/resolve, diagnostics, IR, analyze). `KnownUnsupported` y `UnknownCapability` son miembros distintos del ADT; la UI no muestra capacidad como disponible por hallar un nombre de clase en un JAR de Studio. Plugins/admisión real pertenecen a runtime/Fabric, mientras Studio conserva flujos UI de inspección y experimentación.

## Implicaciones

Nuevos contratos extensibles deben versionarse, incluir rechazo tipado y un E2E contra un worker con y sin capacidad. La publicación SDK de Fabric via Maven y la distribución PK fijan un par verificable. No duplicar StepRegistry ni examinar internals de PipelineK desde Studio.

**Refs:** `../02-specifications/STEP_PLUGIN_LAB.md`, `../02-specifications/INTEROPERABILITY_AND_VERSIONING.md`, `ADR-008-plugin-admission-first.md`, Fabric `ReadModels.kt`.
