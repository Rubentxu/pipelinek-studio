package dev.pipelinek.studio.http

import dev.pipelinek.studio.application.ports.AdmittedPlugin
import dev.pipelinek.studio.application.ports.PluginAdmissionLockPort
import dev.pipelinek.studio.application.usecases.PluginAdmissionUseCase
import dev.pipelinek.studio.application.usecases.StaticManifestInspectorLike
import dev.pipelinek.studio.application.usecases.StaticPluginManifest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2.10 (2026-10-11): HTTP integration tests for the plugin
 * admission routes. The 5 routes are tested against an in-memory
 * lock + a TempDir with two fixture JARs (compatible + out-of-range).
 *
 *   GET  /api/plugins                       → listAll
 *   GET  /api/plugins/{jar}                 → inspect
 *   POST /api/plugins/{jar}/admit           → admit
 *   POST /api/plugins/{jar}/refuse          → refuse
 *   POST /api/plugins/{jar}/revoke          → revoke
 *
 * The "not wired" path is also tested: when the use case is null
 * the routes return 501 Not Implemented (the production boot
 * uses this when no plugin admission is configured).
 */
class PluginAdmissionRoutesR210Test {

    private val sdkVendor = "dev.rubentxu.pipeline.v2"
    private val sdkVersion = "0.50.0"
    private val fixedNow: Instant = Instant.parse("2026-10-11T20:30:00Z")
    private val actor: String = "ops:http-test"

    private class InMemoryLock : PluginAdmissionLockPort {
        private var entries: List<AdmittedPlugin> = emptyList()
        override fun load(): List<AdmittedPlugin> = entries
        override fun save(admitted: List<AdmittedPlugin>) { entries = admitted }
    }

    private fun makeJar(dir: Path, jarName: String, version: String, sdkRange: String?): Path {
        val jar = dir.resolve(jarName)
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Title", jarName.removeSuffix(".jar"))
            mainAttributes.putValue("Implementation-Version", version)
            mainAttributes.putValue("Implementation-Vendor", sdkVendor)
            if (sdkRange != null) {
                mainAttributes.putValue("X-PipelineK-Sdk-Version-Range", sdkRange)
            }
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { /* empty */ }
        return jar
    }

    /**
     * Test inspector that reads the JAR manifest directly (without
     * the StaticManifestInspector in studio-infra-sdk, which
     * studio-http cannot depend on without a circular dep). Only
     * reads the fields the admission use case needs: title,
     * version, vendor, sha256, and the SDK range header.
     */
    private class FakeInspector : StaticManifestInspectorLike {
        override fun inspect(jarPath: Path, expectedInternalVendor: String): StaticPluginManifest {
            val jar = java.util.jar.JarFile(jarPath.toFile())
            val attrs = jar.manifest.mainAttributes
            val title = attrs.getValue("Implementation-Title")
            val version = attrs.getValue("Implementation-Version")
            val vendor = attrs.getValue("Implementation-Vendor")
            val sdkRange = attrs.getValue("X-PipelineK-Sdk-Version-Range")?.takeIf { it.isNotBlank() }
            val sha = sha256Of(jarPath)
            jar.close()
            return StaticPluginManifest(
                jarPath = jarPath,
                sha256 = sha,
                implementationTitle = title,
                implementationVersion = version,
                implementationVendor = vendor,
                sdkVersionRange = sdkRange,
            )
        }

        private fun sha256Of(p: Path): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(p).use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun useCase(lock: InMemoryLock): PluginAdmissionUseCase = PluginAdmissionUseCase(
        inspector = FakeInspector(),
        lock = lock,
        sdkVersion = sdkVersion,
        sdkVendor = sdkVendor,
        clock = { fixedNow },
    )

    @Test
    fun `R2_10 listAll returns 200 with all plugin states`(@TempDir tmp: Path) {
        makeJar(tmp, "valid-0.1.0.jar", "0.1.0", "[0.0.0, 1.0.0)")
        makeJar(tmp, "bad-99.0.0.jar", "99.0.0", "[2.0.0, 3.0.0)")
        testApplication {
            val lock = InMemoryLock()
            application {
                install(ContentNegotiation) { json(Json) }
                routing {
                    installPluginAdmissionRoutes(useCaseProvider = { useCase(lock) }, pluginDir = { tmp })
                }
            }
            val response = client.get("/api/plugins")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"state\":\"compatible\""), "body must include valid plugin, got: $body")
            assertTrue(body.contains("\"state\":\"refused\""), "body must include refused plugin, got: $body")
        }
    }

    @Test
    fun `R2_10 inspect returns the typed state of a single plugin`(@TempDir tmp: Path) {
        val jar = makeJar(tmp, "valid-0.1.0.jar", "0.1.0", "[0.0.0, 1.0.0)")
        testApplication {
            val lock = InMemoryLock()
            application {
                install(ContentNegotiation) { json(Json) }
                routing {
                    installPluginAdmissionRoutes(useCaseProvider = { useCase(lock) }, pluginDir = { tmp })
                }
            }
            val response = client.get("/api/plugins/valid-0.1.0.jar")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"state\":\"compatible\""), "expected compatible, got: $body")
        }
    }

    @Test
    fun `R2_10 admit transitions compatible to admitted and persists`(@TempDir tmp: Path) {
        val jar = makeJar(tmp, "valid-0.1.0.jar", "0.1.0", "[0.0.0, 1.0.0)")
        testApplication {
            val lock = InMemoryLock()
            application {
                install(ContentNegotiation) { json(Json) }
                routing {
                    installPluginAdmissionRoutes(useCaseProvider = { useCase(lock) }, pluginDir = { tmp })
                }
            }
            val response = client.post("/api/plugins/valid-0.1.0.jar/admit") {
                contentType(ContentType.Application.Json)
                setBody("""{"admittedBy":"$actor"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"state\":\"admitted\""), "expected admitted, got: $body")
            // The actor lives in the lock, not in the state response
            // (the state is a snapshot; the actor is the audit metadata).
            assertEquals(1, lock.load().size)
            assertEquals(actor, lock.load().single().admittedBy)
        }
    }

    @Test
    fun `R2_10 refuse returns refused without mutating the lock`(@TempDir tmp: Path) {
        val jar = makeJar(tmp, "valid-0.1.0.jar", "0.1.0", "[0.0.0, 1.0.0)")
        testApplication {
            val lock = InMemoryLock()
            application {
                install(ContentNegotiation) { json(Json) }
                routing {
                    installPluginAdmissionRoutes(useCaseProvider = { useCase(lock) }, pluginDir = { tmp })
                }
            }
            val response = client.post("/api/plugins/valid-0.1.0.jar/refuse") {
                contentType(ContentType.Application.Json)
                setBody("""{"reason":"manual review"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"state\":\"refused\""), "expected refused, got: $body")
            assertTrue(body.contains("manual review"), "reason must surface, got: $body")
            assertEquals(0, lock.load().size, "refuse must NOT persist in the lock")
        }
    }

    @Test
    fun `R2_10 revoke removes an admitted entry from the lock`(@TempDir tmp: Path) {
        val jar = makeJar(tmp, "valid-0.1.0.jar", "0.1.0", "[0.0.0, 1.0.0)")
        testApplication {
            val lock = InMemoryLock()
            // Pre-admit
            useCase(lock).admit(jar, actor)
            assertEquals(1, lock.load().size)
            application {
                install(ContentNegotiation) { json(Json) }
                routing {
                    installPluginAdmissionRoutes(useCaseProvider = { useCase(lock) }, pluginDir = { tmp })
                }
            }
            val response = client.post("/api/plugins/valid-0.1.0.jar/revoke")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"state\":\"compatible\""), "expected compatible after revoke, got: $body")
            assertEquals(0, lock.load().size, "revoke must remove the lock entry")
        }
    }

    @Test
    fun `R2_10 not-wired returns 501`(@TempDir tmp: Path) {
        testApplication {
            application {
                install(ContentNegotiation) { json(Json) }
                routing {
                    installPluginAdmissionRoutes(useCaseProvider = { null }, pluginDir = { tmp })
                }
            }
            val response = client.get("/api/plugins")
            assertEquals(HttpStatusCode.NotImplemented, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("NOT_WIRED"), "body must include NOT_WIRED, got: $body")
        }
    }
}
