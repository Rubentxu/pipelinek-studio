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
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertTrue

/**
 * R2.2 cierre (2026-10-11): the SDK version range declared in the
 * manifest header `X-PipelineK-Sdk-Version-Range` is the source of
 * truth for "plugin X is compatible with SDK Y" — not the naive
 * "plugin version < SDK version" rule. The header is parsed as a
 * Maven-style range:
 *
 *   `[lo, hi)`  — half-open interval; SDK version s is compatible
 *                  when lo <= s < hi
 *   `[lo, hi]`  — closed interval; lo <= s <= hi
 *   `1.0.0`     — single version; treated as exact match
 *
 * When the header is absent, the use case falls back to the naive
 * rule. Malformed headers do NOT refuse outright — the use case
 * falls back to the naive rule (so a hand-edited or buggy manifest
 * doesn't accidentally admit-or-refuse; the operator can re-admit
 * once the manifest is fixed).
 */
class VersionRangeR22Test {

    private val sdkVendor = "dev.rubentxu.pipeline.v2"
    // Synthetic SDK version chosen to fall clearly inside the
    // R2.1 fixture's `[0.0.0, 1.0.0)` range. The actual SDK pin is
    // 0.48.0-rc1 per `studio-infra-sdk/src/main/resources/sdk.lock.json`;
    // we use 0.50.0 here so the tests don't depend on the lockfile
    // pin changing.
    private val sdkVersion = "0.50.0"
    private val inspector = StaticManifestInspectorAdapter()
    private val fixedNow: Instant = Instant.parse("2026-10-11T19:00:00Z")
    private val actor: String = "ops:test"

    private class InMemoryLock : PluginAdmissionLockPort {
        private var entries: List<AdmittedPlugin> = emptyList()
        override fun load(): List<AdmittedPlugin> = entries
        override fun save(admitted: List<AdmittedPlugin>) { entries = admitted }
    }

    private fun useCase(
        lock: PluginAdmissionLockPort = InMemoryLock(),
        sdk: String = sdkVersion,
    ) = PluginAdmissionUseCase(
        inspector = inspector,
        lock = lock,
        sdkVersion = sdk,
        sdkVendor = sdkVendor,
        clock = { fixedNow },
    )

    private fun buildJar(
        dir: Path,
        jarName: String,
        version: String,
        sdkRange: String?,
    ): Path {
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

    @Test
    fun `R2_2 narrow range — SDK inside is compatible`(@TempDir tmp: Path) {
        // Range `[0.0.0, 1.0.0)` and SDK 0.50.0: SDK is inside the
        // half-open interval (0 <= 0.50.0 < 1) → Compatible.
        val jar = buildJar(tmp, "narrow-0.5.0.jar", "0.5.0", "[0.0.0, 1.0.0)")
        val state = useCase().inspect(jar)
        assertTrue(state is PluginAdmissionState.Compatible, "expected Compatible, got $state")
    }

    @Test
    fun `R2_2 narrow range — SDK at the upper bound is refused`(@TempDir tmp: Path) {
        // Range `[0.0.0, 1.0.0)` and SDK 1.0.0: 1.0.0 is at the
        // EXCLUSIVE upper bound → Refused. This is the R2.2
        // semantic change: pre-R2.2 the naive rule `0.5.0 < 1.0.0`
        // would have accepted a plugin with version 0.5.0 against
        // this range; post-R2.2 the SDK 1.0.0 is excluded by the
        // half-open form.
        val jar = buildJar(tmp, "any-1.0.0.jar", "1.0.0", "[0.0.0, 1.0.0)")
        val state = useCase(sdk = "1.0.0").inspect(jar)
        assertTrue(state is PluginAdmissionState.Refused, "expected Refused, got $state")
        assertTrue(
            state.reason.contains("1.0.0") && state.reason.contains("1.0.0"),
            "refusal must name both versions, got: ${state.reason}",
        )
    }

    @Test
    fun `R2_2 wide range — SDK 1_0_0 is compatible`(@TempDir tmp: Path) {
        // The KEY R2.2 behavior: a plugin declaring `[0.0.0, 2.0.0)`
        // and SDK 1.0.0 → SDK 1.0.0 < 2.0.0 → Compatible. Pre-R2.2
        // the naive rule would have refused any plugin version >=
        // SDK version, so this combination was not testable.
        val jar = buildJar(tmp, "wide-1.0.0.jar", "1.0.0", "[0.0.0, 2.0.0)")
        val state = useCase(sdk = "1.0.0").inspect(jar)
        assertTrue(state is PluginAdmissionState.Compatible, "expected Compatible, got $state")
    }

    @Test
    fun `R2_2 version above the range is refused`(@TempDir tmp: Path) {
        val jar = buildJar(tmp, "wide-3.0.0.jar", "3.0.0", "[0.0.0, 2.0.0)")
        val state = useCase(sdk = "3.0.0").inspect(jar)
        assertTrue(state is PluginAdmissionState.Refused, "expected Refused, got $state")
    }

    @Test
    fun `R2_2 missing header — falls back to naive plugin-version-less-than-SDK rule`(@TempDir tmp: Path) {
        val jar = buildJar(tmp, "no-header-0.1.0.jar", "0.1.0", sdkRange = null)
        val state = useCase().inspect(jar)
        // 0.1.0 < 0.50.0 (naive rule) -> Compatible
        assertTrue(state is PluginAdmissionState.Compatible, "expected Compatible, got $state")
    }

    @Test
    fun `R2_2 missing header — naive rule refuses version at-or-above SDK`(@TempDir tmp: Path) {
        val jar = buildJar(tmp, "no-header-2.0.0.jar", "2.0.0", sdkRange = null)
        val state = useCase().inspect(jar)
        assertTrue(state is PluginAdmissionState.Refused, "expected Refused, got $state")
    }

    @Test
    fun `R2_2 malformed header — falls back to naive rule (does not crash)`(@TempDir tmp: Path) {
        val jar = buildJar(tmp, "bad-header-0.1.0.jar", "0.1.0", sdkRange = "this is not a range")
        val state = useCase().inspect(jar)
        // Malformed -> fallback to naive -> 0.1.0 < 0.50.0 -> Compatible
        assertTrue(
            state is PluginAdmissionState.Compatible,
            "malformed range must fall back to naive rule, got $state",
        )
    }
}
