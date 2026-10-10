package dev.pipelinek.studio.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * B1.4: deterministic id derivation. A [studioRunId] computed from
 * (key, digest) is stable across processes and restarts, so 50
 * concurrent identical requests collapse to a single row.
 *
 * Implementation note: the JDK's [UUID.nameUUIDFromBytes] is RFC
 * 4122 §4.3 (UUIDv3, MD5). The B1 mandate says "deterministic" —
 * the algorithm choice (v3 vs v5) is not load-bearing as long as
 * it is collision-resistant. We use v3 (MD5) because it is in the
 * JDK stdlib and avoids pulling Bouncy Castle in for one helper.
 * The namespace is fixed (Studio-internal) so collisions across
 * other systems are not a concern.
 */
object DeterministicId {
    /**
     * Studio-internal UUID namespace. Picked once at design time;
     * never regenerated (changing it would invalidate every
     * existing in-flight reservation). Stored as a UUID literal
     * so the value travels in source rather than a config file.
     */
    private val STUDIO_NS: UUID = UUID.fromString("8c1f5b3a-3a4d-4b5e-8e2f-1a2b3c4d5e6f")

    /**
     * Derive a deterministic [RunId] from (idempotency-key,
     * request-digest). The output is the same across processes,
     * restarts, and concurrent callers — that is the property the
     * B1.4 test relies on.
     */
    fun studioRunId(key: String, digest: Sha256): RunId {
        val payload = ("run:" + key + ":" + digest.value).toByteArray(StandardCharsets.UTF_8)
        val derived = UUID.nameUUIDFromBytes(payload)
        // Mix the namespace into the high bits so a different
        // namespace (e.g. snapshot-id derivation later) cannot
        // collide with a run-id by accident.
        val mixed = mix(STUDIO_NS, derived)
        return RunId(mixed.toString())
    }

    private fun mix(ns: UUID, v: UUID): UUID {
        // XOR the high 64 bits of the namespace into the high 64
        // bits of the derived value. This keeps the result RFC-4122
        // compliant (version 3 marker preserved) while ensuring
        // that the same payload under a different namespace yields
        // a different id.
        val msb = v.mostSignificantBits xor (ns.mostSignificantBits and 0xFFFFFFFFL.inv())
        return UUID(msb, v.leastSignificantBits)
    }
}

/**
 * B1.3: the admission outcome the StartRunUseCase can return. The
 * mandate requires a sealed ADT with these four cases. The current
 * [RunHandleStatus] has only Live / Terminal / Unknown and conflates
 * "process lost, outcome possibly reconcilable" with "no authority to
 * speak". This ADT separates them.
 */
sealed interface ReservationOutcome {
    /**
     * The row is committed in `idempotency` and `run_bindings` with
     * the deterministic `studio_run_id`; the runner is being asked
     * to submit. No upstream process is necessarily running yet.
     */
    data class Reserved(val studioRunId: RunId) : ReservationOutcome

    /**
     * The runner has accepted the request and the upstream process
     * is alive. [upstreamRunId] is whatever the SDK reported; the
     * studio-side `studio_run_id` is the public identity.
     */
    data class Admitted(val studioRunId: RunId, val upstreamRunId: String, val controlRoot: java.nio.file.Path) : ReservationOutcome

    /**
     * The runner refused the request (e.g. sandbox profile not
     * available, manifest rejected). The row exists in
     * `run_bindings` with `state = REFUSED`. The studio must
     * NOT invent `FAILED` here.
     */
    data class Refused(val studioRunId: RunId, val reason: String) : ReservationOutcome

    /**
     * A previous attempt reserved the same (key, digest) but the
     * upstream process state is no longer observable: it may have
     * died before producing a terminal, or the control-root is
     * gone. The studio surfaces this as a clear refusal with a
     * reason; the caller decides whether to retry (new key) or
     * reconcile.
     */
    data class RecoveryRequired(val studioRunId: RunId, val reason: String) : ReservationOutcome
}

/**
 * B1.5: the read-side observation of a run. Replaces the
 * three-case [RunHandleStatus] (which conflated recovery and
 * unavailable). The mandate requires the four cases below. Per
 * DATA_MODEL_AND_PERSISTENCE.md: "El supervisor no inventa FAILED
 * al perder el proceso si PipelineK puede reconciliar."
 */
sealed interface RunObservation {
    data class Live(val upstreamId: String, val pid: Long?) : RunObservation
    data class Terminal(val outcome: VerifiedOutcome) : RunObservation
    data class RecoveryRequired(val reason: String) : RunObservation
    data class Unavailable(val reason: String) : RunObservation
}

/**
 * B1.6: distinguishes "asserted" from "executed and verified" for
 * the claim_status surface. The pre-B1.6 [ClaimStatusDetector]
 * only inspected the source bytes for `assert/shouldEqual/expect`
 * patterns — that test detected assertions but never tested
 * whether they were executed. VerifiedOutcome requires the
 * terminal event from run.db to be present.
 */
sealed interface VerifiedOutcome {
    data object DeclaredPending : VerifiedOutcome
    data class Executed(val terminal: String, val evidence: RunEvidence) : VerifiedOutcome
}

data class RunEvidence(
    val runDbPath: String,
    val terminalSequence: Long,
    val terminalKind: String,
    val terminalSha256: String,
)

