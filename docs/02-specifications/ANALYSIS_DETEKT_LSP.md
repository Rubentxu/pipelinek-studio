# Análisis: Kotlin, Detekt, IR y LSP

## Categorías y autoridad

`KOTLIN_COMPILER` diagnostica errores Kotlin y tipos; `DETEKT` emite hallazgos estáticos configurables; `PIPELINEK_DSL` proyecta el IR y errores de construcción/admisión; `RUNTIME` explica consecuencias ejecutadas. No convertir un warning en rechazo de capacidades ni `INCONCLUSIVE` en PASS.

## Roadmap S3

**S3.A Diagnósticos fiables:** normalizar `Diagnostic { sourceDigest, phase, origin, code?, severity, message, location?, related?, toolVersion, classification }`; no inventar rangos si el backend no proporciona. Diferenciar compilación a IR y evaluación (Kotlin host actual mezcla ambas). Cuando el source map no sea 1:1, comunicar `locationStatus=UNMAPPED`.

**S3.B Detekt:** ejecutar Detekt en proceso aislado con versión compatible con Kotlin de PipelineK; baseline verificado en upstream `2.0.0-alpha.6` para Kotlin 2.4.10 (puede cambiar). Compatibilidad de versión asegurada por matrix test, no por documentación. Analizar `.pipeline.kts` con fixture y descartar reglas que requieren type resolution cuando no se tiene classpath correcto, con `analysisCapability=SYNTAX_ONLY`. Plugins Detekt personalizados pueden ejecutar bytecode; aislamiento como Step externo.

**S3.C Rules PipelineK:** primera familia pequeña con hechos comprobables y coste reducido:
- `PKL-001` invocación DSL de API deprecada cuando la metadata real lo indique;
- `PKL-002` Step no resoluble por registry del perfil activo (diagnóstico semántico *del catálogo*, no regex);
- `PKL-003` llamada destructiva sintácticamente peligrosa como *warning* (la admisión real decide);
- `PKL-004` return value discarded si compiler+metadata permiten caracterizar sin duplicar `-Xreturn-value-checker` del host.

Regla con type resolution nunca se activa en modo syntax-only como si tuviera precisión. Antes de cada regla: caso positivo, negativo, mutation/killed y sample con nombre textual parecido para evitar falso positivo.

**S3.D IR Explorer:** el IR canónico ya compilado proyectado en `StageNode`, `StepNode`, `BlockStepNode`, `SourceDescriptor` y catálogo, sin reimplementar parser/renderer de Kotlin ni exponer objeto JVM. Anotar cada elemento `declared|planned|admitted|observed` y su origen.

**S3.E LSP spike:** comparar Kotlin LSP existente, `monaco-languageclient`, custom `.pipeline.kts`, imports/classpath de plugins externos y documentos virtuales. Si falla, mantener las capabilities implementadas y emitir `LSP_UNSUPPORTED` hasta spike posterior. No escribir LSP propio completo ni aceptar inferencias por nombre de Step. Registrarlo como decisión con benchmark/documented limit.

## Contratos de salida

```json
{
  "analysisId": "an_42",
  "snapshotId": "snap_1",
  "analyzer": "DETEKT",
  "analyzerVersion": "2.0.0-alpha.6",
  "typeResolution": "UNAVAILABLE",
  "status": "COMPLETE",
  "diagnostics": [
    {"code":"PKL-003","severity":"WARNING","phase":"STATIC_LINT","message":"Revisar la intención destructiva y el perfil de workspace","location":{"line":4,"column":7}}
  ]
}
```

Propuesta wire, no salida real medida. `SOURCE_COMPILER` y `STATIC_LINT` no usan mismo error-code namespace. `analyzerVersion` debe reflejar la versión real, no hardcodear.

## Dependencias y fuentes

- Detekt extensiones oficiales: https://detekt.dev/docs/introduction/extensions/ ; type resolution: https://detekt.dev/docs/gettingstarted/type-resolution/ .
- `docs/v2/03-specifications/SCRIPTING_COMPILER_SPEC.md`, `docs/pipelinek-v1-to-v2-evolution-bundle/specifications/07-introspection-diagnostics-lsp.md`, `docs/v2/04-adrs/ADR-0024-lsp-metadata-json-schema.md` del upstream (rutas confirmadas).
- El ADR de introspección es especificación de evolución; no concluir implementación por el hecho de existir.

## AAT UAT

UAT-030..034, AAT-AN-001..008. Canario imprescindible: dos scripts de sintaxis válida, uno con side-effect en top level, otro con DSL plugin que lee fichero en build. Un comando que se anuncia como `static analyze` no puede alterar canarios ni llamar Step handler. Mutaciones que transformen `analysis` en `host.compile()` deben matar el gate.
