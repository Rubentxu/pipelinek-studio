package dev.pipelinek.studio.cert.sdk

import dev.pipelinek.studio.cert.domain.Failure
import dev.pipelinek.studio.cert.domain.Sha256
import java.nio.file.Path

/**
 * C1.2 (2026-10-12): the SDK resolution port.
 *
 * D-04 (Functional Core / Imperative Shell): the port
 * returns either an [SdkDistribution] or a structured
 * [Failure]. The shell (the certifier runner) translates
 * the failure into a verdict.
 */
fun interface SdkResolver {
    /**
     * Resolve the SDK from [zipPath] (the published
     * distribution ZIP). The resolver MUST verify:
     *  - the ZIP's own SHA-256 against [expectedZipSha]
     *  - each jar's SHA-256 against the published pins
     *  - the layout contains a launcher script + a
     *    canonical jar (the one with the Main-Class)
     *
     * On any mismatch the resolver returns a [Failure]
     * and does NOT partially resolve (no install
     * root, no classpath, no main class).
     */
    fun resolve(
        zipPath: Path,
        expectedZipSha: Sha256,
        expectedJarPins: Map<String, Sha256>,
    ): SdkResolution
}

sealed interface SdkResolution {
    data class Ok(val distribution: SdkDistribution) : SdkResolution
    data class Err(val failure: Failure, val detail: String) : SdkResolution
}
