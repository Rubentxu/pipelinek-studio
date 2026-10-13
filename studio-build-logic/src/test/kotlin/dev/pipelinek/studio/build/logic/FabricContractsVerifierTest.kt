package dev.pipelinek.studio.build.logic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Falsifiers for the [FabricContractsVerifier] (SFW H1 Phase A.2).
 *
 * The verifier inspects the local Maven repo for the 6 Fabric contract
 * artifacts and reports which are present, which are stale, and which
 * are missing. 5/5 falsifier tests cover the 3-arm status (ALL_OK /
 * STALE / MISSING) plus the per-module detail.
 */
@DisplayName("FabricContractsVerifier (SFW H1 Phase A.2)")
class FabricContractsVerifierTest {

    // ------------------------------------------------------------------
    // Falsifier 1: ALL_OK — all 6 modules at the expected version
    // ------------------------------------------------------------------
    @Test
    fun `ALL_OK when all 6 modules at the expected version`(@TempDir tmp: Path) {
        val repo = tmp.toFile()
        // Populate the 6 modules with the expected version.
        FABRIC_CONTRACT_MODULES.forEach { module ->
            installArtifact(repo, module, "0.5.0-R1-alpha.8")
        }
        val verifier = FabricContractsVerifier("0.5.0-R1-alpha.8", repoRoot = repo)
        val report = verifier.verify()
        assertEquals(FabricContractsVerifier.Status.ALL_OK, report.status)
        assertEquals(6, report.ok.size)
        assertEquals(0, report.stale.size)
        assertEquals(0, report.missing.size)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: MISSING — no modules at all
    // ------------------------------------------------------------------
    @Test
    fun `MISSING when no modules are installed`(@TempDir tmp: Path) {
        val repo = tmp.toFile()
        val verifier = FabricContractsVerifier("0.5.0-R1-alpha.8", repoRoot = repo)
        val report = verifier.verify()
        assertEquals(FabricContractsVerifier.Status.MISSING, report.status)
        assertEquals(0, report.ok.size)
        assertEquals(0, report.stale.size)
        assertEquals(6, report.missing.size)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: STALE — modules present at a different version
    // ------------------------------------------------------------------
    @Test
    fun `STALE when modules are at a different version`(@TempDir tmp: Path) {
        val repo = tmp.toFile()
        // All 6 at the old version.
        FABRIC_CONTRACT_MODULES.forEach { module ->
            installArtifact(repo, module, "0.5.0-R1-alpha.7")
        }
        val verifier = FabricContractsVerifier("0.5.0-R1-alpha.8", repoRoot = repo)
        val report = verifier.verify()
        assertEquals(FabricContractsVerifier.Status.STALE, report.status)
        assertEquals(0, report.ok.size)
        assertEquals(6, report.stale.size, "all 6 modules at alpha.7 should be stale when alpha.8 is expected")
        assertEquals(0, report.missing.size)
    }

    // ------------------------------------------------------------------
    // Falsifier 4: STALE — partial install (3 of 6 missing)
    // ------------------------------------------------------------------
    @Test
    fun `STALE when some modules are missing`(@TempDir tmp: Path) {
        val repo = tmp.toFile()
        // Install only 3 of 6.
        listOf("model", "observation", "run-control").forEach { module ->
            installArtifact(repo, module, "0.5.0-R1-alpha.8")
        }
        val verifier = FabricContractsVerifier("0.5.0-R1-alpha.8", repoRoot = repo)
        val report = verifier.verify()
        // Status is STALE: not all missing, not all OK.
        assertEquals(FabricContractsVerifier.Status.STALE, report.status)
        assertEquals(3, report.ok.size)
        assertEquals(3, report.missing.size)
        assertEquals(0, report.stale.size)
    }

    // ------------------------------------------------------------------
    // Falsifier 5: readPkfVersion — resolves the version from gradle.properties
    // ------------------------------------------------------------------
    @Test
    fun `readPkfVersion reads the version from gradle properties`(@TempDir tmp: Path) {
        val fabricDir = tmp.toFile()
        File(fabricDir, "gradle.properties").writeText(
            """
            pkfVersion=0.5.0-R1-alpha.8
            org.gradle.caching=true
            """.trimIndent()
        )
        val version = FabricContractsVerifier.readPkfVersion(fabricDir)
        assertEquals("0.5.0-R1-alpha.8", version)
    }

    // ------------------------------------------------------------------
    // Falsifier 6: readPkfVersion — returns null when gradle.properties is missing
    // ------------------------------------------------------------------
    @Test
    fun `readPkfVersion returns null when gradle properties missing`(@TempDir tmp: Path) {
        val fabricDir = tmp.toFile()  // empty
        val version = FabricContractsVerifier.readPkfVersion(fabricDir)
        assertNull(version)
    }

    // ------------------------------------------------------------------
    // Falsifier 7: report's per-module detail — pom and jar both required
    // ------------------------------------------------------------------
    @Test
    fun `module state requires both pom and jar`(@TempDir tmp: Path) {
        val repo = tmp.toFile()
        // Install only the pom, no jar.
        installArtifact(repo, "model", "0.5.0-R1-alpha.8", installJar = false)
        val verifier = FabricContractsVerifier("0.5.0-R1-alpha.8", repoRoot = repo)
        val report = verifier.verify()
        val modelState = report.modules.first { it.module == "model" }
        assertTrue(modelState.pomExists, "pom must be detected as present")
        assertFalse(modelState.jarExists, "jar must be detected as missing")
        assertFalse(modelState.isOk, "module without jar is NOT ok")
    }

    // --------------------------------------------------------------------
    // Test helpers
    // --------------------------------------------------------------------

    /**
     * Install a fake artifact (pom + optional jar) at the Maven local
     * layout for the given module + version.
     */
    private fun installArtifact(
        repo: File,
        module: String,
        version: String,
        installJar: Boolean = true,
    ) {
        val moduleDir = File(repo, "${FABRIC_GROUP.replace('.', '/')}/$module/$version")
        moduleDir.mkdirs()
        File(moduleDir, "$module-$version.pom").writeText("<?xml version=\"1.0\"?><project></project>")
        if (installJar) {
            File(moduleDir, "$module-$version.jar").writeText("fake jar")
        }
    }
}
