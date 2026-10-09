# UAT — Catálogo ejecutable de aceptación de usuario

**Regla:** UAT son pruebas reales del producto instalado, no capturas decorativas ni tests de DTO. Cada caso debe registrar input, perfil, SHA del motor, SHA Studio, escenario, evidencia/artifact ref, expected/actual, comando/script reproducible, status PASS/FAIL/NOT-MEASURED y motivo. El código actual prevalece sobre documentos. IDs estables; no reclasificar un FAIL como «skipped PASS».

## S0 — Foundation

| ID | Contexto/acción del usuario | Resultado exigido |
|---|---|---|
| UAT-001 | Instalar Studio headless en directorio limpio sin checkout; crear experimento + pipeline `echo` | Una ejecución real de PipelineK; `runId` y outcome verificables, no mocks |
| UAT-002 | Consultar snapshot del run y artefactos seleccionados | Bytes/código exactos, digest, runtime/SDK/plugin/profile identificados |
| UAT-009 | Script Kotlin intenta crear canario fuera de workspace bajo perfil aislado | Rechazo o bloqueo de efectos, canario externo intacto; sin false PASS |
| UAT-013 | Mismo experimento ejecutado por API HTTP y MCP stdio | Mismos estados/outcomes y recursos equivalentes (runs distintos legítimos) |
| UAT-014 | Cliente corta conexión tras solicitud y reintenta con misma clave | Una sola run por request digest; otra clave admite otra run |
| UAT-015 | Perfil no soportado en plataforma local | Rechazo claro, no fallback silencioso a trusted-local |

## S1 — Interactive Console

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-003 | Abrir navegador local, editar demo y pulsar Run | Runtime real, logs, stages, status y output recuperables |
| UAT-004 | Código con error Kotlin de sintaxis | Diagnóstico source-mapped y sin ejecución de Step |
| UAT-005 | DSL construye sin spec utilizable | `DSL_CONSTRUCTION_FAILED`, nunca SUCCESS por compile success aislado |
| UAT-006 | Step falla tras emitir stdout/stderr | Outcome FAIL, logs consultables sin perder últimos bytes |
| UAT-007 | Pipeline con dos stages, uno omitido / uno failed | Etapas representadas por eventos reales, sin inferencia textual |
| UAT-008 | Operación sin stdout | Vista vacía real solo si confirmado/sellado; no bloqueo infinito |
| UAT-010 | UI cancela una run larga | `CANCEL_REQUESTED` → terminal confirmado o unresolved; no éxito prematuro |
| UAT-011 | Cerrar/reabrir navegador durante run | Reanudación mediante handle y cursores, sin duplicar efectos |
| UAT-012 | Reiniciar Studio durante run o antes de leer output | Recovery y lectura coherente; ningún terminal inventado |
| UAT-016 | Comparar fuente usada con editor modificado | Run muestra snapshot original y no el borrador posterior |
| UAT-017 | Líneas stdout UTF8 multibyte partidas entre páginas | Reconstrucción exacta, sin reemplazos silenciosos |
| UAT-018 | stdout único; stderr único; ambos; streams inexistentes | Cada fuente representa solo lo que realmente persistió el plano de output |
| UAT-019 | Texto malicioso con HTML/ANSI/URL en stdout | Renderizado inocuo; sin XSS, autolinks peligrosos ni secretos |

## S2 — Step & Plugin Lab

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-020 | Plugin externo compilado contra SDK publicado, instalado en runner | Step aparece en catálogo sin modificar código Studio o PipelineK |
| UAT-021 | Plugin sin manifest/manifest incompatible + inicializador canario | Rechazado **antes** de inicialización, canario intacto |
| UAT-022 | Ejecutar Step con capability ausente | Rechazo previo al handler, motivo y acciones correctas |
| UAT-023 | Codec válido/inválido/roundtrip | Resultado tipado sin cast silencioso ni default success |
| UAT-024 | Plugin externo y Step oficial en escenarios comparables | Mismo modelo de dispatch, eventos/outcomes, sin segundo engine |
| UAT-025 | Step sin input/output schema útil | Studio ofrece JSON/raw y marca `SCHEMA_UNAVAILABLE`, no formulario inventado |
| UAT-026 | Seleccionar Step y consultar provenance de artifact | Version/digest/provider verdaderos, sin confundir installed vs admitted vs certified |

## S3 — Diagnostics & IR

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-027 | Compilación Kotlin con error en posición conocida | Diagnóstico en línea correcta dentro de Monaco |
| UAT-028 | DSL valido con IR stage/Step/block | Visualización IR coincide con proyección canónica del mismo snapshot |
| UAT-029 | Step desconocido por catálogo dinámico | `UNKNOWN_STEP` en fase apropiada; sin fallback a core Step aproximado |
| UAT-030 | Ejecutar Detekt sobre `.pipeline.kts` con violación conocida | Hallazgo reproducible con rule ID y toolVersion |
| UAT-031 | Regla Detekt custom falso positivo canario | No reporta Step solo porque nombre textual se parece; preservar precisión |
| UAT-032 | Ejecutar Static Analyze sobre Kotlin side-effect top-level | 0 efectos verificables o `UNSUPPORTED`; nunca 'analysis safe' falso |
| UAT-033 | Solicitar análisis con type resolution sin classpath válido | Degradación explícita `SYNTAX_ONLY` o refusal, nunca semantic verified |
| UAT-034 | LSP Kotlin sobre `.pipeline.kts` con Step externo | Si soportado, completion de clase real; si no, limit documentada sin bloquear release |
| UAT-035 | Seleccionar nodo IR y consulta evento runtime | Se distingue diseño/planificado de evento confirmado |

## S4 — Agent Collaboration

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-036 | Agente investiga fallo por MCP sin abrir navegador | Enumera operaciones, evidencia y motivos; no inventa veredicto |
| UAT-037 | Agente propone variante y genera test negativo | Original no cambia; variante tiene snapshot digest y resultado propio |
| UAT-038 | Agente compara dos runs con plugins distintos | Señala distinta version/lock y no atribuye diferencia solo al código |
| UAT-041 | Repetir tool MCP start con misma clave tras desconexión | Misma run; no segunda ejecución de Step con efecto |
| UAT-042 | Usuario revisa propuesta del agente | Diff visible, evidencia y autorización explícita de aplicación |
| UAT-043 | Agente solicita efecto fuera de perfil | Denegación idéntica a Web, actor/audit trazables |
| UAT-044 | Test de mutación introduce fallo premeditado | Test lo detecta; el reporte identifica mutación vs run normal |
| UAT-045 | Plugin description contiene instrucción maliciosa para LLM | Solo se trata como dato, sin escalada de permisos |
| UAT-046 | Agent prompt genera escenario sin claims de prueba | Sin ejecutar: muestra `NOT_EXECUTED`; después distingue PASS/FAIL según test |

## S5 — Distribución e interoperabilidad

| ID | Contexto/acción | Resultado exigido |
|---|---|---|
| UAT-039 | Segundo cliente no React consume API documentada | Discover, run, cancel, output y errors coherentes con Web |
| UAT-040 | Upgrade de versión con DB antigua | Migración reversible probada, o rechazo con rollback explícito; no corrompe DB upstream |

## Oráculos adicionales

- UAT de output compara bytes SHA256 del productor conocido con read-side paginado, no líneas stdout capturadas por el wrapper.
- UAT de resultado compara outcome oficial (StepOutcome, RunOutcome/event journal), no `stdout.contains("SUCCESS")`.
- UAT sin instalación real solo es **test de desarrollo**, no cierra el caso.
- UAT no tiene autoridad para autorizar un efecto: admisión y sandbox siempre externos al oráculo de UX.
- Un caso con upstream no certificado o entorno no disponible = `NOT MEASURED/BLOCKED` y debe seguir visible en matriz; no borrar su fila.
