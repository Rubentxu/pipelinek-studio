package dev.pipelinek.studio.cert.domain

import kotlinx.serialization.Serializable

/**
 * C0.4 (2026-10-12): the identity domain.
 *
 * D-04 (Functional Core / Imperative Shell): these types
 * are value classes that wrap raw strings; they enforce
 * the format at the boundary (adapters) and never appear
 * downstream as raw strings.
 *
 * The wire contract is the underlying string; the type
 * is for compile-time guarantees.
 */
@Serializable
@JvmInline
value class Sha256(val value: String) {
    init {
        require(value.length == 64) { "SHA-256 must be 64 hex chars, got ${value.length}" }
        require(value.all { it in 'a'..'f' || it in '0'..'9' }) { "SHA-256 must be lowercase hex" }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SemVer(val value: String) {
    init {
        // Tolerant regex: digits.digits.digits + optional -suffix
        require(SEMVER.matches(value)) { "SemVer invalid: $value" }
    }

    override fun toString(): String = value

    companion object {
        private val SEMVER = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.\-]+)?(\+[0-9A-Za-z.\-]+)?$""")
    }
}

@Serializable
data class GitCommit(val sha: String) {
    init {
        require(sha.length == 40 && sha.all { it in 'a'..'f' || it in '0'..'9' }) {
            "GitCommit must be 40-char hex sha, got: $sha"
        }
    }
}

@Serializable
@JvmInline
value class GitTag(val value: String) {
    init {
        require(value.isNotBlank()) { "GitTag cannot be blank" }
    }
}

@Serializable
@JvmInline
value class CaseId(val value: String) {
    init {
        require(CASE_ID.matches(value)) { "CaseId must match ${CASE_ID.pattern}, got: $value" }
    }

    override fun toString(): String = value

    companion object {
        // Catalog format: UAT-001 or AAT-<GROUP>-NNN.
        // Group is UPPERCASE letters (ARCH, DUR, OBS, CPP,
        // AN, UX, SEC, REL, PLG, ABI, MCP, API).
        // We allow AAT-<GROUP>-<NUMBER> or UAT-<NUMBER>.
        private val CASE_ID = Regex(
            """^(?:UAT-\d{1,4}|(?:UAT-)?AAT-[A-Z]{2,6}-\d{1,4})$""",
        )
    }
}

@Serializable
data class ReleaseIdentity(
    val tag: GitTag,
    val commit: GitCommit,
    val studioVersion: SemVer,
)

@Serializable
data class ArtifactDigest(
    val name: String,
    val sha256: Sha256,
    val size: Long,
)
