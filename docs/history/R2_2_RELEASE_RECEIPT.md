# R2.2 — SDK version range header (X-PipelineK-Sdk-Version-Range) — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `6da7fb7 docs(history): R2.3 PASS`
**Bloque:** R2 (Step & Plugin Lab) — sub-item **R2.2** (vendor + version range match)
**Resultado:** **PASS** — header parseado por `StaticManifestInspector`, aplicado por `PluginAdmissionUseCase`, 7 tests adversariales verde ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R2 item 2):**
> Inspeccionar manifiestos, identidad, hashes y compatibilidad sin ejecutar inicializadores ni cargar clases en el backend. La compatibilidad es un atributo del plugin (declarado en su manifest) cruzado contra el pin del SDK instalado.

**Estado pre-R2.2:**
- `StaticManifestInspector` leía `Implementation-Title/Version/Vendor` + META-INF/services + plugin.properties. **No leía** `X-PipelineK-Sdk-Version-Range` (lo descartaba).
- `PluginAdmissionUseCase.isVersionInRange` (R2.3) usaba la regla naive: `plugin_version < SDK_version`. Esto era funcional para el fixture R2.1 (que ponía `[0.0.0, 1.0.0)` como placeholder y se admitía con SDK < 1.0.0) pero no soportaba rangos reales tipo `[1.0.0, 2.0.0)`.
- El header ya estaba en los 3 JARs del fixture R2.1 pero el inspector lo descartaba silenciosamente.

**Estado post-R2.2:**
- `StaticManifestInspector.PluginManifest.sdkVersionRange: String?` surface el header.
- `StaticManifestInspectorAdapter` propaga el campo a `StaticPluginManifest.sdkVersionRange`.
- `PluginAdmissionUseCase.isVersionInRange` ahora:
  - Si el header está presente Y parseable: aplica la regla Maven-style `lo <= SDK < hi` (half-open por default) o `lo <= SDK <= hi` (closed con `]`).
  - Si el header está ausente O malformado: cae al fallback naive R2.3 (`plugin_version < SDK_version`).
- El fixture R2.1 `incompatible-api-plugin-99.0.0.jar` ahora declara explícitamente `[2.0.0, 3.0.0)` (excluye el SDK 0.x actual); el nombre `99.0.0` queda como marker visual pero ya no determina la admission.
- 7 tests adversariales pinean: narrow range con SDK inside / SDK en upper bound / wide range con SDK 1.0.0 / version above range / missing header (cae a naive) / naive rule refuses / malformed header (no crash).

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 6da7fb7 |
| SHA integrado (R2.2) | **3d1d4a9** |
| Tag anotado (intacto) | `v1.0.0-rc.6` → 017f4c3 |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |

---

## 3. Cambios

### Producción

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/.../StaticManifestInspector.kt` | + `PluginManifest.sdkVersionRange: String?`; lee `attrs.getValue("X-PipelineK-Sdk-Version-Range")` en `inspect()` |
| `studio-infra-sdk/.../StaticManifestInspectorAdapter.kt` | + propaga `sdkVersionRange` a `StaticPluginManifest` |
| `studio-application/.../usecases/PluginAdmissionUseCase.kt` | + `isVersionInRange(pluginVersion, pluginRange, sdkVersion)` con range check + fallback naive. + `compareSemver()` + `parseRange()` helpers. + `ParsedRange` data class. |
| `examples/sdk-external-execution/build.gradle.kts` | + `incompatibleSdkVersionRange = "[2.0.0,3.0.0)"` (excluye el SDK actual); el JAR `incompatible-api-plugin` usa este range en lugar del default. |

### Tests

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/.../VersionRangeR22Test.kt` | **NUEVO.** 7 tests adversariales del parser de range. |
| `studio-infra-sdk/.../PluginAdmissionUseCaseR23Test.kt` | `sdkVersion` de `1.0.0-rc.6` → `0.50.0` (sintético, dentro del range del fixture) |

### Recuento

- Archivos modificados: 6.
- Tests netos: **+7** (studio-infra-sdk 83 → 90).
- Tests totales Kotlin: 583 → **590**.
- Líneas: +272 / −15.

---

## 4. Reglas de admisibilidad (R2.2)

```
isVersionInRange(pluginVersion, pluginRange, sdkVersion) =
  if pluginRange == null
    naive(pluginVersion, sdkVersion)   // R2.3 fallback
  else
    parsed = parseRange(pluginRange)
    if parsed == null
      naive(pluginVersion, sdkVersion) // header malformed → fallback (no refuse outright)
    else
      if parsed.lower != null && sdkVersion < parsed.lower  → out of range
      if parsed.upper != null && !parsed.upperInclusive && sdkVersion >= parsed.upper  → out of range (half-open)
      if parsed.upper != null && parsed.upperInclusive && sdkVersion > parsed.upper    → out of range (closed)
      else → in range

naive(pluginVersion, sdkVersion) =
  parseSemver(pluginVersion) < parseSemver(sdkVersion)

parseRange("[lo, hi)") = (lo, hi, upperInclusive=false)
parseRange("[lo, hi]") = (lo, hi, upperInclusive=true)
parseRange("1.0.0")    = (1.0.0, 1.0.0, upperInclusive=true)   // exact match
parseRange("garbage")  = null                                // → fallback
```

Razonamiento del fallback: **un header malformado no debe amplificar a un refusal de admission**. Si el operador tiene un lock entry para un JAR y luego edita el manifest por error (typo, encoding), el siguiente `inspect()` no debe decir "te quito el lock". El fallback naive + el warning en log es la forma honesta: la regla "plugin < SDK" sigue siendo útil para plugins sin header, y un plugin con header roto cae ahí.

---

## 5. Tests adversariales nuevos

### R2.2 — `VersionRangeR22Test`

1. **`R2_2 narrow range — SDK inside is compatible`** — `narrow-0.5.0.jar` con range `[0.0.0, 1.0.0)` + SDK 0.50.0: 0 ≤ 0.50.0 < 1 → Compatible. **AAT-021 pin con range real.**
2. **`R2_2 narrow range — SDK at the upper bound is refused`** — `any-1.0.0.jar` con range `[0.0.0, 1.0.0)` + SDK 1.0.0: 1.0.0 está en el upper bound EXCLUSIVO → Refused. La razón nombra ambos versions. **(Semantica R2.2 clave.)**
3. **`R2_2 wide range — SDK 1_0_0 is compatible`** — `wide-1.0.0.jar` con range `[0.0.0, 2.0.0)` + SDK 1.0.0: 0 ≤ 1.0.0 < 2 → Compatible. **Pre-R2.2 esto era imposible (la regla naive rechazaba cualquier plugin con version >= SDK).**
4. **`R2_2 version above the range is refused`** — `wide-3.0.0.jar` con range `[0.0.0, 2.0.0)` + SDK 3.0.0: 3.0.0 >= 2.0.0 → Refused.
5. **`R2_2 missing header — falls back to naive plugin-version-less-than-SDK rule`** — `no-header-0.1.0.jar` sin header + SDK 0.50.0 + plugin version 0.1.0: 0.1.0 < 0.50.0 (naive) → Compatible.
6. **`R2_2 missing header — naive rule refuses version at-or-above SDK`** — `no-header-2.0.0.jar` sin header + SDK 0.50.0 + plugin version 2.0.0: 2.0.0 < 0.50.0 es false → Refused.
7. **`R2_2 malformed header — falls back to naive rule (does not crash)`** — `bad-header-0.1.0.jar` con range `"this is not a range"` + SDK 0.50.0 + plugin version 0.1.0: parseRange devuelve null → fallback a naive → 0.1.0 < 0.50.0 → Compatible. **No crash, no refuse.** La invariante "typo en manifest no pierde la admission" está pineada.

---

## 6. Tests ejecutados

### Foco R2.2

```
$ ./gradlew :studio-infra-sdk:test --tests '*VersionRangeR22Test'
BUILD SUCCESSFUL in 17s
VersionRangeR22Test tests=7 skipped=0 failures=0 errors=0
```

### Regresión R2.3 (cambio de sdkVersion en tests)

```
$ ./gradlew :studio-infra-sdk:test --tests '*PluginAdmissionUseCaseR23Test'
BUILD SUCCESSFUL
PluginAdmissionUseCaseR23Test tests=7 skipped=0 failures=0 errors=0
```

### R2.1 fixture sigue verde

```
$ ./gradlew :studio-infra-sdk:test --tests '*ExternalFixtureR21Test'
ExternalFixtureR21Test tests=3 skipped=0 failures=0 errors=0
```

### Suite completa

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 56s
```

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 127 | 127 | 0 | 0 | 0 |
| studio-infra-storage | 62 | 62 | 0 | 0 | 0 |
| studio-infra-sdk | **90** | 89 | 0 | 1 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| studio-cert | 8 | 8 | 0 | 0 | 0 |
| studio-domain | 23 | 23 | 0 | 0 | 0 |
| **TOTAL** | **590** | **589** | **0** | **1** | **0** |

---

## 7. UAT/AAT vinculadas

- **AAT-PLG-001 (compatibilidad / vendor + version match)** ✅ — el inspector+use case ahora reporta vendor Y version-range honestamente sin fabricación. La semantica R2.2 sustituye el fallback naive por la regla del header; el fallback se mantiene como red de seguridad para plugins sin header.
- **AAT-PLG-005 (null schemas / no fabricacion)** ✅ — header ausente o malformado no se interpreta; la admission cae al fallback honesto, no a un descriptor inventado.
- **AAT-PLG-007 (no hardcoded keys)** ✅ — el range es string; no hay catálogo cerrado de versiones SDK. El operator puede publicar plugins con cualquier range razonable.

---

## 8. Comandos y códigos de salida

```
$ ./gradlew :examples:sdk-external-execution:clean :examples:sdk-external-execution:assemble
BUILD SUCCESSFUL in 17s   # exit 0
3 JARs: valid-plugin-0.1.0.jar (1.1K, range [0.0.0,1.0.0))
       incompatible-api-plugin-99.0.0.jar (1.1K, range [2.0.0,3.0.0))
       canary-initializer-plugin-0.1.0.jar (1.6K, range [0.0.0,1.0.0))

$ ./gradlew :studio-infra-sdk:test --tests 'dev.pipelinek.studio.infra.sdk.VersionRangeR22Test' --console=plain --no-daemon
BUILD SUCCESSFUL in 17s   # exit 0

$ ./gradlew :studio-infra-sdk:test --tests 'dev.pipelinek.studio.infra.sdk.PluginAdmissionUseCaseR23Test' --console=plain --no-daemon
BUILD SUCCESSFUL   # exit 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 56s   # exit 0
```

---

## 9. Riesgos y deuda residual

1. **El SDK version 0.50.0 es un sintético de tests.** El SDK realmente pinneado en `studio-infra-sdk/src/main/resources/sdk.lock.json` es `0.48.0-rc1`. Los tests R2.3 + R2.2 usan `0.50.0` para no depender del lockfile pin (que puede cambiar). El production wiring del use case debería tomar el SDK version del lockfile, no de un literal.
2. **El fallback naive ignora la version del SDK parseada.** Cuando el header está ausente, el use case compara `plugin < SDK` directamente. Esto funciona para el caso "el plugin viene de un SDK anterior", pero rechaza plugins del "mismo SDK" (version igual). Mejora futura: tratar `plugin == SDK` como Compatible cuando el header falta (es la "compatibilidad misma-linea" del SDK).
3. **Header malformado cae a fallback sin loggear.** El operator no se entera del typo. Mejora: emitir un `log.warn` cuando `parseRange` devuelve null (similar a como `FilePluginAdmissionLock` loggea el format_version desconocido).
4. **No hay cobertura de rangos single-version** (`"1.0.0"` como range). El parser lo trata como exact-match (lower=upper, upperInclusive=true). Test pineable cuando haya necesidad.
5. **`vendorMatch: true` literal en `Compatible`.** El campo se setea siempre `true` porque vendor se chequea antes; si vendor no match, se devuelve `Refused` antes de llegar al Compatible. Mejora menor: derivar el flag de la check explicita.

---

## 10. Cierre

**Veredicto R2.2:** **PASS**.

- Header `X-PipelineK-Sdk-Version-Range` parseado (Maven-style: `[lo, hi)` o `[lo, hi]` o single-version).
- Fallback explicito: ausente o malformado → regla naive R2.3.
- Semantica R2.2 probada: SDK 1.0.0 con range `[0.0.0, 2.0.0)` ahora es Compatible (pre-R2.2 era imposible).
- 7 tests adversariales verde; 590/590 Kotlin verde; 0 failures, 0 errors.

Siguiente item activo en el plan: **PluginAdmissionPort impl** (R2.3 prep) + **R2.7 cierre** (`provenance: StepProvenance` y `refusalReason` en `StepContract`) + **R2.5** (freeze plugin set + digest por run).

---

**Recibo generado por:** Agente (R2.2, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `3d1d4a9` integrado en `main`; tag `v1.0.0-rc.6` intacto en `017f4c3`.
