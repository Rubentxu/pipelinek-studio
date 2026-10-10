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
)

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
