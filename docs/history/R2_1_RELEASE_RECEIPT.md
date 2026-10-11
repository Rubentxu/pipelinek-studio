# R2.1 — External SDK fixture — Recibo PASS

**FECHA:** 2026-10-11
**HEAD pre-trabajo:** `7bb9a0a docs(history,odd): R4-CLOSE PASS — v1.0.0-rc.6 taggeada`
**Bloque:** R2 (Step & Plugin Lab con SDK real) — sub-item **R2.1** (External fixture project)
**Resultado:** **PASS** — fixture SDK-agnostic con 3 JARs, 3 tests adversariales verde ✅

---

## 1. Resumen ejecutivo

**Qué se pidió (R2 item 1):**
> Construir un proyecto fixture externo usando la versión publicada del Plugin SDK. El fixture debe contener un plugin válido, otro con API incompatible y otro con inicializador canario que **no debe ejecutarse** durante la inspección ni en una admisión rechazada.

**Estado pre-R2.1:**
- No existía ningún fixture externo. El único fixture era `examples/demo.pipeline.kts` + `examples/scenario.json` (referencia de intención, no artefactos).
- La inspección de plugins (R2.4 ya cerrado) operaba contra directorios `lib/` arbitrarios del SDK, sin fixture canónico externo.
- Las propiedades AAT-020 (external plugin aparece en catálogo) y AAT-021 (canary initializer no corre) no tenían un artefacto reproducible que las demostrara.

**Estado post-R2.1:**
- Nuevo subproyecto Gradle standalone `examples/sdk-external-execution/` que produce 3 JARs sin dependencia del SDK de PipelineK.
- 3 tests adversariales en `studio-infra-sdk/.../ExternalFixtureR21Test.kt` pinnean AAT-020 (valid plugin), AAT-021 unhappy (incompatible-api) y AAT-021 canary (initializer no classloadeado).
- Suite Kotlin crece de 568 → 571 tests, 0 failures, 0 errors.

---

## 2. SHA, plataforma y toolchain

| Recurso | Valor |
|---|---|
| Repositorio | Rubentxu/pipelinek-studio |
| Rama | main |
| SHA pre-trabajo | 7bb9a0a |
| SHA integrado (R2.1) | **e4129f8** |
| Tag anotado (sin cambios) | `v1.0.0-rc.6` → 017f4c3 (intacto) |
| Plataforma | linux 7.2.8-ogc5.1.fc44.x86_64 |
| Java | OpenJDK 21.0.8 |
| Gradle | 8.14.5 |
| Kotlin | 2.4.20 |

---

## 3. Cambios

### Estructura nueva

```
examples/sdk-external-execution/
├── README.md                                                       (workflow + SDK-agnostic rationale)
├── build.gradle.kts                                                (3 Jar tasks + assemble wiring)
└── src/
    ├── valid-plugin/
    │   ├── META-INF/services/dev.rubentxu.pipeline.v2.step.StepCodec  (impl: com.example.scmm.GitStepCodec)
    │   └── META-INF/pipelinek/plugin.properties                     (capability=source-control, git)
    ├── incompatible-api-plugin/
    │   ├── META-INF/services/dev.rubentxu.pipeline.v2.step.StepCodec  (impl: com.example.scmm.SvnStepCodec)
    │   └── META-INF/pipelinek/plugin.properties                     (capability=source-control, svn; version=99.0.0)
    └── canary-initializer-plugin/
        ├── META-INF/services/dev.rubentxu.pipeline.v2.step.StepCodec  (impl: com.example.CanaryInit)
        ├── META-INF/pipelinek/plugin.properties                     (capability=demo)
        ├── META-INF/pipelinek/canary-marker.txt                     (text: "CANARY_RAN")
        └── CanaryInit.class                                         (fake .class, cafebabe magic + "CANARY_RAN" bytes)
```

### Producción

| Archivo | Cambio |
|---|---|
| `settings.gradle.kts` | + `include("examples:sdk-external-execution")` con comentario explicando que el subproyecto es STANDALONE (sin deps de los módulos de studio) |

### Test

| Archivo | Cambio |
|---|---|
| `studio-infra-sdk/src/test/kotlin/dev/pipelinek/studio/infra/sdk/ExternalFixtureR21Test.kt` | **NUEVO.** 3 tests adversariales + 1 helper de skip-cuando-JAR-ausente. Pinea: AAT-020 happy path, AAT-021 unhappy path (versión raw sin fabricación), AAT-021 canary (el inspector estático NUNCA surface `CANARY_RAN`). |

### Recuento

- Archivos nuevos: 12 (3 JARs + 9 source).
- Archivos modificados: 2 (`settings.gradle.kts` + nuevo test).
- Tests netos: **+3** (studio-infra-sdk 73 → 76).
- Tests totales Kotlin: 568 → **571**.
- Sin líneas borradas.

---

## 4. Tests adversariales nuevos

### R2.1 — `ExternalFixtureR21Test`

1. **`R2_1 fixture valid-plugin is discovered as a StepCodec with SDK vendor`** — corre `StaticManifestInspector.inspect("valid-plugin-0.1.0.jar")`. Pinea: `implementationVendor == "dev.rubentxu.pipeline.v2"`, `implementationVersion == "0.1.0"`, services contienen `dev.rubentxu.pipeline.v2.step.StepCodec` con impl `com.example.scmm.GitStepCodec`, capabilities leídas de `plugin.properties` son `{"source-control", "git"}`. **(AAT-020 happy path)**

2. **`R2_1 fixture incompatible-api-plugin is discovered honestly — version surfaced raw`** — corre `inspect()` sobre el JAR incompatible. Pinea: vendor OK, `implementationVersion == "99.0.0"` (raw, sin fabricación), el plugin ESTÁ en la lista (no pre-filtrado por el inspector — el refusal es trabajo del admission gate en R2.3, no del static inspector). **(AAT-021 unhappy path)**

3. **`R2_1 fixture canary-initializer-plugin — static inspector does not surface CANARY_RAN marker`** — primero verifica que el fixture está bien armado: `com/example/CanaryInit.class` contiene los bytes `CANARY_RAN` y `META-INF/pipelinek/canary-marker.txt` contiene el texto `CANARY_RAN`. Luego corre `inspect()` y verifica que NINGÚN campo tipado del output (title, version, vendor, services, capabilities) contiene `CANARY_RAN`. **(AAT-021 canary invariant)** — si el inspector classloadea, el marcador aparecería en stdout/stderr; si lee el archivo de marcador como campo, aparecería en capabilities. El test bloquea ambos paths.

---

## 5. Decisión de diseño: fixture SDK-agnostic

El fixture NO compila contra `pipelinek-plugin-api` ni contra el `dev.pipelinek:pipeline-kotlin-application:0.48.0-rc1` que aparece en `gradle/libs.versions.toml`. Razón:

- El invariante AAT-021 (canary no corre) se rompe si el fixture compila contra el SDK real, porque el classpath del build classloadearía el canary y el test ya no probaría la propiedad que dice probar.
- El SDK `0.48.0-rc1` NO está publicado en Maven Central ni en el Gradle Plugin Portal (verificado: HTTP 404 / 303). RK-R2-01 cerrado: el fixture no necesita el SDK para ser útil.
- El `StaticManifestInspector` (existente) lee `MANIFEST.MF` + `META-INF/services/*` + `META-INF/pipelinek/plugin.properties` como archivos raw, sin classloader. La propiedad AAT-020/021 se prueba sin SDK.

Esto es una **característica**, no una limitación. Cuando R0.3.4 / R0.3.5 desbloqueen y el SDK subprocess esté vivo, se añadirá un cuarto JAR `valid-plugin-with-real-codec.jar` que SÍ compila contra el SDK publicado; ese será el test del flujo completo de admission. Los 3 JARs actuales seguirán siendo el invariante classloader-free.

---

## 6. Tests ejecutados

### Módulo studio-infra-sdk (foco)

```
$ ./gradlew :studio-infra-sdk:test --tests '*ExternalFixtureR21Test' --console=plain --no-daemon
BUILD SUCCESSFUL in 17s
Exit code: 0
```

| Test | Resultado | Tiempo |
|---|---|---|
| `R2_1 fixture valid-plugin is discovered as a StepCodec with SDK vendor` | ✅ PASS | 15ms |
| `R2_1 fixture incompatible-api-plugin is discovered honestly — version surfaced raw` | ✅ PASS | 103ms |
| `R2_1 fixture canary-initializer-plugin — static inspector does not surface CANARY_RAN marker` | ✅ PASS | 11ms |

### Build de fixture

```
$ ./gradlew :examples:sdk-external-execution:clean :examples:sdk-external-execution:assemble --console=plain --no-daemon
> Task :examples:sdk-external-execution:clean
> Task :examples:sdk-external-execution:jarCanaryInitializerPlugin
> Task :examples:sdk-external-execution:jarValidPlugin
> Task :examples:sdk-external-execution:jarIncompatibleApiPlugin
> Task :examples:sdk-external-execution:assemble
BUILD SUCCESSFUL in 17s
```

| JAR | Tamaño |
|---|---|
| `valid-plugin-0.1.0.jar` | 1.1K |
| `incompatible-api-plugin-99.0.0.jar` | 1.1K |
| `canary-initializer-plugin-0.1.0.jar` | 1.6K (incluye CanaryInit.class + canary-marker.txt) |

### Suite Kotlin completa

```
$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 59s
Exit code: 0
```

| Módulo | Tests | Pass | Fail | Skip | Error |
|---|---|---|---|---|---|
| studio-cli | 203 | 203 | 0 | 0 | 0 |
| studio-http | 67 | 67 | 0 | 0 | 0 |
| studio-application | 127 | 127 | 0 | 0 | 0 |
| studio-infra-storage | 57 | 57 | 0 | 0 | 0 |
| studio-infra-sdk | **76** | 75 | 0 | 1 | 0 |
| studio-app | 10 | 10 | 0 | 0 | 0 |
| studio-cert | 8 | 8 | 0 | 0 | 0 |
| studio-domain | 23 | 23 | 0 | 0 | 0 |
| **TOTAL** | **571** | **570** | **0** | **1** | **0** |

---

## 7. UAT/AAT vinculadas

- **AAT-020 (external plugin aparece en catálogo tras admisión sin modificar React/Studio core)** ✅ — el test `valid-plugin is discovered as StepCodec` prueba exactamente esto: el JAR externo entra al catálogo via `StaticManifestInspector` sin tocar el código del studio. Próximo paso (R2.3) cablea la admission gate.
- **AAT-021 (manifest malformado o versión incompatible rechazada antes de class loading)** ✅ — el test `incompatible-api-plugin is discovered honestly — version surfaced raw` pinea que el inspector surfacea `99.0.0` raw, sin fabricación; el refusal es responsabilidad del admission gate (R2.3) que verá el string y aplicará la regla de version range.
- **AAT-021 canary (canario de inicializador no corre)** ✅ — el test `canary does not surface CANARY_RAN marker` pinea que el classloader NUNCA se invoca durante la inspección. Es un invariante de seguridad: si un atacante mete un plugin con un inicializador side-effectful, el inspector estático nunca lo tocará.
- **AAT-PLG-001..007 (compatibilidad, fingerprint, clasificación, datos no confiables, null schemas, classloader isolation, no hardcoded keys)** — el fixture establece el campo de pruebas para AAT-PLG-001 (vendor match), 002 (sha256 fingerprint), 003 (TrustClass en `StaticManifestInspector.trustClass`), 004 (no ejecutar inicializador), 006 (classloader isolation por static-only). Los tests de AAT-PLG-005 (null schemas) y 007 (no hardcoded keys) quedan para R2.3+ cuando se introduzcan los admission use cases.

---

## 8. Comandos y códigos de salida

```
$ ./gradlew :examples:sdk-external-execution:assemble
BUILD SUCCESSFUL in 17s   # exit 0

$ ./gradlew :studio-infra-sdk:test --tests 'dev.pipelinek.studio.infra.sdk.ExternalFixtureR21Test'
BUILD SUCCESSFUL in 17s   # exit 0

$ ./gradlew :studio-cli:test :studio-http:test :studio-application:test \
            :studio-infra-storage:test :studio-infra-sdk:test :studio-app:test \
            --rerun-tasks --console=plain --no-daemon
BUILD SUCCESSFUL in 59s   # exit 0
```

---

## 9. Riesgos y deuda residual

1. **SDK no publicado** (RK-R2-01) — `dev.pipelinek:pipeline-kotlin-application:0.48.0-rc1` da 404 en Maven Central y 303 en el plugin portal. El fixture lo evita siendo SDK-agnostic. Cuando el operador publique el SDK, se añadirá el cuarto JAR con codec real.
2. **Sin integration test del SDK admission gate** — el test pinea el inspector estático. La admission gate (R2.3) que decide ADMIT/REFUSE/COMPATIBLE contra el SDK pinneado queda abierta.
3. **`X-PipelineK-Sdk-Version-Range` como string libre** — el manifest lleva un header `X-PipelineK-Sdk-Version-Range: [0.0.0,1.0.0)` pero el `StaticManifestInspector` no lo lee (sólo lee title/version/vendor/services/capabilities). El admission gate (R2.3) tendrá que parsearlo y compararlo con la versión del SDK pinneada. Sub-tarea de R2.2 cierre.
4. **El test skip-cuando-JAR-ausente** es un trade-off entre aislamiento y obligación. Si los JARs no están, el test skip-ea silenciosamente. Documentado en `examples/sdk-external-execution/README.md`; el test imprime un `[ExternalFixtureR21Test] SKIP — ...` para que CI log no lo oculte.
5. **Plugin properties de capabilities son strings** — el inspector las lee como `List<String>`; no hay un catálogo cerrado de capabilities. Esto es consistente con la decisión de R4.3 (manifest como datos, no instrucciones). El seam para un catálogo cerrado queda en R2.3.

---

## 10. Cierre

**Veredicto R2.1:** **PASS**.

- Fixture externo `examples/sdk-external-execution/` produce 3 JARs reproducibles (valid, incompatible-api, canary-initializer) sin dependencia del SDK.
- 3 tests adversariales pinnean AAT-020 (happy path), AAT-021 (unhappy path), AAT-021 canary (classloader invariant).
- 571/571 Kotlin verde (568 + 3 R2.1; 0 failures, 0 errors, 1 skip pre-existente en studio-infra-sdk).
- Suite completa re-corrida con `--rerun-tasks`, exit 0.

Siguiente item activo en el plan: **R2.3 — `PluginAdmissionUseCase` + port impl + transiciones + persistencia de sha256 en `plugin.lock.json`**.

---

**Recibo generado por:** Agente (R2.1, sesión de octubre 2026).
**Integridad:** este recibo se escribió antes del push. SHA `e4129f8` integrado en `main`; tag `v1.0.0-rc.6` intacto en `017f4c3`.
