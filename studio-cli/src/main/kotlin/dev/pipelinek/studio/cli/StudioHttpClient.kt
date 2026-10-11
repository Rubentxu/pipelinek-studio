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
import java.net.URI

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

/**
 * Minimal HTTP client used by the CLI. Calls the Studio API per OpenAPI.
 *
 * Audit F-P1-CLI-1 (2026-10-10): the previous implementation used a
 * trust-all X509TrustManager unconditionally — i.e. for every host the
 * operator pointed the CLI at. Trust-all is the only way to accept
 * the studio's self-signed cert on loopback (audit C-1), but it is
 * a footgun for non-loopback hosts: an operator who runs
 *
 *   studio --host=attacker.example create-experiment --name foo
 *
 * silently talks to whoever answers the IP, with no TLS validation.
 * This commit gates the trust-all to loopback hosts only
 * (127.0.0.1, ::1, localhost) and uses the JVM default truststore
 * for everything else. The behavior change is observable: pointing
 * the CLI at a remote host now validates the cert chain.
 */
class StudioHttpClient(baseUrl: String) {
    private val isLoopback: Boolean = isLoopbackHost(baseUrl)
    private val client = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            https {
                trustManager = if (isLoopback) {
                    // Audit C-1: the studio serves HTTPS with a self-signed
                    // cert on loopback. The default JVM truststore would
                    // reject it, so for loopback we trust-all. This is
                    // the only host class where trust-all is acceptable.
                    object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(
                            chain: Array<out java.security.cert.X509Certificate>?,
                            authType: String?,
                        ) {}
                        override fun checkServerTrusted(
                            chain: Array<out java.security.cert.X509Certificate>?,
                            authType: String?,
                        ) {}
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                            arrayOf()
                    }
                } else {
                    // Non-loopback: use the JVM default truststore (the
                    // real one, including system roots). An untrusted
                    // cert now produces a clear SSLHandshakeException
                    // instead of a silent MITM.
                    javax.net.ssl.TrustManagerFactory
                        .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                        .apply { init(null as java.security.KeyStore?) }
                        .trustManagers
                        .first { it is javax.net.ssl.X509TrustManager } as javax.net.ssl.X509TrustManager
                }
            }
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
        // R0.2.5 (2026-10-11): the create family accepts 200 OK,
        // 201 Created, and 202 Accepted. The backend may return
        // 201 when the resource is created inline, 202 when it is
        // reserved for async execution (ReservationOutcome.Reserved),
        // or 200 when the body itself is the canonical representation
        // (legacy). Any of the three is a successful create.
        require(r.status.value in createAcceptableStatuses) {
            "create-experiment failed: ${r.status} $body"
        }
        return JsonLenient.decodeFromString(CreateExperimentDto.serializer(), body)
    }

    suspend fun sealSnapshot(experimentId: String, source: String, creator: String): SnapshotDto {
        val payload = Json.encodeToString(SealSnapshotReq.serializer(), SealSnapshotReq(source, creator))
        val r = client.post("$base/experiments/$experimentId/snapshots") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val body = r.bodyAsText()
        require(r.status.value in createAcceptableStatuses) {
            "snapshot failed: ${r.status} $body"
        }
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
        // R0.2.5 (2026-10-11): the backend distinguishes the
        // execution path from the reservation path:
        //   * 201 Created — ReservationOutcome.Admitted (started)
        //   * 202 Accepted — ReservationOutcome.Reserved (durable)
        //   * 200 OK — legacy paths that returned the canonical body
        // All three are a successful POST /runs from the client's
        // perspective; the contract difference is in the body
        // (claim_status, upstreamRunId presence).
        require(r.status.value in createAcceptableStatuses) {
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
        val parsed = JsonLenient.parseToJsonElement(body).toString()
        return mapOf("raw" to parsed)
    }

    companion object {
        // R0.2.5 (2026-10-11): the POST create family accepts 200 OK,
        // 201 Created, and 202 Accepted. The value set is referenced
        // from createExperiment / sealSnapshot / startRun — the three
        // endpoints whose backend response codes were previously a
        // moving target. The semantic split:
        //   * 200 OK — legacy paths returning the canonical body
        //   * 201 Created — ReservationOutcome.Admitted (started)
        //   * 202 Accepted — ReservationOutcome.Reserved (durable)
        val createAcceptableStatuses: Set<Int> = setOf(
            HttpStatusCode.OK.value,        // 200
            HttpStatusCode.Created.value,   // 201
            HttpStatusCode.Accepted.value,  // 202
        )

        /**
         * Test seam: pure host classification. Internal so the unit
         * test can exercise the loopback / non-loopback split without
         * spinning up an HttpClient.
         */
        internal fun isLoopbackHost(baseUrl: String): Boolean {
            return try {
                val uri = URI(baseUrl)
                val raw = uri.host ?: return false
                // URI.getHost() returns IPv6 addresses wrapped in
                // square brackets (e.g. "[::1]"); strip them before
                // the comparisons below.
                val host = if (raw.startsWith("[") && raw.endsWith("]")) {
                    raw.substring(1, raw.length - 1)
                } else raw
                host.equals("localhost", ignoreCase = true) ||
                    host == "127.0.0.1" ||
                    host == "::1" ||
                    host.startsWith("127.")
            } catch (_: Throwable) {
                // Malformed URL: default to non-loopback so the JVM
                // default truststore is used (safer than falling
                // through to trust-all).
                false
            }
        }
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
