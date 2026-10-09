# Monaco, historial, IR Explorer y observabilidad

## UI

Web SPA React+TypeScript+Monaco. Layout con paneles ajustables: experiment explorer/snapshots, editor central, catálogo lateral opcional, panel inferior Console/Events/Problems/Results/Step Contract/IR. No abrir diez pestañas por defecto; atajos accesibles y layouts persistidos por Studio. No depender de CDN para UAT offline.

## La selección de código no siempre es ejecutable

Tres alcances: `WHOLE_PIPELINE`, `STEP_SCENARIO` (Step resuelto y contexto explícito), `STATIC_SELECTION` (para mostrar docs/diagnósticos, nunca ejecución Kotlin por defecto). `Run Selection` solo permitido cuando generador de escenario y clasificación de efectos producen request válido; rechazo tipado en caso contrario. No crear un intérprete de snippets paralelo a Kotlin host.

## Historial

Cada edición puede producir borrador (mutable, local), pero cada Run sella snapshot y perfiles. Historial basado en `SnapshotId` y `RunId` persistentes; no usar `localStorage` como única fuente de verdad. Permitir consultar runs antiguas tras cerrar el navegador y comparar explícitamente: código, plugin lock, runtime version, perfil, escenario y outcome.

## Consola progresiva

- Output Plane: cada Step/operación read por `OutputReadPort`/cursor. Paginación por `maxBytes` bounded y preservación exacta de bytes. stdout/stderr son canales diferenciados solo cuando el productor/almacenamiento garantiza separación; nunca inventar `stderr` leyendo un stream mezclado.
- Event Plane: secuencia de eventos independiente, categoría/step/stage/run IDs. Rechazos (`unknown event`, `cursor foreign`, `recovery not completed`) se muestran explícitos.
- SSE comunica progreso: los cursores autoritativos se consultan por endpoints read. Web desconectada no cancela run ni pierde datos durablemente confirmados.
- Output muy grande: virtualización, ventanas/páginas, ningún `Buffer` sin límite en frontend o servidor; detectar límites, indicar recorte visible sin truncar datos durables.
- Texto UTF-8 parcial: decoder incremental o API entrega bytes y consumidor decodifica por stream; evitar pérdida/reemplazo silencioso en splits multibyte.

## IR Explorer

Nodos obtenidos del compilador/IR canónico: Stage, Step, Block Step, metadatos, origen y transiciones. No renderizar un grafo de ejecución como si fuera traza real; usar fase `DESIGNED`, `COMPILED`, `ADMITTED`, `OBSERVED`. Mapear StepKey a metadata real sin requerir escritura de código fuente adicional.

## Seguridad de la presentación

Escape HTML/ANSI (ANSI parsing solo whitelisted), evitar XSS por logs maliciosos. No abrir enlaces de logs automáticamente; filesystem refs mediante API permitida. Enlace desde diagnóstico a source snapshot, no ruta insegura. Modal de autorización antes de attached/trusted run; mostrar efectos desconocidos como `UNKNOWN`.

## UX acceptance

UAT-004..008, UAT-011..012, UAT-025..026, UAT-035. AAT-OBS-001..008. Un `OutputRefusal` nunca equivale a página con 0 bytes; UI no oculta `RecoveryRequired` detrás de spinner infinito. Un evento terminal no implica que todos los output streams estén sellados si el contrato upstream aún no lo certifica.
