# Especificación UX — Interactive Console y colaboración con agentes

## Referencia Groovy Console: adoptar y adaptar

1. Editor + ejecutar código → Studio Scratchpad de `.pipeline.kts`.
2. Ejecutar selección → **NO** ejecutar Kotlin suelto como pipeline. Preparar un escenario contextual tipado y validado o rechazar con instrucciones. S4.
3. Output y valor del script → paneles separados para stdout/stderr, eventos, resultado tipado y outcome.
4. Historial → snapshots y runs inmutables, comparables por contenido/versiones/perfil.
5. AST Browser → IR Explorer: origen→DSL→IR→Step registry→runtime; diferencia «proyectado» de «confirmado».
6. Interrumpir → solicitud de cancelación durable, estado observado, sin inventar aborto terminal.

## Pantalla MVP

```text
┌ Experimentos / Snapshots ────────┬ Monaco · demo.pipeline.kts ──────────────────────────┐
│ demo   snap-a   snap-b          │ [Analyze*] [Run] [Stop] [Inspect] [Compare*]           │
│                                 │ pipeline { stages { stage("Build") { ... } } }         │
├ Catálogo de Steps ───────────────┴──────────────────────────────────────────────────────┤
│ [Console] [Events] [Problems] [Results] [Steps]                                       │
│ Build → sh   SUCCEEDED · stdout: Hola                                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
* solo habilitar si implementado y seguro. Fuente única de la verdad: API de Studio.
```

## Flujos normativos

**UX-01: run desde Monaco.** Editar crea borrador local; pulsar Run solicita snapshot inmutable (o usa uno ya sellado), muestra perfil/efectos permitidos, invoca `createRun` idempotente y navega por `runId`. Nunca ejecuta automáticamente por guardar archivo. Dos pulsaciones sobre la misma clave no duplican run.

**UX-02: historial.** Una run referencia `sourceSnapshotId`, runtime digest, plugin set digest, escenario, workspace, control root y reportes. `View Source` enseña exactos bytes de origen. Comparación no mezcla runs con distintos contratos sin advertencia.

**UX-03: diagnóstico.** Clasificar `SYNTAX`, `COMPILATION`, `DSL_CONSTRUCTION`, `ADMISSION`, `RUNTIME`, `RECOVERY`, `INTEGRATION`, `LINT`. Líneas/columnas solo cuando se conocen. Errores sobre origen virtual se mapean al snapshot; no simular ubicación.

**UX-04: observación.** Vista de etapas basada en eventos; logs obtenidos desde Output Plane por su cursor independiente. SSE es notificación/hint; si falla, API paginada recupera estado y cursores. Evento sin output no equivale a byte perdido.

**UX-05: revisión de propuestas de agente.** El agente crea snapshot variante, comunica `proposedFrom`, diferencias, pruebas ejecutadas, evidencias y límites. El usuario puede aceptarlo/copiarlo; nunca aplica automáticamente cambios al repositorio original.

**UX-06: Plugin Lab.** Ver la identidad y el digest del JAR/manifest antes de ejecutar código. No prometer formulario dinámico si `StepCodec.schema()` es `{}`. Ofrecer JSON tipado de entrada y descripción de contrato como fallback.

## Accesibilidad y calidad

- Navegación por teclado; semántica ARIA; contraste AA; mensajes de error accesibles; no depender del color para PASS/FAIL/UNKNOWN.
- Logs masivos virtualizados/paginados; nunca acumular stdout completo en memoria DOM.
- Source editor nunca envía cambios en cada pulsación para ejecutarlos; autoguardado opcional de borrador sin efectos.
- Consola y eventos con límites explícitos, redacción probada y escape seguro del texto mostrado.
- Offline local durante ejecución del corpus base: dependencias de JS empaquetadas, no CDNs.

## UX de estados

| Estado | Presentación | Acción ofrecida |
|---|---|---|
| `QUEUED` | programado, sin éxito supuesto | consultar/cancelar |
| `RUNNING` | operación viva | inspeccionar/cancelar |
| `SUCCEEDED` | evidencia terminal verificada | ver resultados |
| `FAILED` | fallo tipado real | inspeccionar paso |
| `CANCEL_REQUESTED` | aún no terminal | esperar/consultar |
| `CANCELLED` | confirmado como terminal | crear nuevo snapshot/run |
| `RECOVERY_REQUIRED` | no inventar terminal | reconciliar con permiso |
| `REFUSED` | admisión, seguridad o contrato | ver motivo/acciones |
| `UNKNOWN` | no observable | reconsultar, no auto-replay |

## Ideas futuras, sin condicionamiento inicial

Diff del workspace, editor de reglas Detekt, gráfica de IR, inspector de cabeceras/admisión, catálogo de ejemplos curados, comparador visual de runs, replay documental de eventos y vistas especializadas Policies/Assurance.
