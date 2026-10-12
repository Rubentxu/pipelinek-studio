package dev.pipelinek.studio.cert.sdk

import dev.pipelinek.studio.cert.domain.Sha256

/**
 * C1.2 (2026-10-12): the typed SDK distribution.
 *
 * D-04 (Functional Core / Imperative Shell): this type is
 * the immutable value the runner + probe consume. It is
 * the *only* shape the rest of the system sees; the
 * resolution port returns one of these or an error.
 *
 * The same [SdkDistribution] instance is shared by the
 * probe and the runner, so the SHA they observe is the
 * same SHA (the canonical property of the C1 contract).
 */
data class SdkDistribution(
    val version: String,
    val zipSha256: Sha256,
    val installRoot: java.nio.file.Path,
    val mainClass: String,
    val launcherScript: java.nio.file.Path,
    val jars: List<SdkJar>,
) {
    /**
     * C1.2: the canonical jar that the launcher invokes
     * (the one whose Main-Class the studio hands to the
     * JVM). Its SHA is the wire identity the runner
     * reports.
     */
    val canonicalJar: SdkJar
        get() = jars.first { it.isMainClass }

    /**
     * C1.2: the full classpath the JVM must see to
     * load the canonical jar (and its sibling jars).
     * Colon-separated, absolute paths, ready for
     * `-cp`.
     */
    val classpath: String
        get() = jars.joinToString(java.io.File.pathSeparator) { it.path.toString() }
}

data class SdkJar(
    val name: String,
    val path: java.nio.file.Path,
    val sha256: Sha256,
    val sizeBytes: Long,
    val isMainClass: Boolean,
)
