package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId

/**
 * Step catalog reads per S2 (Plugin Lab) ROADMAP §"E2.2 Snapshot StepRegistry
 * admitido, `GET /steps`, detalle de StepContract, schemas opcionales,
 * capabilities y action affordances".
 *
 * Per UPSTREAM_GROUNDING.md and ADR-008: the catalog is the set of steps the SDK has
 * actually admitted. Studio never keeps a hardcoded copy of the catalog
 * (per ARCHITECTURE.md "No mantiene catálogo hardcoded de Steps").
 */

/** Static description of a Step that the SDK has admitted. */
data class StepContract(
    val id: String,
    val name: String,
    val pluginJar: String?,
    val capabilities: Set<String>,
    val inputSchema: StepSchema?,
    val outputSchema: StepSchema?,
    val affordances: List<StepAffordance>,
    /**
     * R2.7 cierre (2026-10-11): provenance of the step in the
     * catalog. `null` for built-in SDK steps (no JAR). For
     * external plugin steps, this is the surface that
     * `PluginAdmissionState` translates to: vendor, SDK version
     * range, discovery path. The catalog must surface this
     * honestly so a UI / API consumer can show "this step comes
     * from plugin X admitted at time T" without the operator
     * having to look elsewhere.
     */
    val provenance: StepProvenance? = null,
    /**
     * R2.7 cierre (2026-10-11): refusal reason. Non-null ONLY
     * when the admission gate refused this step (i.e., the step
     * is in the catalog because it is discoverable, but the
     * gate refused it — version out of range, vendor mismatch,
     * sha256 drift, etc.). When non-null, the operator knows
     * why this step is on the catalog but not admissible.
     */
    val refusalReason: String? = null,
)

/**
 * R2.7 cierre (2026-10-11): the origin of a Step on the catalog.
 * This is the "where did this step come from" surface, NOT the
 * "is this step admissible" surface (that's the admission gate's
 * job). Built-in SDK steps have `null` provenance; external
 * plugin steps have the JAR path, the SDK vendor, the version
 * range, and the discovery path (which determines whether the
 * catalog trusts the ServiceLoader declaration or the SDK's
 * StepRegistry snapshot).
 */
data class StepProvenance(
    val pluginJar: String,
    val vendor: String?,
    val sdkVersionRange: String?,
    val discovery: StepDiscovery,
)

/**
 * R2.7 cierre (2026-10-11): how the catalog discovered this
 * step. Pre-R2.7 the catalog was a flat list; this enum makes
 * the discovery path explicit so the UI can render it
 * ("discovered via ServiceLoader" vs "discovered via SDK
 * StepRegistry snapshot") and so audits can trace why a step
 * was admitted.
 */
enum class StepDiscovery {
    /** ServiceLoader META-INF/services/<interface> declaration. */
    ServiceLoader,

    /** SDK StepRegistry snapshot (when the SDK subprocess is alive). */
    SdkRegistry,

    /** Built into the SDK itself; no JAR. */
    Builtin,
}

/**
 * JSON-Schema-shaped descriptor for Step codec. Per ROADMAP §R-06, StepCodec.schema()
 * may return `{}` — we surface that honestly as `NO_SCHEMA` rather than fabricating
 * a default form.
 */
sealed interface StepSchema {
    object NoSchema : StepSchema
    data class Provided(val raw: String) : StepSchema
}

data class StepAffordance(
    val kind: String,
    val label: String,
)

sealed interface StepCatalogResult {
    data class Ok(val steps: List<StepContract>) : StepCatalogResult
    data class Failure(val reason: String) : StepCatalogResult
}

interface StepCatalogReadPort {
    fun list(): StepCatalogResult
    fun describe(id: String): StepCatalogResult
}

/**
 * R2.6 (2026-10-11): one row in the collision report. When two or
 * more [StepContract]s in the catalog share the same `id`
 * (stepKey), [StepCollisionResolver] picks one as the winner and
 * reports the rest as [CollisionReport] entries. The report is
 * pure data: no mutation, no side effects. The catalog stays the
 * source of truth; the report is a lens on it.
 *
 * The [reason] field is one of:
 *  - "lower_version"   — the loser had a strictly lower semver.
 *  - "same_version_tie" — semver was equal; tie broken by
 *                         [winnerPluginJar] lexicographic order.
 *  - "no_version"      — neither party carried a version; the
 *                         resolver fell back to pluginJar order.
 *
 * The [versionLoser] / [versionWinner] are the parsed semver
 * strings, or `null` when not declared (built-in SDK steps with
 * no JAR usually have no version). They are diagnostic: a UI can
 * show "step X from plugin Y (1.2.3) shadowed by step X from
 * plugin Z (1.5.0)".
 */
data class CollisionReport(
    val stepKey: String,
    val winnerPluginJar: String,
    val winnerVersion: String?,
    val loserPluginJar: String,
    val loserVersion: String?,
    val reason: String,
)

/**
 * R2.6 (2026-10-11): the resolved catalog. The catalog source
 * (e.g. [StepCatalogReadPort.list]) may return duplicates (two
 * plugins declaring the same step key). The resolver picks a
 * winner per [StepContract.id] and surfaces the rest as
 * [collisions]. The [winners] list is the deduplicated view the
 * studio should dispatch against.
 *
 * The original input is not preserved; consumers that need it
 * should keep a reference at the call site.
 */
data class ResolvedCatalog(
    val winners: List<StepContract>,
    val collisions: List<CollisionReport>,
)

/** S2 E2.5 ResultDescriptor — generic, no specialized UI for Policy/Assurance. */
sealed interface ResultDescriptor {
    data class Text(val body: String, val mime: String = "text/plain") : ResultDescriptor
    data class Json(val body: String) : ResultDescriptor
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ResultDescriptor
    data class ArtifactRefs(val refs: List<String>) : ResultDescriptor
    data class NotAvailable(val reason: String) : ResultDescriptor
}

/** S2 E2.3 Scenario execution — bound to a specific Step + input.
 *
 *  [runId] is the run identifier assigned by the caller (audit C-4,
 *  2026-10-10). A real [ScenarioRunnerPort] implementation must
 *  persist a run.db keyed by this id, so a downstream
 *  [RunComparisonPort] can read the events written by the execution.
 *  Without an explicit [runId] the agent workflow would mint one and
 *  forget it, leaving [RunComparisonPort.compare] comparing against a
 *  run that never executed — a fake PASS.
 */
data class ScenarioExecutionRequest(
    val scenarioId: ScenarioId,
    val stepId: String,
    val inputJson: String,
    val runId: RunId = RunId.new(),
)

sealed interface ScenarioExecutionResult {
    data class Ok(val descriptor: ResultDescriptor, val runtimeMs: Long) : ScenarioExecutionResult
    data class Failure(val reason: String) : ScenarioExecutionResult
}

interface ScenarioRunnerPort {
    fun execute(req: ScenarioExecutionRequest): ScenarioExecutionResult
}
