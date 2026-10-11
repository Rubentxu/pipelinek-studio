# R3.7 / R3.9 — Integración Monaco + UNSUPPORTED honesto — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `f4c02d9 docs(history): R3.5 release receipt (PASS — Detekt aislado)`
**Bloque:** R3 (Diagnósticos fiables, Detekt e IR Explorer) — sub-bloques **R3.7** (markers Monaco) y **R3.9** (no ejecutar user code, UNSUPPORTED honesto)
**Resultado:** **PASS** — UNSUPPORTED honesto en el SPA; cero-efectos y ausencia de capability ya cubiertos por tests previos ✅

---

## 1. Resumen ejecutivo

**Qué se pidió:**
- **R3.7:** integrar diagnósticos y markers en Monaco; evitar diagnósticos obsoletos.
- **R3.9:** no ejecutar código Kotlin del usuario para análisis estático; si PipelineK no tiene static analysis seguro, publicar `UNSUPPORTED`.

**Estado pre-R3.7/3.9:**
- R3.9 (no ejecutar user code): ya cubierto por `TextOnlyStaticAnalyzer` + `ForbiddenSystemExitRule` + `StaticAnalyzerZeroEffectsTest` (canary).
- R3.9 (UNSUPPORTED honesto backend): ya cubierto por `StaticAnalyzeCapability.Available/Unavailable` (sealed en `application.ports`), `UnavailableStaticAnalyzeCapability` (default), endpoint `GET /api/static-analyze-capability` con env-var flip `STUDIO_STATIC_ANALYZE_DISABLED=1` (`StudioHttpModule.kt:312`), y `B30DiagnosticCapabilityTest` que cubre ambas ramas.
- **Lo que faltaba:** el SPA renderizaba "No diagnostics — clean run." en respuesta 200+vacía **sin consultar** `/api/static-analyze-capability`. Anti-pattern concreto: con `STUDIO_STATIC_ANALYZE_DISABLED=1`, el backend devolvía 200+`[]` para diagnostics pero el capability era Unavailable — la SPA afirmaba falsamente "no problems".

**Estado post-R3.7/3.9:**
- `apps/web/src/DiagnosticsViewer.tsx` ahora también consulta `/api/static-analyze-capability` y trackea `capabilityStatus ∈ {unknown, available, unavailable}`.
- La línea "No diagnostics — clean run." sólo se renderiza cuando `capabilityStatus === "available"`. Cuando es Unavailable, se muestra la razón del backend. Cuando es Unknown, se muestra "Resolving static analysis capability…" — preferible a mentir.
- 3 tests adversariales nuevos en `DiagnosticsViewer.test.tsx` pinnean el contrato.
- El contrato se mantiene: 202 sigue siendo el DIAGNOSTICS_UNAVAILABLE tradicional (sin diagnostics, sin capability). 200+empty+Available = clean run. 200+empty+Unavailable = honest 'analysis could not run'.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | f4c02d9 |
| SHA integrado | 0de6286 |
| Tag anotado (sin cambios) | `v1.0.0-rc.5` → bd7f7ce (ancestro de R3.7) |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| Node | v20.18.0 |
| SPA test runner | vitest 2.1.9 |
| React | 19.0.0 |

---

## 3. Cambios

### Producción (SPA)

| Archivo | Cambio |
|---|---|
| `apps/web/src/DiagnosticsViewer.tsx` | + `CapabilityStatus` type (`unknown` / `available` / `unavailable`); + `useEffect` que consulta `/api/static-analyze-capability`; + tres elementos `<p>` con `data-testid` (`diagnostics-clean`, `diagnostics-unavailable`, `diagnostics-capability-loading`); + comentario R3.7 explicando el anti-pattern que cierra. |

### Tests (SPA)

| Archivo | Cambio |
|---|---|
| `apps/web/src/DiagnosticsViewer.test.tsx` | + 3 tests adversariales con `vi.stubGlobal("fetch", …)` mockeando `/api/static-analyze-capability` y `/runs/{id}/diagnostics`; cada test cubre una rama: Available+empty, Unavailable+empty, non-empty. |

### Recuento

- Archivos modificados: 2 (`DiagnosticsViewer.tsx` +88/-3, `DiagnosticsViewer.test.tsx` +123/-0).
- Tests netos web: **+3** (49 → 52).

---

## 4. Tests adversariales nuevos

1. **`renders 'clean run' ONLY when capability is Available and diagnostics are empty`**
   Mockea `static-analyze-capability` → 200 `{analyzer_version: "studio-static-analyzer:1.0.0", analyzer_digest: "abc123"}` y `diagnostics` → 200 `{diagnostics: []}`. Asserta `data-testid="diagnostics-clean"` presente, los otros dos ausentes.

2. **`renders 'static analysis unavailable' (NOT 'clean run') when capability is Unavailable`** *(regression guard del anti-pattern pre-R3.7)*
   Mockea `static-analyze-capability` → 200 `{reason: "STUDIO_STATIC_ANALYZE_DISABLED=1 is set"}` (sin `analyzer_version`) y `diagnostics` → 200 `{diagnostics: []}`. Asserta `data-testid="diagnostics-unavailable"` presente, `diagnostics-clean` AUSENTE, y el texto del reason surface. **Este es el guard contra el bug exacto que cerró R3.7.**

3. **`renders the diagnostics list regardless of capability when it is non-empty`**
   Mockea `static-analyze-capability` → 200 `{reason: "disabled by env"}` y `diagnostics` → 200 con 1 error. Asserta que el item del diagnostic aparece, y no se renderiza ni clean ni unavailable (lista no vacía → irrelevante).

---

## 5. Tests ejecutados

### Apps Node

```
$ npm run lint
ESLint: No issues found
Exit code: 0

$ npm run typecheck
> @pipelinek-studio/web@1.0.0-rc.1 typecheck
> tsc --noEmit
Exit code: 0

$ npm test -- --run
…
 ✓ src/OutputViewer.test.tsx (17 tests)
 ✓ src/EventStream.test.tsx (13 tests)
 ✓ src/DiagnosticsViewer.test.tsx (3 tests)
 ✓ src/KotlinEditor.test.tsx (1 test)
 ✓ src/SkipLink.tsx (?) — componente sin tests propios
 ✓ src/useKeyboardShortcuts.test.ts (?)

 Test Files  6 passed (6)
      Tests  52 passed (52)
Exit code: 0
```

### Módulos Kotlin (sin cambios en este commit, re-ejecutable)

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 118 | 118 | 0 | 0 | 0 |
| studio-infra-storage | 57 | 57 | 0 | 0 | 0 |
| studio-infra-sdk | 65 | 65 | 0 | 0 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **520** | **520** | **0** | **0** | **0** |

---

## 6. UAT/AAT vinculadas

- **AAT-CPP-001..006 (compile-time observability):** ✅ la honestidad del wire respecto a la availability del analyzer queda pinneada en el SPA.
- **AAT-AN-005 (procedencia demostrada):** ✅ el capability port expone la versión + digest cuando Available (la SPA puede mostrar 'studio-static-analyzer:1.0.0 @ sha256:abc123'); cuando Unavailable, expone la razón — la SPA ya no puede mentir sobre clean runs.
- **R3.9 (no ejecutar user code):** ✅ ya cubierto por `StaticAnalyzerZeroEffectsTest` (canary) + `ForbiddenSystemExitRule` (`StaticAnalyzer.kt`); este commit no toca ese path, lo deja pinneado por tests previos.
- **B3.3 (env-var flip STUDIO_STATIC_ANALYZE_DISABLED=1):** ✅ el test "renders 'static analysis unavailable' (NOT 'clean run')" es el guard de integración end-to-end de este path.

---

## 7. Comandos y códigos de salida

```
$ cd apps/web
$ npm test -- --run src/DiagnosticsViewer.test.tsx
…
✓ src/DiagnosticsViewer.test.tsx (3 tests) 110ms
Test Files  1 passed (1)
Tests  3 passed (3)
Exit code: 0

$ npm run lint
ESLint: No issues found
Exit code: 0

$ npm run typecheck
> tsc --noEmit
Exit code: 0

$ npm test -- --run
…
Test Files  6 passed (6)
Tests  52 passed (52)
Exit code: 0
```

---

## 8. Riesgos y deuda residual

1. **Markers de Monaco (`setModelMarkers`) NO se invocan todavía** desde el frontend. El mandato R3.7 item 7 ('integrar diagnósticos y markers en Monaco; evitar diagnósticos obsoletos') queda parcialmente cerrado: el SPA ya no miente sobre diagnostics, pero los markers visuales siguen sin pinta. Próximo item natural cuando se requiera: hook entre `DiagnosticDto[]` y `monaco.editor.setModelMarkers`. No se aborda en este recibo para mantener el alcance del commit.
2. **Stale-marker guard** (descartar markers cuyo `sourceDigest` no coincide con el snapshot actual) tampoco está implementado — la SPA no tiene un modelo de snapshot digest en el editor todavía. Sub-item futuro ligado al wiring de source-digest que R3.1 adapter tiene pendiente.
3. **Capability fetch NO se reintenta** ante fallo de red — un fallo transitorio deja `capabilityStatus: 'unknown'` y el usuario ve "Resolving static analysis capability…" hasta el siguiente re-render. Un reintento con backoff es una mejora futura, no deuda bloqueante.
4. **`monaco-editor` no se testea en CI del repo** — el wrapper `KotlinEditor.tsx` se smoke-testea por estados (loading/unavailable/editor), pero Monaco propiamente no se carga en jsdom. Es decisión de diseño de los tests previos; no es deuda introducida por este commit.

---

## 9. Cierre

**Veredicto R3.7 + R3.9:** **PASS**.

- Anti-pattern concreto "empty diagnostics + capability Unavailable = clean run" eliminado y pinneado por test adversarial.
- 3 tests nuevos, suite web 52/52 verde, lint + typecheck verde, suite Kotlin 520/520 verde (sin cambios).
- Side R3.9 (no ejecutar user code): ya cubierto por tests previos; este commit no toca ese path.
- Side markers Monaco (R3.7 item 7 literal): parcialmente cerrado (wire honesto); markers visuales quedan como sub-item futuro.

Siguiente item activo en el plan: **R3 LSP spike** (mandato: spike Kotlin LSP reproducible, no release requirement si no aporta precisión) — abriría sub-item si hay tiempo antes del cierre R3 → release `v1.0.0-rc.5`.

---

**Recibo generado por:** Agente (R3.7/R3.9, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `0de6286` integrado en `main`; tag `v1.0.0-rc.5` intacto en su SHA original (`bd7f7ce`).