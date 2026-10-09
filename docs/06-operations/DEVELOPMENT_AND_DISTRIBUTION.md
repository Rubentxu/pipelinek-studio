# Desarrollo, empaquetado y operación local

## Stack baselined

**Backend:** JVM 21+ compatible con PipelineK actual, Kotlin/JVM, Gradle, Ktor, JDBC SQLite y Testcontainers solo si aporta valor. Kotlin 2.4.10 y Detekt 2.0.0-alpha.6 aparecen en `pipeline-kotlin/main` investigado (2026-10-09); revalidar antes de pin.

**Frontend:** React, TypeScript, Vite, Monaco, cliente `OpenAPI` generado, `vitest`/Playwright para pruebas y `pnpm` con lockfile. Versiones concretas por matriz reproducible en S0, sin inventarlas en el blueprint.

**MCP:** TypeScript SDK v2 `@modelcontextprotocol/server` con v2 2026-07-28 `serveStdio`, API thin client HTTP; packaging Node o distribución standalone del MCP adapter medida y con hash.

## Operaciones de usuario objetivo

`studio start` inicia Ktor y frontend estático local, abre URL opcional; `studio doctor` verifica JDK/PipelineK, workspace perms, SQLite WAL, Node/MCP, capacidad de sandbox OS y límites; `studio mcp` inicia adaptador stdio para configuración de agentes; `studio version --json` muestra versiones y provenance. Son **interfaz futura**, no comandos ya implementados.

No exigir Node global al usuario si el empaquetado de producción incluye runtime MCP apropiado; decidir en S5 mediante smoke. S0 puede ejecutarse con herramientas de desarrollo instaladas.

## Build y empaquetado

Monorepo Gradle backend + frontend pnpm; Gradle puede invocar build front-end solo en tarea explícita con inputs reproducibles; no descargar scripts de Internet durante `studio start`. Distribuciones `studio-linux-x86_64.zip` (o genérico portable si se valida) con JRE conforme a licencia/seguridad, binarios, frontend estático, MCP adapter y metadata. SHA256 + SBOM CycloneDX + release manifest, tests de `distZip`, installDist y arranque sin checkout. Otras arquitecturas solo tras pruebas reales.

## Persistencia / carpetas

Ubicaciones XDG Linux como punto de partida: `${XDG_STATE_HOME:-~/.local/state}/pipelinek-studio/{metadata,experiments,control,logs}`, `${XDG_CACHE_HOME:-~/.cache}/pipelinek-studio/{analysis,plugins}` y `${XDG_CONFIG_HOME:-~/.config}/pipelinek-studio/config.*` con permissions adecuadas; concretar compatibilidades de SO al implementar. Nunca incluir rutas de estado de PipelineK dentro del repo del usuario ni script `.pipeline.kts` autogenerado bajo proyecto original salvo opt-in.

## Backups/migrations

Detener operaciones de metadata, copiar `studio.db` con API SQLite backup o checkpoint consistente, registrar versión, probar migración y rollback con fixture de versión anterior; DB de PipelineK y Output Plane pueden retenerse según referencias. Nunca migrar `run.db` desde Studio. Soportar cleanup explicable, dry-run y canario para no borrar rutas que no son de propiedad Studio.

## Toolchain y licencias

Fijar licencias/distribución de Monaco, Kotlin/Gradle/JRE, Detekt, Ktor, SQLite JDBC, MCP TS. `notices` y SBOM obligatorios; el MCP SDK v2 puede actualizarse bajo test de wire, no con `latest` en CI release. No descargar plugins por script no autorizado; permitidos artefactos firmados/verificados según admisión real.

## Observabilidad propia de Studio

Logging estructurado propio con `requestId`, `experimentId`, `runId`, `operationId`, `state`, time; sin copiar output completo, secretos ni payload source arbitrario al log de backend. Distinguir logs del wrapper JVM de la salida autoritativa de Step. Healthchecks no ejecutan Steps ni inspeccionan carpetas no autorizadas.

## Developer ergonomics

- Ejemplos en `examples/` y sample plugin externo independent build.
- `./gradlew test` focal para backend; `pnpm test`/`typecheck` para TS; script `verify-release` usa installDist, no clases locales.
- No GitHub Actions como gate decisorio; se puede añadir documentación opcional de integración, pero política oficial `CI local obligatorio`.
- SDDK estado/inputs/receipts operativo en directorio del usuario por proyecto; `ROADMAP.md` central en raíz; `docs/history/` solo receipts históricos. Hooks generales sin credenciales/acciones peligrosas.
