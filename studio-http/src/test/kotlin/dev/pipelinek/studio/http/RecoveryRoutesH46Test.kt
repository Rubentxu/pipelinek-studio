@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.RecoveryBindingFailure
import dev.pipelinek.studio.application.ports.RecoveryResult
import dev.pipelinek.studio.application.ports.RecoveryTransition
import dev.pipelinek.studio.application.ports.RecoveryUseCase
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import io.ktor.client.request.post
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
 * Falsifiers for the SFW-H4.6 HTTP route
 * for the H4.5
 * [dev.pipelinek.studio.application.ports.RecoveryUseCase].
 *
 * The tests pin the 4 status codes for the
 * 4 arms of [RecoveryResult] + the 501
 * Not Implemented fallback when the
 * production boot did not wire the
 * recovery use case.
 */
@DisplayName("RecoveryRoutes (SFW-H4.6 POST /api/admin/recovery)")
class RecoveryRoutesH46Test {

    // ------------------------------------------------------------------
    // Falsifier 1: Recovered -> 200 OK with transition list
    // ------------------------------------------------------------------
    @Test
    fun `Recovered maps to 200 OK with total + unchanged + transitioned list`() = testApplication {
        val fake = FakeRecoveryUseCaseForH46().apply {
            nextResult = RecoveryResult.Recovered(
                total = 3,
                unchanged = 1,
                transitioned = listOf(
                    RecoveryTransition(
                        studioRunId = RunId("h46-1"),
                        previousState = RunState.QUEUED,
                        newState = RunState.RUNNING,
                        reason = "fabric observed started",
                    ),
                    RecoveryTransition(
                        studioRunId = RunId("h46-2"),
                        previousState = RunState.QUEUED,
                        newState = RunState.RUNNING,
                        reason = "fabric observed started",
                    ),
                ),
            )
        }
        application {
            install(ContentNegotiation) { json(Json) }
            routing {
                installRecoveryRoutes(recoveryProvider = { fake })
            }
        }
        val response = client.post("/api/admin/recovery")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"total\":3"), "body must include total=3: $body")
        assertTrue(body.contains("\"unchanged\":1"), "body must include unchanged=1: $body")
        assertTrue(body.contains("\"h46-1\""), "body must include the transitioned runId: $body")
        assertTrue(body.contains("\"h46-2\""), "body must include the transitioned runId: $body")
        assertTrue(body.contains("\"QUEUED\""), "body must include the previousState: $body")
        assertTrue(body.contains("\"RUNNING\""), "body must include the newState: $body")
        assertEquals(1, fake.recoverAllCalls)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: PartiallyRecovered -> 207 Multi-Status with breakdown
    // ------------------------------------------------------------------
    @Test
    fun `PartiallyRecovered maps to 207 Multi-Status with transitioned + unknown + refused lists`() = testApplication {
        val fake = FakeRecoveryUseCaseForH46().apply {
            nextResult = RecoveryResult.PartiallyRecovered(
                total = 3,
                unchanged = 0,
                transitioned = listOf(
                    RecoveryTransition(
                        studioRunId = RunId("h46-3"),
                        previousState = RunState.QUEUED,
                        newState = RunState.RUNNING,
                        reason = "fabric observed started",
                    ),
                ),
                unknown = listOf(
                    RecoveryBindingFailure(
                        studioRunId = RunId("h46-4"),
                        reason = "fabric has no binding",
                    ),
                ),
                refused = listOf(
                    RecoveryBindingFailure(
                        studioRunId = RunId("h46-5"),
                        reason = "transport failure on reconcile: connection reset",
                    ),
                ),
            )
        }
        application {
            install(ContentNegotiation) { json(Json) }
            routing {
                installRecoveryRoutes(recoveryProvider = { fake })
            }
        }
        val response = client.post("/api/admin/recovery")
        assertEquals(HttpStatusCode.MultiStatus, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"h46-3\""), "body must include the transitioned runId: $body")
        assertTrue(body.contains("\"h46-4\""), "body must include the unknown runId: $body")
        assertTrue(body.contains("\"h46-5\""), "body must include the refused runId: $body")
        assertTrue(body.contains("connection reset"), "body must include the refused reason: $body")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: NoBindings -> 200 OK with total=0
    // ------------------------------------------------------------------
    @Test
    fun `NoBindings maps to 200 OK with total=0 unchanged=0 (distinguishes from Recovered with empty transitioned list)`() = testApplication {
        val fake = FakeRecoveryUseCaseForH46().apply {
            nextResult = RecoveryResult.NoBindings
        }
        application {
            install(ContentNegotiation) { json(Json) }
            routing {
                installRecoveryRoutes(recoveryProvider = { fake })
            }
        }
        val response = client.post("/api/admin/recovery")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"total\":0"), "body must include total=0: $body")
        assertTrue(body.contains("\"unchanged\":0"), "body must include unchanged=0: $body")
    }

    // ------------------------------------------------------------------
    // Falsifier 4: Refused -> 503 Service Unavailable with reason
    // ------------------------------------------------------------------
    @Test
    fun `Refused maps to 503 Service Unavailable with reason`() = testApplication {
        val fake = FakeRecoveryUseCaseForH46().apply {
            nextResult = RecoveryResult.Refused(
                reason = "recovery enumeration failed: RuntimeException: SQLite is locked",
            )
        }
        application {
            install(ContentNegotiation) { json(Json) }
            routing {
                installRecoveryRoutes(recoveryProvider = { fake })
            }
        }
        val response = client.post("/api/admin/recovery")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("SQLite is locked"), "body must include the refused reason: $body")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: null provider -> 501 Not Implemented (honest fallback)
    // ------------------------------------------------------------------
    @Test
    fun `null provider maps to 501 Not Implemented (the unwired state is honest)`() = testApplication {
        application {
            install(ContentNegotiation) { json(Json) }
            routing {
                installRecoveryRoutes(recoveryProvider = { null })
            }
        }
        val response = client.post("/api/admin/recovery")
        assertEquals(HttpStatusCode.NotImplemented, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("RECOVERY_NOT_WIRED"), "body must include the unwired-state code: $body")
    }
}

// --------------------------------------------------------------------
// Test fixtures
// --------------------------------------------------------------------

private class FakeRecoveryUseCaseForH46 : RecoveryUseCase {
    var nextResult: RecoveryResult = RecoveryResult.NoBindings
    var recoverAllCalls: Int = 0
        private set

    override suspend fun recoverAll(): RecoveryResult {
        recoverAllCalls += 1
        return nextResult
    }
}
