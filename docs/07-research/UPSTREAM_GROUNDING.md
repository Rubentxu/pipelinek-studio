# Grounding y fuentes — código real vs propuestas

**Fecha de observación:** 2026-10-09 · **Evidencia:** inspección remota mediante GitHub connector de árbol/contenido, **no** ejecución local de tests ni compilación de repos. Las ramas avanzan; repetir `git fetch` y comparar SHA en S0.

## Ramas observadas

| Repositorio | Ref | SHA observado | Lectura |
|---|---|---|---|
| `Rubentxu/pipeline-kotlin` | `main` | `b003e3c6dbba58eaf80fe5930d98af9660a7392b` | motor/SDK/SQLite/read-side; autoridad actual al consultar |
| `Rubentxu/pipeline-kotlin` | `s6-plugin-sdk` | `6e8e86bd2e234dcaa4fcb4061ebc6e9fbeb7ce0b` | desarrollo avanzado SDK; no asumir integrado main |
| `Rubentxu/pipelinek-policy` | `main` | `3d35f49e83f9b487711010bfb226c330d4bfb5ff` | plugin evolutivo, fuera del MVP |
| `Rubentxu/pipelinek-policy` | `feat/m10-cert` | `90c3d0e5748b1e4aae591b348f3c144d51c9b7ea` | plugin `policy.check` existe en rama avanzada; **no** usar como SDK Studio base |
| `Rubentxu/pipelinek-assurance` | `main` | `0645599f76297f1cbf9089ed0d1ecf6fd97661c3` | dominio de evidencia y engine; futuro |
| `Rubentxu/pipelinek-studio` | `main` | inexistente/404 al consultar | bootstrap nuevo repo en S0 |

## Código relevante localizado (links orientativos a main, verificar SHA vigente)

- [`ScriptingHost.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptingHost.kt): `compile(definition)` compila **y evalúa**.
- [`ScriptDefinition.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptDefinition.kt): fuente inline/ruta y classpath.
- [`ScriptingDiagnostic.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptingDiagnostic.kt): severity, message, line, column y path.
- [`Kotlin24ScriptingHost.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-scripting-kotlin24/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/Kotlin24ScriptingHost.kt): `BasicJvmScriptingHost`, `evalWithTemplate`, classpath y cachekey.
- [`Main.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt): `validate`, `run`, `--db`, `--control-root`, selección durable/in-memory; `validate` usa host.compile (Kotlin eval).
- [`DslCompiledPipelineCompiler.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt): IR StageNode/StepNodes y plugins por registry abierto.
- [`StepRegistry.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/StepRegistry.kt): StepContract, StepCodec, StepHandler, FrozenStepRegistry y capabilities; `StepCodec.schema()` puede ser `{}`.
- [`PreLoadPluginAdmission.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PreLoadPluginAdmission.kt): primera admisión sobre artifact sin ClassLoader antes de composición.
- [`SqliteEventStore.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/SqliteEventStore.kt): SQLite WAL, eventos, journal schema, escritor por batches, índice UNIQUE(run_id,sequence).
- [`SqliteConnectionFactory.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/SqliteConnectionFactory.kt): WAL, synchronous NORMAL, busy timeout.
- [`MainConsoleCli.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainConsoleCli.kt): `ConsoleReadService`, `OutputCursor`, OutputReadPort y refusals.
- [`OutputReadPort.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputReadPort.kt): contrato de lectura del output.
- [`EventHistoryReader.kt`](https://github.com/Rubentxu/pipeline-kotlin/blob/main/v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryReader.kt): lectura por secuencia del Event Plane.

## Especificaciones upstream existentes (no equivalen a implementación)

1. [Scripting Compiler Spec](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/SCRIPTING_COMPILER_SPEC.md)
2. [Step Plugin SDK](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/STEP_PLUGIN_SDK.md)
3. [Step & Plugin Certification](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md)
4. [CLI Observability](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md)
5. [Test Sandbox Profiles](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/TEST_SANDBOX_PROFILES.md)
6. [Recovery Durability](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/RECOVERY_DURABILITY.md)
7. [DSL Spec](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/03-specifications/DSL_SPEC.md)
8. [ADR-0024 LSP metadata](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0024-lsp-metadata-json-schema.md)
9. [ADR-0099 main/release](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/v2/04-adrs/ADR-0099-main-authority-release-candidates.md)
10. [Introspection diagnostics LSP v2](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-v1-to-v2-evolution-bundle/specifications/07-introspection-diagnostics-lsp.md) — **evolución propuesta**.
11. [Affordance API](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-v1-to-v2-evolution-bundle/specifications/05-affordance-api-and-cli-discovery.md) — **evolución propuesta**.
12. [Plan / why-not / inline step](https://github.com/Rubentxu/pipeline-kotlin/blob/main/docs/pipelinek-v1-to-v2-evolution-bundle/specifications/08-plan-why-not-and-inline-invocation.md) — **evolución propuesta**.

## Documentación externa actual

- MCP spec 2026-07-28: https://modelcontextprotocol.io/specification/2026-07-28
- MCP v2 TS SDK: https://github.com/modelcontextprotocol/typescript-sdk ; packages `@modelcontextprotocol/server` / `client`; guía server stdio v2 https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/migration/support-2026-07-28.md ; serve HTTP https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/serving/http.md .
- MCP Tasks extension **draft/opt-in**, fuera del MVP: https://tasks.extensions.modelcontextprotocol.io/specification/draft/tasks
- Detekt 2.0 alpha6 ext https://detekt.dev/docs/introduction/extensions/ ; type resolution https://detekt.dev/docs/gettingstarted/type-resolution/ . Verificar toolchain en `v2/gradle/libs.versions.toml` upstream (2.0.0-alpha.6 + Kotlin 2.4.10 observados).
- Monaco: https://microsoft.github.io/monaco-editor/ ; monaco-languageclient https://github.com/TypeFox/monaco-languageclient ; Kotlin LSP https://github.com/Kotlin/kotlin-lsp ; Ktor SSE https://ktor.io/docs/server-server-sent-events.html .
- Referencia interactiva Groovy Console https://groovy-lang.org/groovy-console.html .

## Dependencias y bloqueantes por verificar

**U0** Publicación real de artefactos S6/main y compatibilidad JVM/SDK. **U1** Compile-only sin eval inexistente/no demostrado. **U2** Protocolo cancelación/read-side público/admisión para ejecutor externo. **U3** Sandboxing linux trust-level probado. **U4** Output Plane source channels/sellado/recovery (especialmente branch OBS) y read port. **U5** Plugin SDK schema útil/KSP metadata y LSP para `.pipeline.kts`.

No introducir ni simular una abstracción de producto para cubrir un gap upstream sin test y decisión: documentar `BLOCKED-BY-Ux`, mantener la capacidad no publicada hasta que se resuelva. Si el core necesita extensión, hacerla **genérica**, acompañada de ejemplo/plugin externo y release upstream certificada antes de prometer feature Studio.
