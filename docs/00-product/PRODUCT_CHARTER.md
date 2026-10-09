# Product Charter — PipelineK Studio

**Estado:** propuesta normativa de producto · **Dependencias de implementación:** U0–U5 en `UPSTREAM_GROUNDING.md`.

## Problema y oportunidad

Un desarrollador de PipelineK hoy necesita archivos, CLI, logs, inspección de artefactos y suites para contestar preguntas simples: «¿compila?», «¿qué Step ha sido admitido?», «¿qué resultados produjo?», «¿qué falló?», «¿cómo pruebo un plugin sin romper mi proyecto?». Un agente necesita las mismas respuestas de forma estructurada, verificable y con límites de efectos. La Interactive Groovy Console inspira el bucle editar → ejecutar → observar → cambiar; Studio lo adapta a Pipelines declarativos, Step Contracts, IR y durabilidad.

## Producto

Aplicación web local **React/TypeScript + Monaco** con backend **Kotlin/JVM** que permite experimentar sobre **PipelineK V2 real**, supervisando ejecutores aislados, inspeccionando source/diagnósticos/IR/Steps/results y exponiendo idénticos casos de uso por **MCP, HTTP y CLI**. En S0–S5 no implementa laboratorios especiales de Policies ni Assurance; admite futuros contratos tipados de resultados.

## Personas y necesidades

- **Autor de pipelines:** probar cambios cortos, ver etapas, logs y outcomes, reproducir y comparar.
- **Autor de Steps/plugins:** inspeccionar manifest, entrada/salida, capabilities, escenarios positivos y negativos con SDK real, ABI/compatibilidad.
- **Revisor técnico:** distinguir fuente, decisiones, efectos declarados, eventos confirmados y causas de rechazo.
- **Agente de desarrollo:** descubrir capacidades sin leer código interno; crear variantes, ejecutar bajo perfiles concedidos, consultar evidencias y dejar propuestas auditables.
- **Integrador futuro (VS Code/Jenkins/Fabric):** consumir API versionada sin depender de componentes React.

## Jobs to be done y prioridades

| ID | Necesidad concreta | Prioridad | Gate de valor |
|---|---|---|---|
| P-01 | Probar un `.pipeline.kts` mínimo y ver su stdout sin configurar un CI | P0 | S1: 1 clic, real, reproducible |
| P-02 | Ver errores de código, construcción, admisión, ejecución y recovery sin confundirlos | P0 | S0/S1: errores tipados y fuentes |
| P-03 | Conservar código y contexto exactos de una run, recuperar tras cierre del navegador | P0 | S1: snapshot + run durable |
| P-04 | Hacer lo mismo por MCP local y CLI | P0 | S0/S1: paridad |
| P-05 | Conocer Steps de plugins realmente admitidos y sus capabilities | P0 | S2: catálogo sin hardcoding |
| P-06 | Probar Step/plugin externo con UAT y casos negativos | P1 | S2: SDK público, 0 core edits |
| P-07 | Analizar Kotlin y DSL sin ejecutar el script en modo estático | P1 | S3: prueba canario 0 efectos |
| P-08 | Usar Detekt y reglas de PipelineK correctamente versionadas | P1 | S3: hallazgos reproducibles |
| P-09 | Comparar dos ejecuciones y pedir a agente que investigue y proponga cambios | P1 | S4: variantes sin mutación original |
| P-10 | Preparar integración de editor externo y plugin results genéricos | P1 | S5: cliente no React de conformidad |
| P-11 | Policies/Assurance UI, depuración Kotlin por instrucción, colaborativo remoto | Fuera inicial | Evolutivos independientes |

## Criterios de experiencia

- **Fuente primero:** editor, selector de experimento/snapshot, botón Run, panel de resultados. El primer ejemplo funciona sin proyectos Gradle previos.
- **Verdad observable:** `RUNNING`, `FAILED`, `REFUSED`, `RECOVERY_REQUIRED`, `UNKNOWN` son estados diferentes; nada se pinta verde por ausencia de datos.
- **Explicabilidad:** cada fallo muestra `code`, `phase`, `origin`, `message`, referencia a objeto y `actions` existentes. Explicación del agente identificada como interpretación, no resultado certificado.
- **Control de efectos:** ejecución requiere permisos y perfil visibles; previsualización de efectos declarados solo cuando existan metadatos fiables.
- **No intimidante:** interfaz progresiva: Scratchpad → Inspector → Test Lab; paneles avanzados no bloquean el caso sencillo.

## Métricas de producto y objetivos de aceptación (no estadísticas inventadas)

- Primera ejecución del ejemplo instalado, sin conexión de red ni proyecto previo, completada en la UAT S1. No fijar tiempo objetivo hasta medir baseline por hardware.
- Cero ejecuciones duplicadas en reintento de la misma `Idempotency-Key` en test de crash/reconnect.
- 100 % de herramientas MCP publicadas enlazadas a un caso de uso real con UAT y autorización; prohibidas herramientas ficticias.
- Cero inconsistencias semánticas Web/CLI/MCP en corpus golden.
- Reportar p50/p95 de startup, compilación fría/caliente, primera salida y paginación antes de imponer presupuestos.

## Fuera de alcance hasta decisión posterior

Multiusuario SaaS, soporte de ejecutar scripts desconocidos en host sin aislamiento, reemplazar IDE completo, implementación del motor PipelineK, LSP Kotlin escrito desde cero, estado persistente de sesión Kotlin estilo REPL a través de runs, compatibilidad Groovy o plugins Jenkins, debugger paso a paso JVM, SDK Studio específico para Assurance/Policies, extensiones UI con JS suministrado por plugins, dependencia de GitHub Actions.

## Resolución estratégica

**Primera vertical: pipeline completo sencillo.** Step Lab se entrega enseguida después, sin inventar ejecución de selección arbitraria; `Run Selection` contextual pasa a S4 tras disponer de un generador real de escenarios.
