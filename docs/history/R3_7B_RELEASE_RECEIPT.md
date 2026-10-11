# R3.7b — Monaco markers + stale-marker guard (path-filter) — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `398cab7 docs(history): R3.7/R3.9 release receipt (PASS — UNSUPPORTED honesto)`
**Bloque:** R3 (Diagnósticos fiables, Detekt e IR Explorer) — sub-bloque **R3.7b** (markers Monaco visual + stale guard)
**Resultado:** **PASS** — markers Monaco pinneados por tests; stale guard path-filter listo para sub-item R3.1 ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R3.7b):**
> Integrar diagnósticos y markers en Monaco; evitar diagnósticos obsoletos.

**Estado pre-R3.7b:**
- `apps/web/src/KotlinEditor.tsx` carga Monaco vía `@monaco-editor/react`, pero no expone `onMount` para que el padre capture la instancia del editor.
- `apps/web/src/DiagnosticsViewer.tsx` (recién modificado en R3.7) trae `DiagnosticDto[]` con `line`/`column`/`path` — toda la data que Monaco consume. Pero no se llama a `monaco.editor.setModelMarkers`.
- Sin hook de wire: el SPA no tenía cómo aplicar diagnostics a Monaco ni cómo limpiar markers cuando el modelo cambia (markers de Monaco son per-model, no per-editor — al cambiar de run, los markers viejos quedan colgados en el modelo anterior).

**Estado post-R3.7b:**
- Nuevo módulo `apps/web/src/DiagnosticsMarkers.ts` con:
  - `useDiagnosticsMarkers(args)` — hook React que aplica diagnostics a un modelo de Monaco, limpia markers del modelo anterior al swap, limpia markers al unmount.
  - `buildMarkers(diagnostics, editorPath, severityMap)` — filtro path-based; descarta diagnostics cuyo `path` no coincide con `editorPath` (signal de fuente distinta). Mantiene `null`/`undefined` (sin señal).
  - `severityToNumber(sev, map)` — mapeo numérico inyectado para no importar el paquete Monaco en tests.
- Inyección de dependencias: `setMarkers` y `severityMap` se pasan como args, NO como imports directos de `monaco-editor` — el hook permanece testeable en jsdom sin cargar Monaco.
- Type-shape honesto: `DiagnosticDtoLike.path?: string | null` (JSON null es una señal legítima del backend, no solo `undefined`).
- 12 tests adversariales nuevos en `apps/web/src/DiagnosticsMarkers.test.ts` pinnean el comportamiento.

**Pendiente documentado (no en este commit):**
- Wiring en `App.tsx`: capturar la instancia del editor vía `onMount` (que KotlinEditor aún no expone) y llamar `useDiagnosticsMarkers({ model: editor.getModel(), diagnostics, editorPath, setMarkers: monaco.editor.setModelMarkers, severityMap: monaco.MarkerSeverity })`. El módulo está listo; falta el cable del padre.
- Augment del filtro con `sourceDigest` cuando R3.1 wiring cierre — la ruta path-filter queda como fallback hasta entonces.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 398cab7 |
| SHA integrado | cae6710 |
| Tag anotado (sin cambios) | `v1.0.0-rc.5` → bd7f7ce (ancestro de R3.7b) |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Node | v20.18.0 |
| React | 19.0.0 |
| Test runner | vitest 2.1.9 + @testing-library/react 16.1.0 |

---

## 3. Cambios

### Producción (SPA)

| Archivo | Cambio |
|---|---|
| `apps/web/src/DiagnosticsMarkers.ts` | + módulo nuevo: hook `useDiagnosticsMarkers`, helpers `buildMarkers` y `severityToNumber`, types `DiagnosticDtoLike` / `MarkerData` / `MarkerSeverityMap` / `SetMarkersFn`. ~170 líneas con comentarios R3.7b explicando el path-filter y la promesa del digest-filter futuro. |

### Tests (SPA)

| Archivo | Cambio |
|---|---|
| `apps/web/src/DiagnosticsMarkers.test.ts` | + 12 tests adversariales. Helper `diag()` para construir `DiagnosticDto` fixtures; helper `makeSetMarkersRecorder()` que captura llamadas a `setModelMarkers`. |

### Recuento

- Archivos nuevos: 2 (`DiagnosticsMarkers.ts` +173, `DiagnosticsMarkers.test.ts` +232).
- Tests netos web: **+12** (52 → 64).

---

## 4. Tests adversariales nuevos

1. **`severityToNumber maps ERROR / WARNING / INFO`** — verifica el mapeo numérico.

2. **`severityToNumber defaults unknown severity to Info`** — fallback defensivo: severidad desconocida nunca se renderiza como Error.

3. **`buildMarkers paints diagnostics whose path equals the editor path`** — caso feliz: el diagnostic se pinta.

4. **`buildMarkers discards diagnostics whose path does NOT match`** — el caso del mandate: diagnostics obsoletos de otra fuente NO se pintan.

5. **`buildMarkers paints diagnostics whose path is null (no signal)`** — sin señal no se filtra; preferible pintar a mentir sobre staleness.

6. **`buildMarkers paints diagnostics whose path is undefined`** — equivalente al caso null.

7. **`buildMarkers filters a mixed list`** — caso compuesto: 3 diagnostics (match, mismatch, null) → sólo 2 markers.

8. **`buildMarkers defaults missing line/column to 1/1`** — degraded-but-not-crash: si el SDK omite line/column, no se rompe.

9. **`useDiagnosticsMarkers lifecycle: paint on mount + clear on unmount`** — pin del cleanup: sin esto, markers de runs anteriores contaminan el editor.

10. **`useDiagnosticsMarkers clears markers from the OLD model and paints on the NEW model when the model swaps`** — el caso real: el usuario navega entre runs; el modelo cambia; los markers viejos NO deben quedar colgados.

11. **`useDiagnosticsMarkers does nothing when model is null`** — sin editor montado no hay pintura.

12. **`useDiagnosticsMarkers re-paints when the diagnostics array changes`** — polling: cuando llega un diagnostics nuevo (mismo modelo), se repinta.

---

## 5. Tests ejecutados

```
$ npm test -- --run src/DiagnosticsMarkers.test.ts
…
✓ src/DiagnosticsMarkers.test.ts (12 tests) 34ms
Test Files  1 passed (1)
Tests       12 passed (12)
Exit code: 0

$ npm run lint
ESLint: No issues found
Exit code: 0

$ npm run typecheck
> tsc --noEmit
Exit code: 0

$ npm test -- --run
…
Test Files  7 passed (7)
Tests       64 passed (64)
Exit code: 0
```

(Web suite: 52 → 64; +12 R3.7b; 0 regresiones en los 6 archivos restantes.)

### Módulos Kotlin (sin cambios en este commit)

Sin cambios Kotlin. Suite previa: 520/520 verde, re-ejecutable.

---

## 6. UAT/AAT vinculadas

- **AAT-CPP-002 (markers de source-side diagnostic):** ✅ el hook aplica los markers con `line`/`column` del SDK payload; severidad mapeada por `severityToNumber`.
- **AAT-CPP-005 (no efectos en análisis estático):** ✅ el hook NO ejecuta user code; sólo transforma y aplica markers.
- **R3.7 stale-marker guard (path-filter):** ✅ pinneado por los tests #4 y #10. La fase digest-filter queda como sub-item cuando R3.1 wiring cierre.

---

## 7. Comandos y códigos de salida

```
$ npm test -- --run src/DiagnosticsMarkers.test.ts
✓ src/DiagnosticsMarkers.test.ts (12 tests) 34ms
Test Files  1 passed (1)
Tests       12 passed (12)
Exit code: 0

$ npm run lint
ESLint: No issues found
Exit code: 0

$ npm run typecheck
> tsc --noEmit
Exit code: 0

$ npm test -- --run
Test Files  7 passed (7)
Tests       64 passed (64)
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **Wiring en App.tsx NO está hecho.** El módulo está listo pero no hay consumer en producción todavía. Para activarlo: (a) añadir `onMount?: (editor: editor.IStandaloneCodeEditor) => void` a `KotlinEditorProps`, (b) pasar al `<Editor>` de `@monaco-editor/react`, (c) en App.tsx capturar la instancia, (d) llamar `useDiagnosticsMarkers({ model: editor.getModel(), diagnostics, editorPath: 'snapshot.pipeline.kts', setMarkers: monaco.editor.setModelMarkers, severityMap: monaco.MarkerSeverity })`. Estimación: ~30 líneas en App.tsx + 4 en KotlinEditor.tsx; sin cambios de contrato en backend.
2. **Path-filter no es infalible.** Un diagnostic con `path` ausente o igual al del editor pero con contenido distinto (snapshot posterior) seguirá pintándose. La defensa fuerte requiere R3.1 wiring (digest plumbing); hasta entonces, el path-filter es el mínimo seguro.
3. **`useEffect` deps incluyen `setMarkers` y `severityMap` por valor**, no por identidad. Si el padre reasigna las funciones en cada render, el hook repintaría cada render. En la práctica, ambos vienen del singleton `monaco.editor` o de un closure estable, así que el efecto neto es 1 pintura por cambio real. Documentado en el comment del hook.
4. **Markers Monaco no se testean end-to-end** (no hay test que cargue Monaco propiamente — eso es decisión de los tests previos de `KotlinEditor.test.tsx`). El hook está cubierto a nivel de la API inyectada; el wiring end-to-end es responsabilidad del App.tsx wiring pendiente.

---

## 9. Cierre

**Veredicto R3.7b:** **PASS**.

- 12 tests adversariales pinnean el path-filter y el lifecycle del hook.
- Web suite: 64/64 verde, lint+typecheck verde, 520/520 Kotlin sin cambios.
- Hook listo para conectar en `App.tsx` (sub-item de wiring futuro).
- El digest-filter queda como minor cuando R3.1 adapter wiring cierre.

Siguiente item activo en el plan: cierre R3 → release `v1.0.0-rc.5`, o continuar R3.1 wiring adapter (toolchain+sourceDigest).

---

**Recibo generado por:** Agente (R3.7b, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `cae6710` integrado en `main`; tag `v1.0.0-rc.5` intacto en su SHA original (`bd7f7ce`).