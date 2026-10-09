# Step/Plugin Lab y seam genérico de extensibilidad

## Objetivo

Permitir descubrir y probar Steps de plugins de PipelineK mediante **SDK real**, sin branches por plugin dentro del coordinator o Studio, y sin implementar aún especializaciones para Policy o Assurance.

## Flujo

1. Seleccionar `artifactRef` local — proviene de fichero elegido dentro del workspace autorizado, no ruta arbitraria MCP.
2. Inspeccionar manifest y firma/digest/identity/api range *sin cargar el código*.
3. Mostrar `Accepted/Refused` de **admisión** con causas; `accepted` no certifica seguridad ni calidad.
4. Preparar runner separado con artefactos admitidos, obtener snapshot inmutable del registry real (`StepRegistry` y proveedores).
5. Mostrar `StepKey`, nombre, version, `StepDescriptor`, schemas cuando sean útiles, declared effects, capabilities, replay policy y referencia a ejemplos.
6. Crear escenario de Step usando `StepCodec` y `StepContract` real. Probar éxito, fallo tipado, falta de capability y codec roundtrip cuando aplicable.
7. Comparar resultados de una ejecución de Step directa (si el SDK/runtime ofrece un entrypoint certificado) contra un pipeline `.pipeline.kts` que usa el mismo Step: **no implementar un handler-execution bypass**.
8. Emitir informe estructurado con run IDs, input digest, plugin identity/version/digest, runtime version, outcome, events y referencias de output.

## Modelo de extensión inicial

`CatalogEntry { key, provider, version, inputSchema?, outputSchema?, capabilities, effectHints?, examples?, availability, provenance }`

`ResultDescriptor { kind, schemaId, artifactRef?, object?, provenance, sensitivity, size }`

`Diagnostic { code, severity, phase, sourceRange?, message, origin, actionRefs? }`

`ScenarioDescriptor { id, snapshotRef, parameterSchema?, expectedOutcomes, profileRestrictions }`

Estos DTO son **Studio wire-contracts propuestos**, no afirmaciones sobre tipos ya existentes en el SDK. Registry/codec/version gobiernan su contenido. Si el codec devuelve `{}` para schema, marcar `schemaStatus=UNAVAILABLE` y admitir JSON raw validado por el codec en runner, nunca generar input form engañoso.

## Reglas de especialización

- Prohibido `when(stepKey == "policy.check")` y comprobaciones directas `assurance.*` en Studio base.
- Permitido renderizador genérico: JSON tree, tabla, texto, graph descriptor seguro, artifact download/link, stdout/stderr, diff y hallazgos tipados.
- No ejecutar JS/HTML proporcionado por JAR. Futura extensión UI requiere su propia ADR de aislamiento y firma.
- `Policies` y `Assurance` aportarán resultados tipados por su propio contrato cuando se planifique el evolutivo, sin modificar el modelo base.
- Las reglas de confianza/admisión son de PipelineK, no del formulario Studio; Studio muestra los motivos que devuelve la autoridad.

## TestKit

Integrar `StepContractSuite` y `PluginContractSuite` si están disponibles en distribuciones públicas verificadas, como runners/test adaptadores. No copiar el testkit desde el repo upstream. Usar plugin externo `example-uppercase-plugin` u otro publicado en fixture del repo `examples/sdk-external-execution/`, compilado contra el artefacto publicado; añadir plugin ad-hoc fuera de `pipeline-kotlin` para comprobar la seam.

## UAT/AAT

- UAT-020: plugin externamente construido aparece en catálogo tras admisión sin modificar React/Studio core.
- UAT-021: manifest malformado o versión incompatible rechazada antes de class loading (canario de inicializador no corre).
- UAT-022: missing capability → rechazo previo al handler.
- UAT-023: `StepCodec` roundtrip de caso representativo; parser malformado devuelve typed failure.
- UAT-024: Step builtin/external siguien mismo dispatcher, mismo modelo de eventos/outcome; sin bypass.
- AAT-PLG-001..007: compatibilidad, fingerprint, clasificación, datos no confiables, null schemas, classloader isolation, no hardcoded keys.

## No objetivos S2

Marketplace, hot swap de JAR dentro de mismo JVM, GUI de policies, editor de assurance lenses, creador de firmas criptográficas, step REPL arbitrario, publicación de plugins a Maven Central.
