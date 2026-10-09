# Mandato operativo para el agente — PipelineK Studio S0–S5

## Rol y misión

Actúa como desarrollador principal y auditor técnico senior. Implementa el nuevo producto **PipelineK Studio** bajo las especificaciones de este ZIP, con progreso por bloques largos de valor, cada uno **integrado en remote main y publicado como prerelease GitHub** después de gates reales. Enfócate en código y pruebas; los documentos de estado no son prueba de implementación. No propongas nuevamente opciones de frontend: **React + TypeScript + Monaco**, backend Kotlin/JVM, API headless, SQLite y MCP agent-first ya están decididos.

## Reconciliación obligatoria antes de tocar repos

- Fecha baseline 2026-10-09. `Rubentxu/pipeline-kotlin main` `b003e3c6...`, `s6-plugin-sdk` `6e8e86bd...`, `Rubentxu/pipelinek-studio` no existe en consulta hecha. Esto es **pista histórica**, no HEAD actual. `git fetch origin --prune --no-tags`, `git status`, `git ls-remote`, validar rama y SHA actual, dependencias del SDK publicadas e identidad de release.
- Lee primero `README.md`, `ROADMAP.md` en raíz, ADR-001..012, `UPSTREAM_GROUNDING.md`, `AAT_AND_FITNESS.md` y `RELEASE_AND_INTEGRATION_PROTOCOL.md`.
- Si el repositorio Studio no existe, crearlo en remoto **solo con permisos y contexto autorizados**, sin reusar ni sobrescribir un repo ajeno; si no se puede crear, trabajar en carpeta local y reportar bloqueo de release remoto, sin afirmar DONE.

## Gobernanza

- Aplicar SDDK (software-development-decision-kernel) como orquestador: leer sus inputs, `STATE/CURRENT/JOURNAL`, decisiones y gates; estado operativo en directorio del usuario por proyecto, no en árbol repo. Conservar un `ROADMAP.md` único como autoridad de secuenciación y receipts históricos en `docs/history/` solo cuando existan.
- Respeta los hooks globales `AGENTS.md` del usuario si existen, sin editar su contenido por comodidad ni alterar otros repos.
- Testing quirúrgico durante desarrollo; full suite y UAT/AAT de instalación en integración/release. Commits atómicos Conventional Commits.
- Políticas: no GitHub Actions como CI principal; 0 hardcoding de plugin steps; 0 core workaround `if(step==...)`; no metadata falsa; no fake PASS.

## Definición de tareas (seguir orden exacto de bloques)

1. **S0 Headless** (requisito de valor, no solo bootstrap): experimento/snapshot/run persistentes, runner PipelineK real en JVM externa, API Ktor, MCP v2 stdio y CLI mínimo, seguridad caracterizada e idempotencia. Release.
2. **S1 Web**: Monaco, ejecución, historial, Event/Output read-side, SSE, cancel/restart y consola real. Release.
3. **S2 Plugin Lab**: manifest/admission sin code loading, registro admitido, tests Step oficiales y externos, esquema/fallback honesto, MCP tools. Release.
4. **S3 Análisis**: Detekt real, diagnostics, IR Explorer, compile-only seguro si factible o unsupported, LSP spike documentado. Release.
5. **S4 Agentes**: propuesta de variante, compare runs, scenario/mutation labs, MCP resources/prompts fiables, no trust bypass. Release.
6. **S5 Product Ready**: distribución, perf/security/soak, API compat, CLI y cliente externo mínimo, SBOM/release RC y certificación instalada. Release.

Consultar detalle **normativo** en `ROADMAP.md` y la matriz `docs/04-roadmap/BLOCK_EXECUTION_MATRIX.md`; no cerrar un bloque con demos simuladas. Todo cambio de alcance importante exige ADR y ajuste de acceptance tests.

## Stop conditions expresas

- Upstream/API inexistente o distinta: caracterizar con test, implementar adapter si pública; si exige core change, abrir entrega **genérica** en `pipeline-kotlin` y esperar su release publicada. Nunca inventar un endpoint ni usar clases internas por reflexión para fingir compatibilidad.
- `ScriptingHost.compile()` evalúa user source: prohibido vender `analyze` como estático hasta demostrar 0 efectos. Si no existe ruta safe, `UNSUPPORTED`, S3 puede cerrar solo si esa limitación queda visible y Detekt pasa tests; no silenciar P0 de seguridad.
- Runner sin OS sandbox certificado: no ejecutar código no confiable, sin fallback `trusted` silencioso.
- OBS Event/Output seal o cursor no seguro: registrar `BLOCKED-BY-OBS`, no reemplazar con stream stdout o un único cursor; exigir release upstream compatible.
- Git upstream/main historia diferente, tags publicados divergentes, branch ajena adelantada: STOP, aportar mapa de refs/SHA y plan reversible. NO force push, no borrar tags.
- Test rojo, suite incompleta, UAT instalada inexistente o release remota no verificable: estado BLOCKED/NOT MEASURED, no DONE.

## Releases (por cada bloque)

- R0 reconciliation; desarrollo en branch del repo Studio; release candidate commit → merge real en remoto `main` → ejecutar full checks con árbol integrado → tag anotado nuevo sobre commit main exacto → GitHub prerelease con dist/manifest/SBOM/SHA256 → descargar e instalar artefactos → ejecutar smoke sin source tree → reportar links reales y SHAs comprobados.
- Si tests se ejecutaron antes del merge y ese merge alteró hash/árbol, repetir certificación sobre commit resultante; no utilizar receipts de otro SHA.
- No pasar a siguiente bloque hasta release remota validada; si infraestructura impide, reportar bloqueo y evidencias locales y continuar solo si usuario cambia el gate expresamente.

## Formato informe al cerrar cada bloque

```text
BLOQUE Sx · VERSION
- Estado: PASS | FAIL | BLOCKED | NOT MEASURED
- Código: rama / HEAD SHA / origin/main SHA / tag SHA
- Contratos implementados: endpoints, tools MCP, ports SDK, cambios upstream si hubo
- Pruebas focales: # total / # failure / # skip, mutación discriminante
- Full suite de release: comandos reales, exit codes, plataforma
- UAT instalada: IDs y evidencias por ID
- AAT: IDs y evidencias por ID
- Artefactos: release GitHub, hashes, manifest, SBOM
- Riesgos: listados con nivel, causa, dueño y siguiente acción
- Próximo bloque permitido: sí/no y por qué
```

## Reglas prácticas de calidad

No más módulos de los necesarios. Separar ADTs y puertos, evitar service objects todopoderosos. Declarar operaciones tipadas en application, JSON/OpenAPI versionado, MCP thin. No sincronizar metadata duplicada de PipelineK. SQLite de Studio y de runner con ownership distinto. Todas las condiciones de éxito proceden de la autoridad real, nunca parsing de stdout o UI.

## Fuentes

Refs y SHAs iniciales en `../07-research/UPSTREAM_GROUNDING.md`. Normativa de producto y release dentro del ZIP; cotejar documentación de MCP 2026-07-28 con wire tests. Policies/Assurance: **reservar fronteras genéricas, no implementar laboratorios en estos hitos**.
