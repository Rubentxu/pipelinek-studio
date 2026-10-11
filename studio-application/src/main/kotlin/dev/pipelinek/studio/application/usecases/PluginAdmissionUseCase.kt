package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.AdmittedPlugin
import dev.pipelinek.studio.application.ports.PluginAdmissionLockPort
import dev.pipelinek.studio.application.ports.PluginAdmissionPort
import dev.pipelinek.studio.application.ports.PluginAdmissionState
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * R2.3 (2026-10-11): the use case that drives the plugin admission
 * lifecycle. The state machine lives here; the read port
 * ([PluginAdmissionPort]) and the write port
 * ([PluginAdmissionLockPort]) are passed as dependencies so the use
 * case is pure orchestration.
 *
 * Lifecycle (from `PluginAdmissionState` KDoc):
 *
 *   DISCOVERED     -> COMPATIBLE  on manifest match (vendor + version)
 *   DISCOVERED     -> REFUSED     on vendor mismatch with SDK
 *   COMPATIBLE     -> ADMITTED    on operator admit (persists sha256)
 *   COMPATIBLE     -> REFUSED     on operator refuse (no persistence)
 *   *              -> UNAVAILABLE on SDK/runtime failure to read JAR
 *
 * The use case enforces these transitions; calling `admit()` on a
 * JAR that is not in the COMPATIBLE state returns a `Refused` with
 * a reason explaining the pre-condition. Calling `refuse()` is
 * always safe (it's a no-op state-wise; the lock is not touched).
 *
 * Spec: docs/02-specifications/STEP_PLUGIN_LAB.md §Flujo +
 *       ROADMAP R2 item 3 ("Distinguir expresamente DISCOVERED,
 *       COMPATIBLE, ADMITTED, REFUSED y UNAVAILABLE") + R2 item 5
 *       ("Congelar el conjunto de plugins y su digest por run").
 */
class PluginAdmissionUseCase(
    private val inspector: StaticManifestInspectorLike,
    private val lock: PluginAdmissionLockPort,
    private val sdkVersion: String,
    private val sdkVendor: String = "dev.rubentxu.pipeline.v2",
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Re-inspects the JAR on disk and returns its current admission
     * state. The state is computed from the on-disk manifest +
     * the lock contents; it does NOT mutate the lock.
     *
     * Idempotent: calling inspect() twice in a row returns the
     * same state (barring disk/lock changes between calls).
     */
    @Suppress("TooGenericExceptionCaught")
    fun inspect(pluginJarPath: Path): PluginAdmissionState {
        val pluginJar = pluginJarPath.fileName.toString()
        if (!Files.isRegularFile(pluginJarPath)) {
            return PluginAdmissionState.Unavailable(
                pluginJar = pluginJar,
                reason = "plugin JAR not found on disk: $pluginJarPath",
            )
        }
        val manifest = try {
            inspector.inspect(pluginJarPath, expectedInternalVendor = sdkVendor)
        } catch (e: Exception) {
            log.warn("manifest inspection failed for {}: {}", pluginJar, e.message)
            return PluginAdmissionState.Unavailable(
                pluginJar = pluginJar,
                reason = "manifest inspection failed: ${e.message}",
            )
        }
        // Vendor check first — the cheapest invalidator.
        if (manifest.implementationVendor != null && manifest.implementationVendor != sdkVendor) {
            return PluginAdmissionState.Refused(
                pluginJar = pluginJar,
                reason = "vendor '${manifest.implementationVendor}' is not the SDK vendor '$sdkVendor'",
            )
        }
        // Compute the "compatible" predicate: vendor OK and version
        // inside the SDK range. We use the X-PipelineK-Sdk-Version-Range
        // header if present; otherwise the rule is "version < sdkVersion".
        val compatible = isVersionInRange(
            pluginVersion = manifest.implementationVersion,
            pluginRange = manifest.sdkVersionRange,
            sdkVersion = sdkVersion,
        )
        if (!compatible) {
            return PluginAdmissionState.Refused(
                pluginJar = pluginJar,
                reason = "version '${manifest.implementationVersion}' is outside the SDK $sdkVersion range",
            )
        }
        // Look up the lock.
        val admitted = lock.load().firstOrNull { it.pluginJar == pluginJar }
        return if (admitted != null) {
            if (admitted.sha256 != manifest.sha256) {
                // Lock entry exists but the on-disk bytes changed.
                // Per AAT-PLG-002, the admission is invalidated; the
                // operator must re-admit after reviewing the change.
                PluginAdmissionState.Refused(
                    pluginJar = pluginJar,
                    reason = "admission invalidated: on-disk sha256 ${manifest.sha256.take(12)}... " +
                        "does not match locked sha256 ${admitted.sha256.take(12)}...",
                )
            } else {
                PluginAdmissionState.Admitted(
                    pluginJar = pluginJar,
                    sha256 = admitted.sha256,
                    admittedAt = admitted.admittedAt,
                )
            }
        } else {
            PluginAdmissionState.Compatible(
                pluginJar = pluginJar,
                sdkVersion = sdkVersion,
                vendorMatch = true,
            )
        }
    }

    /**
     * Operator-initiated transition: COMPATIBLE -> ADMITTED.
     * Persists the on-disk sha256 in the lock. Idempotent: admitting
     * an already-admitted JAR with the same sha256 is a no-op.
     *
     * If the JAR is not in COMPATIBLE state, returns a Refused
     * with the reason (the admission is not granted). The lock is
     * NOT mutated in that case.
     */
    fun admit(pluginJarPath: Path, admittedBy: String): PluginAdmissionState {
        val current = inspect(pluginJarPath)
        return when (current) {
            is PluginAdmissionState.Compatible -> {
                val manifest = inspector.inspect(pluginJarPath, expectedInternalVendor = sdkVendor)
                val now = clock()
                val existing = lock.load()
                val updated = existing.filterNot { it.pluginJar == current.pluginJar } +
                    AdmittedPlugin(
                        pluginJar = current.pluginJar,
                        sha256 = manifest.sha256,
                        admittedAt = now,
                        admittedBy = admittedBy,
                    )
                lock.save(updated)
                PluginAdmissionState.Admitted(
                    pluginJar = current.pluginJar,
                    sha256 = manifest.sha256,
                    admittedAt = now,
                )
            }
            is PluginAdmissionState.Admitted -> current
            is PluginAdmissionState.Discovered,
            is PluginAdmissionState.Refused,
            is PluginAdmissionState.Unavailable -> PluginAdmissionState.Refused(
                pluginJar = current.pluginJar,
                reason = "cannot admit: current state is ${current::class.simpleName} (${(current as? PluginAdmissionState.Refused)?.reason ?: (current as? PluginAdmissionState.Unavailable)?.reason ?: "no reason"})",
            )
        }
    }

    /**
     * Operator-initiated refusal: COMPATIBLE -> REFUSED. Does NOT
     * mutate the lock (refusal is per-invocation; the next inspect()
     * on the same JAR will return Compatible again unless the lock
     * is consulted or the JAR is removed). Returns the refused state.
     */
    fun refuse(pluginJarPath: Path, reason: String): PluginAdmissionState {
        val current = inspect(pluginJarPath)
        return when (current) {
            is PluginAdmissionState.Compatible -> PluginAdmissionState.Refused(
                pluginJar = current.pluginJar,
                reason = "operator refused: $reason",
            )
            else -> current
        }
    }

    /**
     * Operator-initiated revocation: removes the plugin from the
     * lock. The next inspect() on the same JAR will return
     * Compatible (or Refused if version out of range).
     */
    fun revoke(pluginJarPath: Path): PluginAdmissionState {
        val pluginJar = pluginJarPath.fileName.toString()
        val existing = lock.load()
        if (existing.none { it.pluginJar == pluginJar }) {
            return inspect(pluginJarPath)
        }
        lock.save(existing.filterNot { it.pluginJar == pluginJar })
        return inspect(pluginJarPath)
    }

    /**
     * Conservative version-range check. When the plugin manifest
     * declares an SDK range via `X-PipelineK-Sdk-Version-Range`
     * (R2.2 cierre), that range is applied: a Maven-style range
     * like `[0.0.0, 1.0.0)` means "compatible with SDKs whose
     * version is in the half-open interval". When the header is
     * absent, the fallback is the R2.3 rule: "plugin version < SDK
     * version" (anything below the pinned SDK).
     */
    private fun isVersionInRange(
        pluginVersion: String?,
        pluginRange: String?,
        sdkVersion: String,
    ): Boolean {
        if (pluginVersion == null) return false
        // R2.2 (2026-10-11): if the manifest declares a parseable
        // SDK range, apply it. If the header is absent OR malformed,
        // fall through to the naive R2.3 rule. Refusing outright on
        // a malformed range would amplify a manifest typo into an
        // admission refusal; the operator can re-fix the manifest
        // without losing the lock entry.
        if (pluginRange != null) {
            val parsed = parseRange(pluginRange)
            if (parsed != null) {
                val sv = parseSemver(sdkVersion) ?: return false
                if (parsed.lower != null) {
                    if (compareSemver(sv, parsed.lower) < 0) return false
                }
                if (parsed.upper != null) {
                    if (parsed.upperInclusive) {
                        if (compareSemver(sv, parsed.upper) > 0) return false
                    } else {
                        if (compareSemver(sv, parsed.upper) >= 0) return false
                    }
                }
                return true
            }
            // Malformed range: fall through to the naive rule.
        }
        // Naive fallback: plugin version must be strictly less than
        // the SDK version. Works for plugins that do not declare a
        // range (R2.1 fixture uses `[0.0.0, 1.0.0)` as a default
        // marker; pre-R2.2 the naive rule was the only check).
        val pv = parseSemver(pluginVersion) ?: return false
        val sv = parseSemver(sdkVersion) ?: return false
        if (pv.first != sv.first) return pv.first < sv.first
        if (pv.second != sv.second) return pv.second < sv.second
        return pv.third < sv.third
    }

    /** -1 if a < b, 0 if equal, 1 if a > b. */
    private fun compareSemver(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        if (a.first != b.first) return if (a.first < b.first) -1 else 1
        if (a.second != b.second) return if (a.second < b.second) -1 else 1
        if (a.third != b.third) return if (a.third < b.third) -1 else 1
        return 0
    }

    private data class ParsedRange(val lower: Triple<Int, Int, Int>?, val upper: Triple<Int, Int, Int>?, val upperInclusive: Boolean)

    /**
     * Parse a Maven-style semver range. Accepted forms:
     *   - `[X, Y)` / `[X, Y]` — half-open / closed interval
     *   - `X` alone (treated as exact match, but we treat it as
     *     "lower = X, upper = X" which the comparison above handles
     *     as inclusive on both ends when `upperInclusive = true`)
     * Returns null on malformed input.
     */
    private fun parseRange(range: String): ParsedRange? {
        val trimmed = range.trim()
        if (trimmed.isEmpty()) return null
        // Single version: "1.0.0"
        if (!trimmed.startsWith("[")) {
            val v = parseSemver(trimmed) ?: return null
            return ParsedRange(lower = v, upper = v, upperInclusive = true)
        }
        // Interval: "[X, Y)" or "[X, Y]"
        val match = Regex("""^\[(\S+),\s*(\S+)([])])$""").matchEntire(trimmed) ?: return null
        val (lo, hi, closing) = match.destructured
        val lower = parseSemver(lo) ?: return null
        val upper = parseSemver(hi) ?: return null
        // The closing character is either `)` (Maven half-open
        // upper bound, exclusive) or `]` (closed upper bound,
        // inclusive). Maven's default is `)`.
        val upperInclusive = closing == "]"
        return ParsedRange(lower = lower, upper = upper, upperInclusive = upperInclusive)
    }

    private fun parseSemver(v: String): Triple<Int, Int, Int>? {
        // Accepts "0.1.0", "0.48.0-rc1", "1.0.0-rc.7" — strips
        // the suffix after `-` and parses the numeric part.
        val core = v.substringBefore("-")
        val parts = core.split(".")
        if (parts.size < 3) return null
        return try {
            Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        } catch (e: NumberFormatException) {
            null
        }
    }
}

/**
 * R2.3 (2026-10-11): structural port for the static manifest
 * inspector. Declared here (in the application layer) so the use
 * case can depend on it without pulling the infra SDK module.
 *
 * The interface is a strict subset of
 * `dev.pipelinek.studio.infra.sdk.StaticManifestInspector.inspect`
 * — only the parts the use case actually uses.
 */
interface StaticManifestInspectorLike {
    fun inspect(jarPath: Path, expectedInternalVendor: String): StaticPluginManifest
}

data class StaticPluginManifest(
    val jarPath: Path,
    val sha256: String,
    val implementationTitle: String?,
    val implementationVersion: String?,
    val implementationVendor: String?,
    /**
     * R2.2 cierre (2026-10-11): SDK version range declared in the
     * manifest header `X-PipelineK-Sdk-Version-Range`, surfaced
     * through [StaticManifestInspector]. `null` when the header is
     * absent — the use case falls back to the naive
     * "plugin version < SDK version" rule in that case.
     */
    val sdkVersionRange: String?,
)
