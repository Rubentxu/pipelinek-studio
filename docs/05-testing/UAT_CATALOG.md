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

## Contrato de veredicto del arnés (T2 / S3+S4)

Regla: **ausencia de ejecución nunca es PASS.** Un script de UAT que termina en
verde sin que PipelineK haya ejecutado el snapshot certifica una distribución
que no ejecuta pipelines. El arnés implementa un único contrato, compartido por
`scripts/uat-from-distribution.sh`, `scripts/run-all-uats.sh` y
`scripts/e2e-host.sh`:

| Exit | Línea de veredicto | Significado |
|---|---|---|
| 0 | `UAT_VERDICT: PASSED` + `ALL UAT STEPS PASSED` | La run alcanzó `SUCCEEDED` **y** el plano de output contenía el marcador sellado en la fuente |
| 1 | `UAT_VERDICT: FAILED` | Ejecutó (o anunció) pero el resultado esperado falta: estado terminal no exitoso, sin `run_id`, sin marcador, o respuesta no documentada |
| 2 | (error de uso, sin HTTP) | Precondición/argumento inválido; no se emite ninguna petición |
| 3 | `UAT_VERDICT: BLOCKED` | El runtime no está presente o no certificó una run: `409 REFUSED`, `503`, sin `run_id`, o timeout |

Consecuencias operativas:

- `409 RUNTIME_UNAVAILABLE` (sin SDK) es **BLOCKED**, no PASS. El banner
  `ALL UAT STEPS PASSED` es inalcanzable desde ese camino.
- Un `202 OUTPUT_REFUSED` impide verificar el marcador: **FAILED**.
- Timeout sin estado terminal: **BLOCKED**. Estado terminal `FAILED`/`CANCELLED`
  o `REFUSED` en la binding: **FAILED**/**BLOCKED** respectivamente.
- Los planos de diagnóstico (`/events`, `/diagnostics`, `/ir`, `/steps`) se
  reportan como diagnósticos: una negativa **documentada** es `NOT MEASURED` y
  no condiciona la aceptación; un status no documentado (p.ej. 500) sí falla.
- `scripts/e2e-host.sh` es un wrapper fino: dist + `studio doctor` + delegación
  en el UAT canónico, propagando el mismo exit code. No reimplementa el flujo.
- El certificador `scripts/verify-from-clean-clone.sh` solo marca verde el paso
  3b si el log contiene `UAT_VERDICT: PASSED`. Sin SDK la certificación es
  **NOT_READY** honesta; para certificar hay que aportar
  `PIPELINEK_SDK_CLASSPATH=...`.

### API pública usada (sin formas obsoletas)

`POST /experiments` → `POST /scenarios` (id real de escenario, nunca el
`experiment_id` como sustituto) → `POST /experiments/{id}/snapshots` →
`POST /runs` con `experiment_id`, `snapshot_id`, `scenario_id` y header
`Idempotency-Key` → `GET /runs/{runId}` (`run_id`, estados
`SUCCEEDED|FAILED|CANCELLED|REFUSED`) → `GET /runs/{runId}/output` (envelope
`chunks[].bytesBase64`, decodificado en orden). La versión se compara con la del
binario instalado (`STUDIO_CLI_BIN --version`); no hay literales de release
(`1.0.0-rc.11`) en el arnés.

### Transporte

No hay default implícito de endpoint: `STUDIO_HTTP` es obligatorio.

```bash
# HTTPS canónico (Studio escucha TLS en 127.0.0.1:7778).
# DATA_DIR es el data dir del proceso: ${user.home}/.pipelinek-studio/runtime
# para la distribución `studio-app`, ${user.home}/.pipelinek-studio para
# `studio start`. El alias y la contraseña son las constantes reales de
# `TlsConfig` (KEY_ALIAS, KEY_PASSWORD); con los valores de un keystore de
# ejemplo (`pipelinek-studio` / `changeit`) keytool sale con código ≠ 0 y el
# PEM resultante no es un certificado.
DATA_DIR="$HOME/.pipelinek-studio/runtime"
keytool -exportcert -rfc -alias studio-loopback \
  -keystore "$DATA_DIR/keystore.jks" -storepass studio-loopback-change-me > studio-loopback.pem
STUDIO_HTTP=https://127.0.0.1:7778 STUDIO_CA_CERT=studio-loopback.pem \
  ./scripts/uat-from-distribution.sh

# HTTP de test, opt-in explícito (mismo flag que ya exigen CLI y MCP)
STUDIO_HTTP=http://127.0.0.1:7777 STUDIO_HTTP_INSECURE=1 \
  ./scripts/uat-from-distribution.sh
```

No existe bypass TLS (`-k`): un fallo de certificado es un fallo real. Curl
negocia **HTTP/2** por defecto contra el listener TLS y el guard de host lo
acepta leyendo `:authority`, igual que acepta `Host` en HTTP/1.1: ya no hace
falta el workaround `--http1.1`. Un `:authority` fuera de la allow-list se
rechaza con `400 HOST_NOT_ALLOWED` y una petición sin autoridad con
`400 HOST_HEADER_MISSING` (ver `docs/04-security/transport.md`).

### Presupuesto de tiempo (un solo mando)

`UAT_TIMEOUT` (1..1800 s, por defecto 120) acota **todo** el recorrido:

- espera de estado terminal,
- cada petición HTTP (`--connect-timeout` ≤5 s y `--max-time` ≤30 s derivados de
  `UAT_TIMEOUT`, de modo que un servidor que acepta la conexión y no responde
  termina en `BLOCKED` en lugar de colgar el arnés),
- la gracia posterior al estado terminal para leer output (≤10 s).

`UAT_POLL_INTERVAL` (0..60 s, por defecto 1) es el único otro ajuste expuesto.
Ambos se validan **antes de la primera petición**: un valor no numérico o fuera
de rango es un error de uso (exit 2), no un timeout de red misterioso. No se
reintenta ningún `POST`.

### Coste del arnés y selección (S4)

- `scripts/run-all-uats.sh` ejecuta cada escenario **una vez**. Antes dormía 30 s
  ciegos tras cada hijo (≈9 min con el set actual) y ejecutaba además cada
  `uat-podman-NNN.sh` dos veces (una directa y otra vía `uat-podman-all.sh`,
  duplicando contenedor y arranque de Studio). El sleep ciego se ha eliminado
  (medido: 120 034 ms → 27 ms con 4 hijos instantáneos) y los escenarios Podman
  se alcanzan solo a través de su agregador (`UAT_STAGGER_SECONDS` queda como
  opt-in explícito para quien apunte toda la tanda a un único servidor vivo,
  por el rate limit 60 req/60 s).
- Veredicto agregado: `0` todo PASSED · `1` algún FAILED · `3` algún BLOCKED
  (sin FAILED) · `2` uso/selección vacía (una selección vacía no certifica).

### Decisiones de regresión (promote / replace / retire / defer)

| Candidato | Decisión | Garantía equivalente |
|---|---|---|
| Paso 4j de `verify-from-clean-clone.sh` (greps de strings + `bash -n` sobre el UAT) | **RETIRE** | `UatVerdictTest` (en `./gradlew test`, del que `verifyCleanClone` depende) conduce los canales false-PASS, el payload público (experiment/scenario/idempotency) y la validez bash del script |
| Resto de checkpoints 4c..4ai (source-grep / presencia de ficheros) | **DEFER** | No se ha demostrado cobertura conductual equivalente en este cambio; no se retiran |
| Flujo runtime duplicado en `e2e-host.sh` | **REPLACE** | Una sola implementación (UAT canónico) + wrapper con `doctor` |
| Duplicado anidado Podman + sleep ciego en `run-all-uats.sh` | **RETIRE** | Agregador único con propagación de FAILED/BLOCKED |
| `UatVerdictTest` (curl real contra un `HttpServer` JDK en 127.0.0.1:0) | **PROMOTE** (regresión permanente) | Cubre cada canal false-PASS (refusal, sin `run_id`, estado no exitoso, timeout, servidor colgado, marcador ausente, refusal de output, status no documentado), el payload público y la selección del agregador; es fixture de lógica de veredicto y **no** acredita ejecución real ni Fabric |
