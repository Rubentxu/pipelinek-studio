# Interoperabilidad, compatibilidad y contratos externos

## Versiones distintas y explícitas

- `studioVersion` = SemVer de producto (tags del repo Studio).
- `studioApiSchema` = `studio.pipelinek.dev/api/v1` + compatibilidad de wire.
- `mcpProtocolVersion` = revisión oficial negociada, inicialmente 2026-07-28 (sin inferencia por npm package).
- `pipelinekRuntimeVersion` = versión manifest real, artifact digest y Git SHA si está disponible verificablemente.
- `pipelinekPluginApiVersion` = API del SDK de plugins publicada, separada del app version.
- `compilerProfileDigest` = fuente + toolchain + opciones reales + classpath de plugins efectivamente admitidos, respetando precedencia; no usar orden alfabético como identidad de classpath.
- `catalogDigest` = identidad canónica de membresía y metadatos del catálogo, no autorización de invocación.

## Compatibilidad

SemVer y cambios de API: añadir campos opcionales a v1; cambios semánticos incompatibles exigen `v2`, migration fixtures y ventana de compatibilidad. Desconocido de `enum` del runtime = `UNKNOWN` o rechazo tipado; clientes antiguos no asumen PASS. Cliente TypeScript generado de OpenAPI; test de contrato Web/MCP/CLI. El API de Studio no transporta internals del JVM/serialización Kotlin directamente.

## Upstream version floor

Inicio del proyecto: verificar SHA S6/main activo, especialmente manifiestos pre-load y Step SDK. Fijar dependencia en artefacto **publicado** con coordenadas/sha256 y no en el workspace local implícito. No consumir builds MavenLocal como evidencia de release final (es apropiado solo para iteración etiquetada como tal).

## Seam VS Code

VS Code futuro consume `studio-api` y, cuando madure, server LSP; la UI Web no se reutiliza como dependencia central. Un cliente de conformidad `headless` y otro de VS Code mock shell comparten OpenAPI + estados. En S5 implementar una mini-extensión o un segundo cliente real que haga discovery, snapshot, run, output y cancel; no crear IDE completo.

## Plugins externos y futuras vistas

`ResultDescriptor` y `ArtifactReference` versionados; extensiones futuras pueden aportar `viewKind`, `schemaId`, `relatedResources`, `sourceRanges`, `evidenceRefs` (datos serializables, no ejecutables). Studio conserva fallback JSON/texto cuando no conoce `viewKind`. Policies y Assurance son **future work**; en S0–S5 ninguna dependencia de sus clases, plugins o DSL.

## Política de dependencias

Escoger versiones Kotlin, Gradle, Ktor, React, Vite, Monaco, SQLite JDBC y MCP desde matriz de compatibilidad y release builds verificables. No forzar seguir latest sin validar con JVM21/Kotlin2.4+ baseline de PipelineK. Registry de dependencias con SHA de publicación/SBOM; no inventar versiones finales en docs.

## UAT/AAT

AAT-ABI-001..006, AAT-API-001..008, AAT-MCP-001..008, UAT-039, UAT-040; analizar incompatibilidad entre runtime B y plugin compilado A, adaptar diagnóstico y bloquear ejecución antes de efectos.
