# Runner, compilación y seguridad — separación de fases

## Hallazgo del código upstream

`v2/pipeline-scripting-api/.../ScriptingHost.kt` define `compile(definition): ScriptCompilationResult` que compila **y evalúa**; `Kotlin24ScriptingHost` usa `BasicJvmScriptingHost`, y `Main.kt` `validate` llama esa ruta y construye el DSL. No basta el nombre `validate` para afirmar que no produce efectos: `policyCheck` de pipelinek-policy realiza `Files.readAllBytes(...)` al construir el Step. Esta frontera es P0 de producto.

## Operaciones distintas (no atajos)

| Operación | Significado | Riesgos | MVP |
|---|---|---|---|
| `STATIC_ANALYZE` | Parse/type/diagnostics sin eval bytecode del usuario | Compiler plugins y classpath potencialmente activos; ejecución bajo proceso aislado | S3, tras prueba canario |
| `DSL_EVALUATE` | Construcción de PipelineSpec/IR con Kotlin del usuario | Puede leer/escribir filesystem y ejecutar inicializadores | Solo dentro de runner con perfil real |
| `IR_INSPECT` | Inspeccionar IR ya obtenido, sin invocar handlers | Datos pueden ser parciales o sensibles | S2/S3 |
| `STEP_PREPARE` | Resolver contrato, codec, capabilities y admit | Código de plugin si se carga, evitar en backend servidor | S2 |
| `RUN` | Compilar/evaluar y ejecutar handlers por motor canon | Efectos, red, procesos, secretos | S0 desde runner aislado |

Si upstream no ofrece compile-only seguro, `STATIC_ANALYZE` responde `UNSUPPORTED` hasta diseñar y probar un adaptador de compilación que no ejecute Kotlin del usuario. Jamás llamar `validate`/`compile` y devolver `analysis.noEffects=true` sin un test adversarial.

## Process model S0

- `studio-http` es confiable, de larga vida; ningún `.pipeline.kts` del usuario ni JAR externo se carga allí.
- `studio-runner-supervisor` arranca distribución PipelineK **instalada** por `ProcessBuilder(argv)` en entorno específico, no `sh -c`.
- Las rutas de script/DB/control root se generan y verifican dentro de un workspace gestionado; no se acepta `../`, symlinks que escapan ni mounts host no autorizados.
- Por run, directorio aislado y `run.db`; output/control root fuera del proyecto del usuario y separado del estado de Studio.
- STDOUT del proceso runner no es necesariamente todo output de Steps: leer output real desde Output Plane. Capturar stdout/stderr del wrapper en buffers acotados como diagnósticos del supervisor.
- Cancelación cooperativa: usar protocolo oficial de PipelineK si se expone; si no, proceso/señales son **adapters limitados** y el estado debe reconciliarse sin fingir outcome seguro. En S1 probar kill de supervisor, runner y procesos hijos.

## Aislamiento de verdad (gate obligatorio)

Distinguir **process separation** (JVM distinta) de **security sandbox** (aislamiento OS de usuario, FS, red, recursos y procesos). En Linux/Bazzite, evaluar un wrapper con namespaces/user namespaces, mount namespaces y seccomp, con bubblewrap o herramienta equivalente **solo si** el kernel y la distribución soportan los controles; si no, fail closed para ejecución no confiable. La versión local permite perfiles `trusted-local` solo con aceptación explícita del riesgo; no etiquetarla sandbox.

Perfiles iniciales:
- `managed-isolated`: solo scratch propio, HOME temporal, red denegada salvo autorización, límite CPU/memoria/procesos, sin secretos por defecto, lifecycle completo.
- `trusted-local`: código confiable del usuario con advertencia y consentimiento explícitos, capabilities visibles; nunca default silencioso.
- `attached-readonly` (posterior): inspección de proyecto real y operaciones no destructivas, sin suponer que los scripts Kotlin son puros.

**Efectos y admisión:** el lease tipado `Attached`/`Managed` de PipelineK se respeta; un Step destructivo sobre raíz ajena no se autoriza por la GUI. Revisar C8 `deleteDir(".")` y CleanWs al certificar, pero Studio no parchea el Step con un bloqueo superficial basado en cadenas.

## Gestión de plugins

- Primer pase de manifest por bytes/JAR sin class loading, digest/identidad/versiones y rechazo tipado; segundo pase en runner/scanner aislado para `ServiceLoader` y contribuciones.
- Fijar runtime + plugin set en snapshot/admission; ningún upgrade o descarga de plugins implícitos durante la run.
- No usar `ClassLoader` en backend para inspeccionar plugin desconocido.
- Un plugin que requiere capacidades no disponibles debe devolver rechazo, no mock silencioso.
- SDK externo real: usar `StepContract`, `StepDefinition`, `StepCodec`, `StepHandler`, `StepRegistry`, `StepDefinitionContributor` según la versión verificada de S6/main.

## Gestión de secretos

Nunca enviar secretos por OpenAPI/MCP ni persistirlos como snapshots, logs, reportes o fixtures. Credenciales por handles de acceso temporal con autorización, registro de uso sin valores, redacción probada en eventos/output, `stderr` del runner y errores de compilador. Canarios adversariales: secretos en env, comandos, stdout/stderr, mensaje de excepción y artefactos; ningún canal sin redactar.

## Control de recursos y fallos

Tiempos diferenciados: tiempo de compilación, tiempo de ejecución, tiempo de cancelación/recovery. Límite de salida por página y retención del plano de output, sin truncar la autoridad durable silenciosamente. Tamaño de script/payload acotado. Si runner muere o sale sin registro terminal, estado `RECOVERY_REQUIRED/UNKNOWN/LOST` según evidencia real; nunca autoconvertir en FAILED terminal.

## UAT/AAT vinculadas

UAT-001..003, UAT-009..014; AAT-SEC-001..010, AAT-DUR-001..006, AAT-CPP-001..006. Caso P0: script Kotlin que escribe canario en fase de construcción; la operación STATIC_ANALYZE no debe escribirlo, DSL_EVALUATE bajo permisos denegados debe rechazar, RUN confiable debe declarar efecto solo cuando realmente permitido.
