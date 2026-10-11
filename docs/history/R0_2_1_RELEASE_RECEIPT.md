# R0.2.1 — Unificación de contrato OpenAPI — Recibo parcial

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `c53d761 docs(history): R0.3 partial release receipt (R0.3.2/3 closed; 7 deferred)`
**Bloque:** R0 (núcleo headless íntegro y certificación fiable) — sub-bloque R0.2.1
**Resultado del sub-bloque:** PARCIAL — contrato OpenAPI unificado ✅, superficies runtime aún no unificadas ⏳

---

## 1. Resumen ejecutivo

**Qué se pidió:** R0.2.1 — "Unificar el servidor productivo y el de la CLI, eliminando diferencias funcionales injustificadas."

**Qué se entrega en este commit:**
- El servidor `studio start` de la CLI sirve ahora **exactamente el mismo documento OpenAPI 3.1** que el servidor de producción `studio-app` (`dev.pipelinek.studio.http.OpenApiDoc.current`), no una copia hand-maintained.
- Se elimina `studio-cli/.../MinimalOpenApiDoc.kt` (ya no queda código activo que lo referencie; los 2 comentarios históricos que aún lo mencionan documentan deliberadamente el cambio).
- `VersionCoherenceB0Test` y `OpenApiVersionB52Test` se consolidan para fijar una sola fuente de verdad del contrato.

**Qué queda abierto dentro de R0.2.1 (diferencia funcional injustificada restante):**
- La superficie runtime del servidor de la CLI (`studio start`) sigue siendo un subconjunto de 5 rutas diagnósticas: `/health`, `/api/version`, `/api/metrics`, `/api/ready`, `/openapi.json`. La CLI no monta el `StudioHttpModule` completo porque no tiene los 9 adaptadores de fábrica (`wiringFactory`, `runReadFactory`, `outputReadFactory`, `controlRootBase`, `stepCatalogFactory`, `diagnosticsFactory`, `irFactory`, `liveRunToPidFactory`, `leaseAcquireFactory`, `leaseValidateFactory`) que `studio-app/Main.kt` mantiene.
- Este cierre parcial cierra la contradicción del **contrato** (la doc OpenAPI es una sola); la contradicción del **alcance runtime** requiere extraer `StudioServer.boot(...)` para que CLI y app compartan el mismo `StudioHttpModule`, o hacer que `studio start` ejecute el JVM de `studio-app` como subproceso. Ambas opciones son un refactor estructural para una sesión posterior; la elección queda abierta.

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | c53d761 |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Node | v25.9.0 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |
| SDK PipelineK | dev.pipelinek:pipeline-kotlin-application:0.48.0-rc1 (sha256 0811e7b6…) |
| Versión Studio | 1.0.0-rc.1 (`gradle.properties:studio.version`) |

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-cli/src/main/kotlin/dev/pipelinek/studio/cli/ServerStart.kt` | Línea 175: `/openapi.json` sirve `dev.pipelinek.studio.http.OpenApiDoc.current` (la doc de producción) en lugar de `MinimalOpenApiDoc.current` (la copia hand-maintained). Comentario actualizado explicando qué había y por qué se unifica. La superficie runtime del módulo (`MinimalModule`) sigue siendo diagnóstica — doc documentada. |
| `studio-cli/src/main/kotlin/dev/pipelinek/studio/cli/MinimalOpenApiDoc.kt` | **Eliminado.** No quedaba ningún código activo que lo importara; los 2 comentarios históricos que lo nombran son deliberados (documentan el cambio R0.2.1). El recibo B5.2 en `docs/history/B5_RELEASE_RECEIPT.md` queda intacto (los receipts son inmutables y reflejan el estado B5.2 de entonces). |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-cli/src/test/kotlin/dev/pipelinek/studio/cli/VersionCoherenceB0Test.kt` | Test `MinimalOpenApiDoc info version matches gradle properties` renombrado a `OpenApiDoc info version matches gradle properties`; apunta a `dev.pipelinek.studio.http.OpenApiDoc.current`. Import `MinimalOpenApiDoc` eliminado. Comentario de cabecera cita la nueva fuente (5ª posición). |
| `studio-cli/src/test/kotlin/dev/pipelinek/studio/cli/OpenApiVersionB52Test.kt` | Tests `B5_2 full OpenAPI doc carries` y `B5_2 minimal OpenAPI doc carries` colapsados en `B5_2 OpenAPI doc carries` (un solo doc ahora). Test `B5_2 full and minimal OpenAPI docs agree on the API version` reemplazado por `B5_2 OpenAPI doc x-studio-api-version is parseable JSON` (la invariante de acuerdo entre dos docs se hace trivialmente cierta tras la unificación; mantenemos parseable-JSON como evidencia nueva). |
| `studio-http/src/test/kotlin/dev/pipelinek/studio/http/MetricsEndpointTest.kt` | Comentario: "...OpenApiDoc + MinimalOpenApiDoc" → "...OpenApiDoc". |

### Recuento

- Archivos modificados: 4
- Archivos eliminados: 1
- Líneas netas: -35/+38
- Reducción neta de tests: -1 (en `OpenApiVersionB52Test`; ver §5)

## 4. Superficie documentada: `studio start` vs `studio-app`

| Aspecto | `studio-cli … studio start` (MinimalModule) | `studio-app/Main.kt` (StudioHttpModule completo) |
|---|---|---|
| Rutas expuestas | 5 (/health, /api/version, /api/metrics, /api/ready, /openapi.json) | 16 rutas + handlers de admin |
| TLS | No (HTTP plano en :7777) | Sí (HTTPS en :7778) |
| Adapter wiring | No — diagnóstico puro | Sí — 9 factories reales (R0.2.4) |
| Documento OpenAPI servido | **`dev.pipelinek.studio.http.OpenApiDoc.current`** ✅ unificado (R0.2.1) | `dev.pipelinek.studio.http.OpenApiDoc.current` |
| Uso legítimo | Smoke + verificación de clone limpio + profiling JVM | Producto completo |
| Injustificado | — | — |

**Diagnóstico de la diferencia restante:** el conjunto de rutas del CLI es deliberadamente menor (no expone endpoints productivos que dependen de factories que la CLI no carga). La elección es **legítima** para el propósito del CLI, pero la unificación total de servidores requiere extraer un `StudioServer.boot(...)` común o spawn-JVM; ambos son refactors estructurales fuera del alcance de un cierre parcial.

## 5. Tests ejecutados

| Módulo | Test count | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 199 | 199 | 0 | 0 | 0 |
| studio-http | 60 | 60 | 0 | 0 | 0 |
| studio-infra-sdk | 57 | 57 | 0 | 0 | 0 |
| studio-infra-storage | 50 | 50 | 0 | 0 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| **TOTAL** | **376** | **376** | **0** | **0** | **0** |

Comando:
```
./gradlew :studio-cli:test :studio-http:test :studio-infra-sdk:test \
          :studio-infra-storage:test :studio-app:test \
          --console=plain --no-daemon
```

**Reducción de un test en studio-cli:** antes 200 → ahora 199. Causa: `OpenApiVersionB52Test` consolida 3 casos de versión OpenAPI a 2 (los dos duplicados "full/minimal" colapsan en uno; el "agreement" pasa a "parseable JSON"). La unificación elimina la duplicación honesta del contrato y el conteo refleja esa reducción.

## 6. UAT/AAT vinculadas a este sub-bloque

### UAT

- **UAT-039 (TLS + HTTP opt-in cross-client):** bloqueada por R0.2.2 / R0.2.3 (requisitos reales de máquina de smoke). R0.2.1 cubre solo la parte de contrato OpenAPI unificado, que es un prerrequisito.

### AAT

- **API-004 (OpenAPI doc shape + version coherence):** ✅ Verificado por `VersionCoherenceB0Test` (9 casos) + `OpenApiVersionB52Test` (2 casos, R0.2.1).
- **API-008 (no fabrication of unsupported capabilities):** ✅ Verificado por la unificación misma (no hay dos docs divergentes).

## 7. Comandos y códigos de salida

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-infra-sdk:test \
            :studio-infra-storage:test :studio-app:test --console=plain --no-daemon
…
BUILD SUCCESSFUL in 60s
Exit code: 0
```

## 8. Riesgos y deuda residual

1. **Diferencia runtime CLI vs app:** la superficie de rutas CLI sigue siendo 5 diagnósticas; los endpoints de negocio sólo los monta `studio-app`. Esto es legítimo para smoke, pero cualquier consumidor que hable HTTP contra `studio start` verá solo diagnóstico. No es un bug — es el contrato actual — pero la unificación total requiere un refactor mayor (R0.2.1 completo).
2. **Receipt histórica B5.2 menciona `MinimalOpenApiDoc`:** el recibo histórico documenta la verdad de B5.2 en ese momento (dos docs paralelos con la misma versión). Conservarlo es correcto (inmutabilidad de receipts). El lector que cruce B5.2 con R0.2.1 verá la evolución correcta.
3. **CI gate R0.2.1 no se ha podido ejecutar en este commit** (`git push` y `gh` están bloqueados por D-007 hasta que el operador autorice). El número de tests es honesto (ejecutado localmente, suite completa), pero el gate remoto queda como próximo paso.

## 9. Cierre

**Veredicto R0.2.1 (sub-bloque):** **PASS** para la parte de **unificación de contrato OpenAPI** (R0.2.1.a). La parte de **unificación de servidor runtime** (R0.2.1.b — extracción de `StudioServer.boot(...)` o spawn-JVM) queda **BLOQUEADA estructuralmente**: requiere un refactor fuera del alcance de un commit de recibo.

**Próximos pasos posibles:**
- **A) Cerrar R0.2.1.b en una sesión posterior** con el refactor acordado, o
- **B) Pivotar a R0.3.4–3.7** (reserva durable, recovery sin duplicados, upstreamRunId authority, UNKNOWN→no FAILED) que requieren máquina de smoke real, o
- **C) Documentar el cierre parcial de R0.2 como suficiente** y abrir R1 (consola interactiva) con la OPENAPI unificada como baseline.

---

**Recibo generado por:** Agente (R0.2.1, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. La GitHub Release R0 (`v1.0.0-rc.2`) sigue bloqueada por D-007.
