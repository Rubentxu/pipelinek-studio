# UAT / E2E / AAT mínimos y efímeros de F0–F4

Este documento define **oráculos de ciclo**, no scripts persistentes. Los scripts/materiales de ensayo se crean en `$TMPDIR` con `mktemp -d`, fuera de repositorios, y se eliminan al cerrar cada hito después de recoger evidencias y decidir `PROMOTE/REPLACE/RETIRE`. Únicamente regresiones pequeñas de garantías críticas permanecen en el módulo propietario, siguiendo `delivery-first-testing` y `ADR-018`.

## Tabla de mínimos por hito

| Hito | UAT positivo (uno) | E2E frontera (uno) | AAT adversarial (uno) | Regresión permanente recomendada |
|---|---|---|---|---|
| F0 | Dos inicios del mismo comando por cliente Fabric y misma identidad/resultado | gRPC socket→Fabric→PK real, datos de parámetros reflejados en trabajo | Timeout o 2 llamadas simultáneas, una sola ejecución y un solo efecto; reinicio fail-closed | `StartRunId` replay/dispatch atómico + regresión parámetros |
| F1 | Snapshot sellado Studio ejecutado en Fabric desde CLI/MCP | Studio distrib.→gRPC TLS→Fabric→PK; stdout y digest verificables | SHA falso, cambio de bytes mismo key, traversal, reinicio entre prepare/start | CAS SHA/permission atomicidad + FK vínculo Studio/Fabric |
| F2 | Usuario web ve stages/salida, cancela y reconecta | Studio SPA/VS Code→HTTP SSE→Fabric ObserveRun/ReadConsole→PK real | UTF-8 fragmentado, `ForeignConsole`, red caída, `Lost`, sin falso terminal | Cursores de hechos vs byte offsets, no synthetic terminal, TLS real |
| F3 | Dos variantes con snapshots distintos generan resultados distintos y comparables por `FabricRunId` | MCP/Web consultan catálogo real y proyección en misma ejecución | Plugin ausente o versión incompatible, respuesta input que no alcanza worker → `UNSUPPORTED` | ADT capabilities y outcome; input end-to-end solo cuando soportado |
| F4 | Consumidor sin checkout instala Studio y ejecuta contra Fabric remoto | Descargar EXACTO inventario release + dependencias Maven/SDK desde repos remoto, ejecutar & reiniciar | Falta JAR/transitivo/assets, digest erróneo, SDK obsoleto o TLS inválido → FAIL | Smoke pequeño de instalación y coherencia catálogo de assets |

## Oráculos detallados

### F0 — start idempotente y parámetros

- Preparar `.pipeline.kts` real, cuyo `sh` o step disponible escriba un marcador con creación exclusiva en una ruta controlada; no usar fixture que simula resultado ni tests de registry aislados como E2E. Confirmar sintaxis contra la versión PK sellada, no copiar snippets de versiones anteriores sin ejecutar.
- Abrir un servidor Fabric **real** empaquetado, con bundle PK oficial; efectuar dos llamadas `StartRun` idénticas con el **mismo** `StartRunId` a través de `FabricClient` en paralelo; esperar por estado/cursor observable, no sleep fijo. Las dos respuestas deben ser `Accepted` mismo RunId. El marcador de efecto debe existir una vez. Si el worker fue lanzado una sola vez pero el marcador no se escribió, no es PASS.
- Reiniciar Fabric, reenviar comando y comprobar mismo id sin otro marcador; cambiar parámetro con `commandId` antiguo devuelve `Conflict`, sin arranque.
- Mostrar el valor `Text`/`Flag`/`Choice` elegido en la salida real para probar entrega al runtime, no solo almacenamiento de admisión.
- AAT: matar servidor después de guardar el claim; si es imposible determinar si se lanzó, exigir `Lost`/estado incierto y cero relanzamientos automáticos.

### F1 — snapshot SHA

- Studio crea experimento, guarda snapshot UTF-8 con newline/acentos y digest registrado, hace PreparePipeline en servidor de otra carpeta/usuario/host, obtiene key y arranca con comando durable.
- Fabric verifica digest de esos **bytes**, devuelve un `FabricRunId`, y stdout/eventos prueban que se ejecutó el texto exacto. Reiniciar Fabric entre preparación y StartRun; comprobar que encuentra el contenido correcto.
- AAT: enviar fuente X con hash de Y debe rechazarse; mismo key+otro contenido no modifica versión; anular permiso a tenant ajeno; `../` y symlink fuera de raíz no funcionan.
- Si la operación no existe, el resultado es `BLOCKED` con falta de RPC; la prueba no se sustituye por escribir un archivo manualmente en `scriptsRoot`.

### F2 — live y read-side

- Pipeline de dos stages: primer stage emite salida y bloquea en señal observable, segundo finaliza después. Dos consumidores simultáneos deben ver primer hecho/console **antes** de liberar la señal. Si hechos solo aparecen al final, live es FAIL/UNSUPPORTED (no verde por GetRun polling).
- Leer la consola fragmentando una secuencia UTF-8 multibyte entre páginas, continuar por `nextByteOffset`. Recuperar desde última página/cursor después de cerrar navegador y reiniciar Studio. Sin duplicados ni pérdida.
- `CancelRun` devuelve aceptación de intención; Studio no representa terminal hasta `RunState.Terminal` real. Desconectar red no cancela run.
- AAT: `ForeignConsole` y cursor de otro run/ref debe dar rechazo, no 0; evento desconocido no genera outcome.

### F3 — capacidades, comparaciones e interacciones

- Ejecutar snapshot A y snapshot B con salidas distinguibles, comparar por dos FabricRunId conservando sus respectivos digests. No usar `StudioRunId` para buscar hechos Fabric.
- Leer catálogo del worker PK auténtico y contrastar un step externo instalado frente a step ausente; solo exponer capacidad cuando Fabric la ha publicado.
- Si `interaction.resolve` está marcado activo: PipelineK solicita input, el run entra Waiting, Studio responde por gRPC y el worker continúa a Terminal; si falla **NO** etiquetar activo.
- AAT: protocolo/vocabulario nuevo en una versión incompatible devuelve `UnsupportedVersion/UNSUPPORTED`, no capacidades inventadas.

### F4 — distribución real

- Instalar desde release Studio y Fabric publicados (no checkout); resolver `FabricClient` y transitivos desde Maven remoto, PK desde distribución sellada. Arrancar Studio por sus executables reales, no clases TestKit.
- Leer release asset inventory requerido, contar completitud **y** validar hashes; deshabilitar `mavenLocal`; smoke TLS con CA correcta y CA incorrecta.
- Registrar SHA Git remoto, tag, digest de cada binario, versión SDK y resultado instalación/ejecución/consulta. Si falta un JAR/POM/imagen, no aceptar que SHA256SUMS de activos restantes dé PASS.

## Ciclo de vida temporal por hito

```sh
# Ejemplo de ciclo, NO es una prueba ya implementada ni se ejecuta solo.
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/studio-fabric-Fn.XXXXXXXX")"
chmod 700 "$RUN_DIR"
# Crear fixtures/scripts en RUN_DIR, invocar binarios empaquetados,
# guardar salida y estado, clasificar con oráculos anteriores.
# Al cierre, registrar hashes+resultados (sin secretos); decidir PROMOTE/REPLACE/RETIRE;
# borrar el directorio aislado de ensayo tras decidir. No versionar RUN_DIR.
```

## Dónde debe permanecer cada test

**Obligatorias y permanentes solo si no existe cobertura equivalente:**

1. Fabric core: ley atómica de `StartRun` y dispatch bajo concurrencia/crash/recovery.
2. Fabric content catalog: SHA e invariantes de autorización/path/traversal y persistencia.
3. Fabric contratos: codec Protobuf y `NorthboundOperation` ACL para RPC nuevo + compat N/N-1.
4. Fabric observación: conformance cursor y `ConsoleRef` scoped + `Unstable`/`Lost`.
5. Studio dominio: mappings ADT e idempotencia persistente.
6. Un smoke cross-repo consumer de distribución en RC, no veinte variantes de UAT por feature.

**Retirar por defecto si reemplazadas:** scripts de UAT `rc.11` obsoletos, e2e con `|| true`/`if failed: ok`, validación `health=ok` cuando API retorna JSON, harnesses que ejecutan mismos recorridos en varios shells, inventarios de archivo que no cubren riesgo observable. Cualquier borrado exige comparar garantía antes/después, ensayo de falla discriminante y autorización del mantenedor.

## Documento mínimo de evidencia (uno por hito)

`hito, repo, head_sha, release_tag, artifact_sha256, dataset, consumidor, oraculo, comandos_reales, started, finished, PASS/FAIL/BLOCKED/NOT_RUN, defectos, tests_promovidos, tests_retirados, motivo, siguiente_paso`; preservar solo datos no sensibles en recibo corto o SDDK, y output pesado en almacenamiento efímero controlado hasta cerrar.
