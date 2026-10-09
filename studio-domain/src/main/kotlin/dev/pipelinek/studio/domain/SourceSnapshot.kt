package dev.pipelinek.studio.domain

import java.time.Instant

/**
 * Immutable source snapshot per docs/01-architecture/DATA_MODEL_AND_PERSISTENCE.md.
 * Any change to the source bytes produces a new SnapshotId; sha256 is the content authority.
 */
data class SourceSnapshot(
    val id: SnapshotId,
    val experimentId: ExperimentId,
    val sha256: Sha256,
    val sourceUtf8: ByteArray,
    val createdAt: Instant,
    val creator: String,
) {
    init {
        require(sourceUtf8.isNotEmpty()) { "snapshot cannot be empty source bytes" }
        require(creator.isNotBlank()) { "creator cannot be blank" }
    }

    /** validate sha256 matches sourceUtf8 — caller responsibility on creation. */
    fun isContentValid(): Boolean = Sha256.digest(sourceUtf8).value == sha256.value

    override fun equals(other: Any?): Boolean = other is SourceSnapshot && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

@JvmInline
value class Sha256(val value: String) {
    init { require(value.length == 64) { "sha256 must be 64 hex chars" } }
    companion object {
        fun digest(bytes: ByteArray): Sha256 {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hex = md.digest(bytes).joinToString("") { "%02x".format(it) }
            return Sha256(hex)
        }
    }
}
