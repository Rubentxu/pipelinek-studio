package dev.pipelinek.studio.cert.sdk

import dev.pipelinek.studio.cert.domain.Sha256

/**
 * C1.1 (2026-10-12): the published SDK pins.
 *
 * The mandate fixes these SHAs. They are part of the
 * certifier wire contract: changing them is a release
 * change, not a code change.
 */
object ExpectedPins {
    /**
     * SHA-256 of the published distribution ZIP
     * `pipelinek-0.48.0.zip` from
     * https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.48.0-rc1
     */
    val PIPELINEK_0_48_0_ZIP: Sha256 = Sha256(
        "a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93",
    )

    /**
     * Per-jar SHAs of every jar inside the ZIP. The
     * resolver verifies each one against this map; an
     * unknown jar (no pin) is still accepted (we can't
     * know the future), but a MISMATCH is FAIL.
     *
     * The canonical Main-Class jar
     * (`pipeline-application-0.48.0.jar`) SHA matches
     * the one already pinned in
     * `studio-infra-sdk/src/main/resources/sdk.lock.json`.
     */
    val PIPELINEK_0_48_0_JARS: Map<String, Sha256> = mapOf(
        "pipeline-application-0.48.0.jar" to Sha256(
            "0811e7b60460e6b15a31cd3f312b1b5b3c36f8d1aa6dce7a7fd04193e362eace",
        ),
    )
}
