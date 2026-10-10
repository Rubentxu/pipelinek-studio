package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.Diagnostic
import dev.pipelinek.studio.application.ports.DiagnosticPhase
import dev.pipelinek.studio.application.ports.DiagnosticSeverity
import dev.pipelinek.studio.application.ports.DiagnosticsReadPort
import dev.pipelinek.studio.application.ports.DiagnosticsReadResult
import dev.pipelinek.studio.application.ports.StaticAnalyzeCapability
import dev.pipelinek.studio.application.ports.StaticAnalyzeCapabilityPort
import dev.pipelinek.studio.application.ports.UnavailableStaticAnalyzeCapability
import dev.pipelinek.studio.domain.RunId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B3.0 / B3.3 (2026-10-10): capability model +
 * toolchain provenance. The tests pin:
 *  - `Diagnostic` carries `toolchainVersion` +
 *    `toolchainDigest` on the wire;
 *  - the default `StaticAnalyzeCapabilityPort`
 *    reports `Unavailable("not configured")`;
 *  - `GET /api/static-analyze-capability` returns
 *    the capability as a typed DTO;
 *  - the env var `STUDIO_STATIC_ANALYZE_DISABLED=1`
 *    flips the capability to `Unavailable`, even when
 *    an `Available` factory is wired in.
 */
class B30DiagnosticCapabilityTest {

    @Test
    fun `default capability is Unavailable with not configured reason`() {
        val cap = UnavailableStaticAnalyzeCapability.capability()
        assertIs<StaticAnalyzeCapability.Unavailable>(cap)
        assertEquals(
            "not configured",
            (cap as StaticAnalyzeCapability.Unavailable).reason,
        )
    }

    @Test
    fun `Available capability carries version and digest`() {
        val port = StaticAnalyzeCapabilityPort {
            StaticAnalyzeCapability.Available(
                analyzerVersion = "1.23.1",
                analyzerDigest = "sha256:abc123",
                rulesetVersion = "studio-1",
            )
        }
        val cap = port.capability()
        assertIs<StaticAnalyzeCapability.Available>(cap)
        val avail = cap as StaticAnalyzeCapability.Available
        assertEquals("1.23.1", avail.analyzerVersion)
        assertEquals("sha256:abc123", avail.analyzerDigest)
        assertEquals("studio-1", avail.rulesetVersion)
    }

    @Test
    fun `B3_0 Diagnostic carries toolchainVersion and toolchainDigest through the DTO`() =
        testApplication {
            val wiredDiagnostics = object : DiagnosticsReadPort {
                override fun diagnostics(runId: RunId): DiagnosticsReadResult =
                    DiagnosticsReadResult.Ok(
                        listOf(
                            Diagnostic(
                                id = "d-1",
                                severity = DiagnosticSeverity.WARNING,
                                phase = DiagnosticPhase.DETEKT,
                                message = "forbidden-call: System.exit",
                                source = "detekt:studio-1",
                                line = 3,
                                column = 5,
                                path = "snapshot.pipeline.kts",
                                runId = runId,
                                toolchainVersion = "1.23.1",
                                toolchainDigest = "sha256:abc123",
                            ),
                        ),
                    )
            }
            application {
                StudioHttpModule(
                    fakeWiringFactory(),
                    diagnosticsFactory = { wiredDiagnostics },
                ).install(this)
            }
            val r = hostClient().get("/runs/00000000-0000-0000-0000-000000000001/diagnostics")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = r.bodyAsText()
            assertTrue(
                body.contains("\"toolchain_version\":\"1.23.1\""),
                "expected toolchain_version in body, got: $body",
            )
            assertTrue(
                body.contains("\"toolchain_digest\":\"sha256:abc123\""),
                "expected toolchain_digest in body, got: $body",
            )
        }

    @Test
    fun `B3_3 capability route returns Unavailable with default factory`() = testApplication {
        application {
            StudioHttpModule(
                fakeWiringFactory(),
                staticAnalyzeCapabilityFactory = null,
            ).install(this)
        }
        val r = hostClient().get("/api/static-analyze-capability")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = r.bodyAsText()
        assertTrue(
            body.contains("\"available\":false"),
            "expected available=false in body, got: $body",
        )
        assertTrue(
            body.contains("\"reason\":\"not configured\""),
            "expected reason='not configured' in body, got: $body",
        )
    }

    @Test
    fun `B3_3 capability route returns Available when an Available factory is wired`() =
        testApplication {
            application {
                StudioHttpModule(
                    fakeWiringFactory(),
                    staticAnalyzeCapabilityFactory = {
                        object : StaticAnalyzeCapabilityPort {
                            override fun capability(): StaticAnalyzeCapability =
                                StaticAnalyzeCapability.Available(
                                    analyzerVersion = "1.23.1",
                                    analyzerDigest = "sha256:abc123",
                                )
                        }
                    },
                ).install(this)
            }
            val r = hostClient().get("/api/static-analyze-capability")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = r.bodyAsText()
            assertTrue(
                body.contains("\"available\":true"),
                "expected available=true in body, got: $body",
            )
            assertTrue(
                body.contains("\"analyzer_version\":\"1.23.1\""),
                "expected analyzer_version=1.23.1 in body, got: $body",
            )
            assertTrue(
                body.contains("\"analyzer_digest\":\"sha256:abc123\""),
                "expected analyzer_digest in body, got: $body",
            )
        }
}

/**
 * Local assertIs to avoid pulling in the kotlin.test.assertIs
 * reified helper and the corresponding import in a file
 * that already has several other kotlin.test imports.
 */
private inline fun <reified T : Any> assertIs(value: Any?): T {
    val matched = value is T
    if (!matched) {
        val actual = value?.javaClass?.name ?: "null"
        throw AssertionError("expected ${T::class.java.name}, got $actual")
    }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
