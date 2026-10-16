@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.ScheduleDecision
import dev.pipelinek.studio.application.ports.ScheduleRunResult
import dev.pipelinek.studio.application.ports.ScheduleRunUseCase
import dev.pipelinek.studio.application.ports.WorkerRequirement
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for the SFW-H5.4 HTTP route
 * for the H5.1
 * [dev.pipelinek.studio.application.ports.ScheduleRunUseCase].
 */
@DisplayName("ScheduleRunRoutes (SFW-H5.4 POST /api/runs/schedule)")
class ScheduleRunRoutesH54Test {

    private val requirementJson = """
        {
            "cpuMillicores": 1000,
            "memoryMb": 512,
            "region": "us-west-2"
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // Falsifier 1: Placed -> 200 OK with workerId + region
    // ------------------------------------------------------------------
    @Test
    fun `Placed maps to 200 OK with workerId and region`() = testApplication {
        val fake = FakeScheduleRunUseCaseForH54().apply {
            nextResult = ScheduleRunResult.Placed(
                workerId = "worker-1",
                region = "us-west-2",
            )
        }
        application {
            install(ContentNegotiation) { json(Json) }
            routing { installScheduleRunRoutes(scheduleRunProvider = { fake }) }
        }
        val response = client.post("/api/runs/schedule") {
            contentType(ContentType.Application.Json)
            setBody(requirementJson)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"workerId\":\"worker-1\""), "body must include workerId: $body")
        assertTrue(body.contains("\"region\":\"us-west-2\""), "body must include region: $body")
    }

    // ------------------------------------------------------------------
    // Falsifier 2: NoCapacity -> 507 Insufficient Storage with reason
    // ------------------------------------------------------------------
    @Test
    fun `NoCapacity maps to 507 Insufficient Storage with reason`() = testApplication {
        val fake = FakeScheduleRunUseCaseForH54().apply {
            nextResult = ScheduleRunResult.NoCapacity("no worker in us-west-2 has 1GiB memory")
        }
        application {
            install(ContentNegotiation) { json(Json) }
            routing { installScheduleRunRoutes(scheduleRunProvider = { fake }) }
        }
        val response = client.post("/api/runs/schedule") {
            contentType(ContentType.Application.Json)
            setBody(requirementJson)
        }
        assertEquals(HttpStatusCode.InsufficientStorage, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("us-west-2"), "body must include the reason: $body")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: Refused -> 503 Service Unavailable with reason
    // ------------------------------------------------------------------
    @Test
    fun `Refused maps to 503 Service Unavailable with reason`() = testApplication {
        val fake = FakeScheduleRunUseCaseForH54().apply {
            nextResult = ScheduleRunResult.Refused(
                reason = "transport failure on WorkerPool.schedule: connection reset",
            )
        }
        application {
            install(ContentNegotiation) { json(Json) }
            routing { installScheduleRunRoutes(scheduleRunProvider = { fake }) }
        }
        val response = client.post("/api/runs/schedule") {
            contentType(ContentType.Application.Json)
            setBody(requirementJson)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("connection reset"), "body must include the reason: $body")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: Unknown -> 202 Accepted with reason
    // ------------------------------------------------------------------
    @Test
    fun `Unknown maps to 202 Accepted with reason`() = testApplication {
        val fake = FakeScheduleRunUseCaseForH54().apply {
            nextResult = ScheduleRunResult.Unknown(
                reason = "pool has no record of worker-1 (a placement race)",
            )
        }
        application {
            install(ContentNegotiation) { json(Json) }
            routing { installScheduleRunRoutes(scheduleRunProvider = { fake }) }
        }
        val response = client.post("/api/runs/schedule") {
            contentType(ContentType.Application.Json)
            setBody(requirementJson)
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("placement race"), "body must include the reason: $body")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: null provider -> 501 Not Implemented (honest fallback)
    // ------------------------------------------------------------------
    @Test
    fun `null provider maps to 501 Not Implemented (the unwired state is honest)`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json) }
            routing { installScheduleRunRoutes(scheduleRunProvider = { null }) }
        }
        val response = client.post("/api/runs/schedule") {
            contentType(ContentType.Application.Json)
            setBody(requirementJson)
        }
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("SCHEDULE_NOT_WIRED"), "body must include the unwired-state code: $body")
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeScheduleRunUseCaseForH54 : ScheduleRunUseCase {
    var nextResult: ScheduleRunResult = ScheduleRunResult.NoCapacity("default")
    var scheduleCalls: Int = 0
        private set
    var lastRequirement: WorkerRequirement? = null
        private set

    override suspend fun schedule(requirement: WorkerRequirement): ScheduleRunResult {
        scheduleCalls += 1
        lastRequirement = requirement
        return nextResult
    }
}
