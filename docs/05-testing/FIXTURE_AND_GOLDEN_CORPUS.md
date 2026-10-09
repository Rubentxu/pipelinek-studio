# Fixtures y corpus Golden — pruebas que distinguen comportamiento

## Corpus mínimo sin ficheros sintéticos de producto

| Fixture | Descripción | Oráculo |
|---|---|---|
| `pipeline-hello` | Pipeline 1 stage, Step sh echo mensaje conocido | Run terminal success + bytes exactos Output Plane |
| `pipeline-fail` | Step termina no-zero tras escribir stdout | Run fails + bytes/digest presentes |
| `pipeline-silent` | Step sin stdout/stderr | End-of-stream honesto o refusal detectable, no bloqueo permanente |
| `pipeline-channels` | stdout/stderr distintos cuando host soporta fuentes separadas | No mezcla falsa; validación por bytes |
| `pipeline-unicode` | UTF8 2/3/4 bytes con división por página | Hash del byte stream y decodificación incremental igual |
| `pipeline-long` | Timeout/cancelación y subprocess durable | Cancel requested vs terminal y recovery reales |
| `pipeline-two-stages` | éxito, fail, skip, block | IR y evento de Stage reflejan run |
| `pipeline-attached-delete` | intento root destructive en attached | No borrar sentinel del workspace de usuario |
| `kotlin-top-level-effect` | `Files.writeString` en constructor/script | STATIC_ANALYZE no lo ejecuta; eval solo si autorizado |
| `plugin-eager-initializer` | Provider con static initializer que escribe sentinel | Manifest inspection/rejection no ejecuta initializer |
| `plugin-upper-external` | Step externo de uppercase con codec simple | Camino canonical SDK, hash/manifest/provenance |
| `plugin-missing-capability` | Same step sin capability requerida | Refusal antes de handler |
| `plugin-unknown-schema` | `schema()=="{}"` | `SCHEMA_UNAVAILABLE`, JSON fallback |
| `detekt-cases` | `.pipeline.kts` correcto/incorrecto, lookalike names | Precisión sin false-positive textual |
| `prompts-maliciosos` | Step metadata/logs con instrucciones de engaño | UI y agente no aplican instrucciones/escala permisos |
| `mcp-dupe` | dos llamadas start concurrentes + drop connection | un launch real por idempotency key |

## Golden contract test

- Cada fixture tiene `manifest.json` con expected `runtimeVersionRange`, exactitud de bytes hash, resultado permitido, fase, capability profile, archivos de control y flags `KNOWN_UNSUPPORTED` cuando procede.
- Golden outputs no contienen rutas absolutas ni tiempos de pared/UUID aleatorios; comparar por propiedades de dominio, no normalizar errores que revelarían pérdida de información.
- Fixture de plugin externo se compila desde un **proyecto distinto** contra SDK publicado; no copiar clases a core.
- Oracle de `NoEvidence!=Pass`: si futuros plugins se incorporan, el renderizador genérico nunca convierte ausencia de campo en `SUCCEEDED`.
- Pruebas de mutación obligatorias para `StaticAnalyze` (activar eval), `PluginInspection` (inicializar JAR), `Idempotency` (quitar unique), `OutputRefusal` (convertir en page vacía), `RunCancelled` (marcar terminal prematuro).

## Flujo de corpus

Generar fixtures mínimos desde fuentes versionadas; verificar SHA por contenido, ejecutar desde ruta temporal limpia, reportar actual/expected con fase y evidencia, sin subir `run.db`, secretos ni logs temporales al repo. Reportes de release van al receipt y artefactos release, no a carpetas de estado en el repositorio.
