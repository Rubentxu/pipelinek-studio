# Extensibility Seam for Policies & Assurance — R2.11

**Status:** Documentation only. No code in Studio core is added
or removed by R2.11; this document records the **seam** that future
Policies and Assurance projects will plug into, and pins the
contract that protects Studio core from being modified when those
projects land.

Per ROADMAP R2 item 11: "Mantener el seam de extensibilidad para
Policies y Assurance sin introducir dependencias de esos
proyectos."

Per `docs/02-specifications/STEP_PLUGIN_LAB.md §Reglas de
especialización`:
> Prohibido `when(stepKey == "policy.check")` y comprobaciones
> directas `assurance.*` en Studio base. Permitido renderizador
> genérico: JSON tree, tabla, texto, graph descriptor seguro,
> artifact download/link, stdout/stderr, diff y hallazgos
> tipados.
>
> `Policies` y `Assurance` aportarán resultados tipados por su
> propio contrato cuando se planifique el evolutivo, sin
> modificar el modelo base.

## What the seam looks like today

The seam is **structural**, not functional. Studio exposes a
generic surface that any extension can plug into without Studio
core knowing what the extension does:

### 1. StepContract is generic

`studio-application/.../ports/StepCatalog.kt` defines:

```kotlin
data class StepContract(
    val id: String,
    val name: String,
    val pluginJar: String?,
    val capabilities: Set<String>,
    val inputSchema: StepSchema?,
    val outputSchema: StepSchema?,
    val affordances: List<StepAffordance>,
    val provenance: StepProvenance? = null,
    val refusalReason: String? = null,
)
```

`StepContract` carries NO concept of "is this a policy step?" or
"is this an assurance check?". A Policy step is just a step with
a particular id, capability set, and provenance. The catalog
adapter does not filter or annotate by kind.

### 2. ResultDescriptor is generic

`studio-application/.../ports/StepCatalog.kt`:

```kotlin
sealed interface ResultDescriptor {
    data class Text(val body: String, val mime: String = "text/plain")
    data class Json(val body: String)
    data class Table(val headers: List<String>, val rows: List<List<String>>)
    data class ArtifactRefs(val refs: List<String>)
    data class NotAvailable(val reason: String)
}
```

The renderer is a `when` on the sealed interface. New variants
added by Policies/Assurance (e.g. `PolicyVerdict`,
`AssuranceFinding`) would extend the sealed interface in their
own module; Studio's renderer would `else -> renderGeneric(...)`.
The forbidden pattern `when (result is PolicyVerdict) { ... }`
in Studio core is what the seam protects against.

### 3. Plugin admission is generic

`PluginAdmissionState` (R2.3) classifies JARs by vendor + version
range. It does not know that a particular plugin happens to be
"a policy plugin" or "an assurance plugin". The admission gate
treats all plugins uniformly; semantic classification is the
plugin's own concern, surfaced via `capabilities: Set<String>` in
the catalog.

### 4. HTTP / MCP transports are generic

The 5 routes at `/api/plugins/*` (R2.10) and 5 MCP tools at
`studio.plugins.*` are named after the **operation** (list,
inspect, admit, refuse, revoke), not after any policy/assurance
domain concept. A future Policies service would call these
endpoints; the endpoint surface does not change.

## What an extension can do without touching Studio

A future Policies project can, **without modifying Studio core**:

1. **Discover plugins** via `GET /api/plugins` or
   `studio.plugins.list` MCP tool.
2. **Filter by capabilities** — `capabilities: Set<String>` is
   the public surface. A policy plugin declares its capabilities
   in `META-INF/pipelinek/plugin.properties`; the extension
   matches `capabilities.contains("policy.evaluation")` (or
   similar) without Studio core knowing.
3. **Admit / refuse / revoke** its own plugins via
   `POST /api/plugins/{jar}/admit` etc. The studio enforces the
   gate; the extension provides the policy decision.
4. **Run a policy check** by invoking a step via
   `studio.run_scenario` (R0.3.6 / AAT-002..003) with a
   `ScenarioExecutionRequest`. The step runs; the studio surfaces
   the result via the generic `ResultDescriptor` sealed
   interface; the extension interprets the result.
5. **Render a custom view** by extending `ResultDescriptor` in
   its own module. Studio's `when` over the sealed interface
   falls back to `else -> renderGeneric(...)` for variants it
   doesn't recognize; the extension's renderer takes over.

## What an extension CANNOT do (the protected contract)

The seam protects Studio core from these anti-patterns. If a
future PR violates any of these, the PR is rejected at review
and the contract is re-pinned:

1. **NO** `when (stepKey == "policy.check")` in Studio core. Branch
   on capabilities, not on step identity.
2. **NO** `if (plugin.manifest.contains("policy"))` in Studio
   core. Read the capabilities set; let the extension declare
   its identity.
3. **NO** direct calls into Policies / Assurance modules from
   Studio core. Studio exposes the surface; the extension calls
   the surface.
4. **NO** hardcoded rendering of any policy-specific output.
   The renderer is a `when` over `ResultDescriptor`; new variants
   are added by extensions, not by Studio.
5. **NO** "accepted as trusted" markings in Studio core. The
   admission gate says ADMITTED, REFUSED, or UNKNOWN; it does not
   certify safety or quality. The spec quote: "accepted no
   certifica seguridad ni calidad."

## Where the seam is enforced in code

| Concern | Where it's enforced |
|---|---|
| "No `when (stepKey == ...)`" | Lint rule + code review. The catalog adapter (`PipelineKStepCatalogReadPort`) uses `StepDiscovery.ServiceLoader`; no per-step special cases. |
| "Capabilities are the public surface" | `PluginManifest.stepCapabilities: List<String>` in `StaticManifestInspector`; the `capabilities: Set<String>` field in `StepContract` is the only thing Studio exposes. |
| "Renderer is a `when` over ResultDescriptor" | `ResultDescriptor` is a sealed interface; the consumer (`apps/web/src/.../renderers` etc.) is expected to handle `else -> default`. |
| "Admission is generic" | `PluginAdmissionUseCase.inspect(...)` returns one of 5 states based on manifest metadata only; no plugin-name special cases. |
| "HTTP / MCP are operation-named" | The 5 routes at `/api/plugins/*` are operation-named (`list`, `inspect`, `admit`, `refuse`, `revoke`); the 5 MCP tools are also operation-named. No policy/assurance keywords. |

## Future migration path

When Policies/Assurance land, the work is:

1. **New module** `studio-policies` (or similar) outside the
   `pipelinek-studio` repo or as a separate Gradle subproject.
2. **Add capabilities** to policy plugins via their
   `META-INF/pipelinek/plugin.properties` (e.g.,
   `capability=policy.evaluation`). Studio reads these; no code
   change in Studio.
3. **New ResultDescriptor variants** in the policies module.
   Studio's renderer falls back to `renderGeneric(...)`; the
   policies module provides its own renderer.
4. **Optional: New MCP tools** at `studio.policies.*` in the
   `apps/mcp/` adapter. The shape follows the same
   `capabilities → operations` pattern as the rest of the studio.
5. **No changes to `studio-cli`, `studio-http`, `studio-application`, or `studio-infra-*`**. The seam exists; extensions use it.

## What this document is NOT

- Not a feature spec for Policies or Assurance. Those are
  separate projects.
- Not a recommendation to implement Policies/Assurance soon.
  R6 (distribución) is the canonical next milestone per the
  roadmap.
- Not a license / governance document. The seam's governance is
  inherited from the existing R2 + R4 contracts.

---

**Owner:** R2.11 (extensibility seam documentation).
**Recibo:** `docs/history/R2_11_RELEASE_RECEIPT.md` (a escribirse
cuando el item se cierre; este commit es la pieza de documentación
misma).
**Spec:** `docs/02-specifications/STEP_PLUGIN_LAB.md` §Reglas de
especialización.
**Plan vivo:** `odd/tasks/r2-plugin-lab.md`.
