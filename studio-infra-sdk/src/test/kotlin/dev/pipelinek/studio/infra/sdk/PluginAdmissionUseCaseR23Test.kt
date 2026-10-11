package dev.pipelinek.studio.infra.sdk

import dev.pipelinek.studio.application.ports.AdmittedPlugin
import dev.pipelinek.studio.application.ports.PluginAdmissionLockPort
import dev.pipelinek.studio.application.ports.PluginAdmissionState
import dev.pipelinek.studio.application.usecases.PluginAdmissionUseCase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R2.3 (2026-10-11): adversarial tests for the admission use case
 * state machine. The four scenarios pin the contract:
 *
 *   (1) DISCOVERED -> COMPATIBLE -> ADMITTED, persisted in the lock;
 *       re-loading the use case (or a new process) reads the lock
 *       and returns Admitted with the same sha256.
 *   (2) sha256 drift: if the on-disk JAR is replaced with a
 *       different byteset, the lock entry is invalidated and the
 *       use case returns Refused (NOT Admitted). The lock is NOT
 *       auto-rewritten.
 *   (3) vendor mismatch: a JAR with a non-SDK vendor returns
 *       Refused without touching the lock.
 *   (4) missing JAR: inspect() returns Unavailable, admit() returns
 *       Refused without touching the lock.
 *
 * The tests use the R2.1 external fixture (valid-plugin and a
 * synthetic tampered-version JAR built inline). They skip if the
 * fixture JARs are not built; build with:
 *   ./gradlew :examples:sdk-external-execution:assemble
 */
class PluginAdmissionUseCaseR23Test {

    private val sdkVendor = "dev.rubentxu.pipeline.v2"
    // R2.2 (2026-10-11): use a synthetic SDK version that clearly
    // falls inside the R2.1 fixture's `[0.0.0, 1.0.0)` range. The
    // previous value `1.0.0-rc.7` was at the EXCLUSIVE upper bound
    // and would be (correctly) refused by the new range check.
    private val sdkVersion = "0.50.0"
    private val inspector = StaticManifestInspectorAdapter()
    private val fixedNow: Instant = Instant.parse("2026-10-11T18:30:00Z")
    private val actor: String = "ops:test"

    private fun fixtureJar(name: String): Path? {
        var dir: Path? = Path.of(".").toAbsolutePath().normalize()
        while (dir != null && !Files.isRegularFile(dir.resolve("gradle.properties"))) {
            dir = dir.parent
        }
        requireNotNull(dir) { "could not walk up to find gradle.properties" }
        val p = dir.resolve("examples/sdk-external-execution/build/libs/$name")
        if (!Files.isRegularFile(p)) {
            println("[PluginAdmissionUseCaseR23Test] SKIP — $p not found. Build with: ./gradlew :examples:sdk-external-execution:assemble")
            return null
        }
        return p
    }

    /** In-memory lock for tests; never touches disk. */
    private class InMemoryLock : PluginAdmissionLockPort {
        private var entries: List<AdmittedPlugin> = emptyList()
        override fun load(): List<AdmittedPlugin> = entries
        override fun save(admitted: List<AdmittedPlugin>) {
            entries = admitted
        }
    }

    private fun useCaseWith(lock: PluginAdmissionLockPort, clock: () -> Instant = { fixedNow }) =
        PluginAdmissionUseCase(
            inspector = inspector,
            lock = lock,
            sdkVersion = sdkVersion,
            sdkVendor = sdkVendor,
            clock = clock,
        )

    @Test
    fun `R2_3 happy path — compatible to admitted persists sha256 and re-load returns admitted`() {
        val jar = fixtureJar("valid-plugin-0.1.0.jar") ?: return
        val lock = InMemoryLock()
        val uc = useCaseWith(lock)

        // Initial inspect: no lock entry yet -> Compatible.
        val initial = uc.inspect(jar)
        assertTrue(initial is PluginAdmissionState.Compatible, "expected Compatible, got $initial")

        // Operator admits.
        val afterAdmit = uc.admit(jar, actor)
        assertTrue(afterAdmit is PluginAdmissionState.Admitted, "expected Admitted, got $afterAdmit")
        val sha = afterAdmit.sha256
        assertTrue(sha.length == 64, "sha256 must be 64 hex chars, got $sha")

        // Lock persists exactly one entry.
        val persisted = lock.load()
        assertEquals(1, persisted.size, "lock must contain exactly one entry")
        assertEquals(jar.fileName.toString(), persisted.single().pluginJar)
        assertEquals(sha, persisted.single().sha256)
        assertEquals(actor, persisted.single().admittedBy)
        assertEquals(fixedNow, persisted.single().admittedAt)

        // A new use case (simulating a process restart) reads the
        // lock and reports the same Admitted state.
        val fresh = useCaseWith(lock)
        val reloaded = fresh.inspect(jar)
        assertTrue(reloaded is PluginAdmissionState.Admitted, "expected Admitted on reload, got $reloaded")
        assertEquals(sha, reloaded.sha256)
        assertEquals(fixedNow, reloaded.admittedAt, "admittedAt must survive a re-load")

        // Admitting again is idempotent.
        val again = uc.admit(jar, actor)
        assertTrue(again is PluginAdmissionState.Admitted)
        assertEquals(1, lock.load().size, "double admit must not duplicate the lock entry")
    }

    @Test
    fun `R2_3 sha256 drift — on-disk change invalidates the lock entry`() {
        val jar = fixtureJar("valid-plugin-0.1.0.jar") ?: return
        // Seed the lock with the on-disk sha256 (from the happy path).
        val firstAdmit = useCaseWith(InMemoryLock()).admit(jar, actor)
        assertTrue(firstAdmit is PluginAdmissionState.Admitted)
        val lock = InMemoryLock().apply {
            save(
                listOf(
                    AdmittedPlugin(
                        pluginJar = jar.fileName.toString(),
                        sha256 = firstAdmit.sha256,
                        admittedAt = firstAdmit.admittedAt,
                        admittedBy = actor,
                    ),
                ),
            )
        }
        val uc = useCaseWith(lock)

        // Mutate the JAR on disk (append a single byte) — sha256
        // changes, but the manifest fields stay the same. The
        // admission must be invalidated.
        Files.write(jar, Files.readAllBytes(jar) + byteArrayOf(0x42))
        try {
            val after = uc.inspect(jar)
            assertTrue(
                after is PluginAdmissionState.Refused,
                "expected Refused on sha256 drift, got $after",
            )
            assertTrue(
                after.reason.contains("admission invalidated"),
                "refusal must name 'admission invalidated', got: ${after.reason}",
            )
            // The lock entry is NOT auto-removed; the operator
            // must explicitly revoke (or re-admit) after reviewing
            // the drift.
            assertEquals(1, lock.load().size, "sha256 drift must not auto-mutate the lock")
        } finally {
            // Restore the JAR for downstream tests / re-runs.
            val original = Files.readAllBytes(jar).dropLast(1).toByteArray()
            Files.write(jar, original)
        }
    }

    @Test
    fun `R2_3 vendor mismatch — refuses without touching the lock`() {
        val dir = Files.createTempDirectory("r2_3_vendor_")
        val badVendorJar = dir.resolve("bad-vendor-0.1.0.jar")
        // Build a JAR with the wrong vendor using java.util.jar.
        val manifest = java.util.jar.Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Implementation-Title", "bad-vendor")
            mainAttributes.putValue("Implementation-Version", "0.1.0")
            mainAttributes.putValue("Implementation-Vendor", "com.attacker.evil")
        }
        java.util.jar.JarOutputStream(Files.newOutputStream(badVendorJar), manifest).use { /* empty */ }

        val lock = InMemoryLock()
        val uc = useCaseWith(lock)

        val state = uc.inspect(badVendorJar)
        assertTrue(state is PluginAdmissionState.Refused, "expected Refused, got $state")
        assertTrue(
            state.reason.contains("com.attacker.evil") && state.reason.contains(sdkVendor),
            "refusal must name both the bad vendor and the expected SDK vendor, got: ${state.reason}",
        )
        // admit() on a Refused state returns Refused (no transition).
        val afterAdmit = uc.admit(badVendorJar, actor)
        assertTrue(afterAdmit is PluginAdmissionState.Refused)
        assertEquals(0, lock.load().size, "vendor mismatch must NOT add to the lock")
    }

    @Test
    fun `R2_3 missing JAR — Unavailable, admit refused without lock mutation`() {
        val ghost = Path.of("/tmp/does-not-exist-1.0.0.jar")
        val lock = InMemoryLock()
        val uc = useCaseWith(lock)

        val state = uc.inspect(ghost)
        assertTrue(state is PluginAdmissionState.Unavailable, "expected Unavailable, got $state")
        assertTrue(state.reason.contains("not found"), "reason must name 'not found', got: ${state.reason}")

        val afterAdmit = uc.admit(ghost, actor)
        assertTrue(afterAdmit is PluginAdmissionState.Refused, "admit on Unavailable must return Refused, got $afterAdmit")
        assertEquals(0, lock.load().size, "admit on Unavailable must NOT mutate the lock")
    }

    @Test
    fun `R2_3 version out of SDK range — refuses without touching the lock`() {
        val jar = fixtureJar("incompatible-api-plugin-99.0.0.jar") ?: return
        val lock = InMemoryLock()
        val uc = useCaseWith(lock)

        val state = uc.inspect(jar)
        assertTrue(state is PluginAdmissionState.Refused, "expected Refused, got $state")
        assertTrue(
            state.reason.contains("99.0.0") && state.reason.contains(sdkVersion),
            "refusal must name both the bad version and the SDK version, got: ${state.reason}",
        )
        assertEquals(0, lock.load().size, "version out of range must NOT add to the lock")
    }

    @Test
    fun `R2_3 refuse — operator-initiated refusal does not persist`() {
        val jar = fixtureJar("valid-plugin-0.1.0.jar") ?: return
        val lock = InMemoryLock()
        val uc = useCaseWith(lock)

        // Compatible -> Refused (operator)
        val refused = uc.refuse(jar, "manual review")
        assertTrue(refused is PluginAdmissionState.Refused, "expected Refused, got $refused")
        assertTrue(refused.reason.contains("manual review"))
        // Lock is NOT mutated by refuse.
        assertEquals(0, lock.load().size, "refuse must NOT persist anything in the lock")
        // Next inspect() goes back to Compatible (refusal is per-invocation).
        val after = uc.inspect(jar)
        assertTrue(after is PluginAdmissionState.Compatible, "expected Compatible, got $after")
    }

    @Test
    fun `R2_3 revoke — operator-initiated revocation removes the lock entry`() {
        val jar = fixtureJar("valid-plugin-0.1.0.jar") ?: return
        val lock = InMemoryLock()
        val uc = useCaseWith(lock)

        uc.admit(jar, actor)
        assertEquals(1, lock.load().size)
        val afterRevoke = uc.revoke(jar)
        assertTrue(afterRevoke is PluginAdmissionState.Compatible, "expected Compatible after revoke, got $afterRevoke")
        assertEquals(0, lock.load().size, "revoke must remove the lock entry")
    }
}
