package dev.pipelinek.studio.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level JSON shapes for the HTTP API. Per docs/02-specifications/HTTP_AND_STREAMING_API.md
 * these are versioned via the OpenAPI schema; breaking changes require a v2 route.
 */

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val phase: String? = null,
    val actions: List<String> = emptyList(),
)

@Serializable
data class CreateExperimentRequest(val name: String)

@Serializable
data class CreateExperimentResponse(
    @SerialName("experiment_id") val experimentId: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SealSnapshotRequest(
    val source: String,
    val creator: String,
)

@Serializable
data class SealSnapshotResponse(
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("experiment_id") val experimentId: String,
    val sha256: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class StartRunRequest(
    @SerialName("experiment_id") val experimentId: String,
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("scenario_id") val scenarioId: String,
)

@Serializable
data class StartRunResponse(
    @SerialName("run_id") val runId: String,
    val state: String,
    @SerialName("upstream_run_id") val upstreamRunId: String? = null,
)

@Serializable
data class RunStatusResponse(
    @SerialName("run_id") val runId: String,
    val state: String,
    @SerialName("upstream_run_id") val upstreamRunId: String? = null,
    @SerialName("observed_at") val observedAt: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val service: String = "pipelinek-studio",
    val version: String = "0.1.0-alpha.1",
)

@Serializable
data class VersionResponse(
    val studio: String,
    @SerialName("pipelinek_runtime") val pipelinekRuntime: String? = null,
    @SerialName("pipelinek_runtime_digest") val pipelinekRuntimeDigest: String? = null,
    @SerialName("pipelinek_runtime_capabilities") val pipelinekRuntimeCapabilities: List<String> = emptyList(),
)

@Serializable
data class StepContractDto(
    val id: String,
    val name: String,
    @SerialName("plugin_jar") val pluginJar: String? = null,
    val capabilities: List<String> = emptyList(),
    @SerialName("input_schema_present") val inputSchemaPresent: Boolean = false,
    @SerialName("output_schema_present") val outputSchemaPresent: Boolean = false,
    val affordances: List<StepAffordanceDto> = emptyList(),
)

@Serializable
data class StepAffordanceDto(
    val kind: String,
    val label: String,
)

@Serializable
data class StepCatalogDto(
    val steps: List<StepContractDto>,
)

@Serializable
data class DiagnosticDto(
    val id: String,
    val severity: String,
    val phase: String,
    val message: String,
    val source: String,
    val line: Int? = null,
    val column: Int? = null,
    val path: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val actions: List<String> = emptyList(),
)

@Serializable
data class DiagnosticsDto(
    val diagnostics: List<DiagnosticDto>,
)

@Serializable
data class StepIrDto(
    @SerialName("op_id") val opId: String,
    val name: String,
    @SerialName("class_name") val className: String,
    val capabilities: List<String> = emptyList(),
    @SerialName("input_schema_present") val inputSchemaPresent: Boolean = false,
    @SerialName("output_schema_present") val outputSchemaPresent: Boolean = false,
    val phase: String,
)

@Serializable
data class StageIrDto(
    val name: String,
    val steps: List<StepIrDto>,
)

@Serializable
data class IrDto(
    @SerialName("run_id") val runId: String,
    val stages: List<StageIrDto>,
    @SerialName("phases_observed") val phasesObserved: List<String>,
)

@Serializable
data class IrResultDto(
    val snapshot: IrDto? = null,
    val reason: String? = null,
)
