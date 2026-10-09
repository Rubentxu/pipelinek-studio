package dev.pipelinek.studio.cli

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HealthDto(val status: String)

@Serializable
data class CreateExperimentDto(
    val experiment_id: String,
    val name: String,
    val created_at: String,
)

@Serializable
data class SnapshotDto(
    val snapshot_id: String,
    val experiment_id: String,
    val sha256: String,
    val created_at: String,
)

@Serializable
data class RunDto(
    val run_id: String,
    val state: String,
    val upstream_run_id: String? = null,
)

/** Minimal HTTP client used by the CLI. Calls the Studio API per OpenAPI. */
class StudioHttpClient(baseUrl: String) {
    private val client = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = false
    }
    private val base: String = baseUrl.trimEnd('/')

    suspend fun health(): String {
        val r = client.get("$base/health")
        return "status=${r.status.value}"
    }

    suspend fun createExperiment(name: String): CreateExperimentDto {
        val payload = Json.encodeToString(CreateExperimentReq.serializer(), CreateExperimentReq(name))
        val r = client.post("$base/experiments") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val body = r.bodyAsText()
        require(r.status == HttpStatusCode.OK) { "create-experiment failed: ${r.status} $body" }
        return JsonLenient.decodeFromString(CreateExperimentDto.serializer(), body)
    }

    suspend fun sealSnapshot(experimentId: String, source: String, creator: String): SnapshotDto {
        val payload = Json.encodeToString(SealSnapshotReq.serializer(), SealSnapshotReq(source, creator))
        val r = client.post("$base/experiments/$experimentId/snapshots") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val body = r.bodyAsText()
        require(r.status == HttpStatusCode.OK) { "snapshot failed: ${r.status} $body" }
        return JsonLenient.decodeFromString(SnapshotDto.serializer(), body)
    }

    suspend fun startRun(experiment: String, snapshot: String, scenario: String, idemKey: String): RunDto {
        val payload = Json.encodeToString(StartRunReq.serializer(), StartRunReq(experiment, snapshot, scenario))
        val r = client.post("$base/runs") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", idemKey)
            setBody(payload)
        }
        val body = r.bodyAsText()
        require(r.status == HttpStatusCode.OK || r.status == HttpStatusCode.Accepted) {
            "start-run failed: ${r.status} $body"
        }
        return JsonLenient.decodeFromString(RunDto.serializer(), body)
    }

    suspend fun runStatus(runId: String): String {
        val r = client.get("$base/runs/$runId")
        return "${r.status.value}"
    }

    suspend fun listSteps(): List<Map<String, String?>> {
        val r = client.get("$base/steps")
        if (r.status != HttpStatusCode.OK) error("steps request failed: ${r.status} ${r.bodyAsText()}")
        val body = r.bodyAsText()
        val parsed = JsonLenient.parseToJsonElement(body).toString()
        return listOf(mapOf("raw" to parsed))
    }

    suspend fun listRuns(state: String? = null, limit: Int? = null, offset: Int? = null): Map<String, String?> {
        val q = buildList {
            state?.let { add("state=$it") }
            limit?.let { add("limit=$it") }
            offset?.let { add("offset=$it") }
        }
        val suffix = if (q.isEmpty()) "" else "?" + q.joinToString("&")
        val r = client.get("$base/runs$suffix")
        val body = r.bodyAsText()
        if (r.status != HttpStatusCode.OK) error("list-runs request failed: ${r.status} $body")
        return mapOf("raw" to JsonLenient.parseToJsonElement(body).toString())
    }

    suspend fun version(): Map<String, String?> {
        val r = client.get("$base/api/version")
        val body = r.bodyAsText()
        if (r.status != HttpStatusCode.OK) error("version request failed: ${r.status} $body")
        val parsed = JsonLenient.parseToJsonElement(body).toString()
        return mapOf("raw" to parsed)
    }

    suspend fun metrics(): Map<String, String?> {
        val r = client.get("$base/api/metrics")
        val body = r.bodyAsText()
        if (r.status != HttpStatusCode.OK) error("metrics request failed: ${r.status} $body")
        return mapOf("raw" to JsonLenient.parseToJsonElement(body).toString())
    }
}

@Serializable data class CreateExperimentReq(val name: String)
@Serializable data class SealSnapshotReq(val source: String, val creator: String)
@Serializable data class StartRunReq(
    val experiment_id: String,
    val snapshot_id: String,
    val scenario_id: String,
)

private val JsonLenient: Json = Json { ignoreUnknownKeys = true; isLenient = true }
