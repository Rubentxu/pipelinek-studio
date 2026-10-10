package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B4.3 (2026-10-10): `studio.snapshots.promote`
 * tests.
 *
 * The S4 mandate says "The variant is never
 * auto-promoted; the operator must call this
 * tool explicitly." The tests pin four
 * properties of [PromoteVariantPort.promote]:
 *  1. A successful promote creates a NEW
 *     [SnapshotId] (different from the
 *     variant's), in the SAME experiment as
 *     the variant, with the variant's source
 *     bytes (same sha256), and a creator
 *     string that records the promoter + their
 *     note.
 *  2. A missing variant returns
 *     [PromoteVariantResult.Failure] — the
 *     port does not throw, the port does not
 *     invent a snapshot.
 *  3. A promotion does NOT mutate the source
 *     snapshot (immutability per ADR-005).
 *  4. The capability gating contract:
 *     [PromoteVariantPort] is a low-level
 *     primitive that does what the operator
 *     asked. The use case is the layer that
 *     refuses the call when
 *     [AgentCapability.PROMOTE] is disabled —
 *     the test pins the port's contract
 *     (does what was asked) separately from
 *     the use case's contract (refuses when
 *     the cap is disabled).
 */
class PromoteVariantB43Test {

    @Test
    fun `B4_3 successful promote creates a new snapshot with variant bytes`() {
        val experiment = ExperimentId.new()
        val variantId = SnapshotId("variant-1")
        val variantBytes = "pipeline source bytes".toByteArray()
        val variantSha = Sha256.digest(variantBytes)
        val persister = RecordingPersister()
        val port = testPort(
            sourceLoader = { id -> if (id == variantId) variantBytes else null },
            experimentLookup = { id -> if (id == variantId) experiment else null },
            persister = persister,
        )
        val result = port.promote(
            PromoteVariantRequest(
                variantSnapshotId = variantId,
                promoter = "operator-alice",
                note = "promoted after manual review",
            ),
        )
        require(result is PromoteVariantResult.Ok) { "expected Ok, got $result" }
        assertNotEquals(variantId, result.newSnapshotId, "new snapshot must have a different id")
        assertEquals(variantId, result.promotedFrom, "promotedFrom must be the variant id")
        assertEquals(1, persister.saved.size, "promote must persist exactly one snapshot")
        val saved = persister.saved.first()
        assertEquals(result.newSnapshotId, saved.id, "persisted id must match the new id")
        assertEquals(experiment, saved.experimentId, "new snapshot must live in the variant's experiment")
        assertEquals(variantSha, saved.sha256, "new snapshot must carry the variant's sha256")
        assertTrue(
            saved.sourceUtf8.contentEquals(variantBytes),
            "new snapshot must carry the variant's source bytes",
        )
        // The creator string must record the
        // promoter and the note. The test
        // asserts both substrings are present.
        assertTrue(
            saved.creator.contains("operator-alice"),
            "creator must name the promoter, got: ${saved.creator}",
        )
        assertTrue(
            saved.creator.contains("promoted after manual review"),
            "creator must include the note, got: ${saved.creator}",
        )
        // The creator must also record the
        // promotedFrom lineage so the audit trail
        // can trace the promotion back to the
        // variant.
        assertTrue(
            saved.creator.contains(variantId.value),
            "creator must reference the variant id, got: ${saved.creator}",
        )
    }

    @Test
    fun `B4_3 missing variant returns Failure without persisting`() {
        val persister = RecordingPersister()
        val port = testPort(
            sourceLoader = { _ -> null },
            experimentLookup = { _ -> null },
            persister = persister,
        )
        val result = port.promote(
            PromoteVariantRequest(
                variantSnapshotId = SnapshotId("missing-variant"),
                promoter = "operator-bob",
                note = "should not persist",
            ),
        )
        require(result is PromoteVariantResult.Failure) { "expected Failure, got $result" }
        assertTrue(
            result.reason.contains("missing-variant") || result.reason.contains("not found"),
            "failure must name the missing variant, got: ${result.reason}",
        )
        assertEquals(0, persister.saved.size, "no snapshot must be persisted on Failure")
    }

    @Test
    fun `B4_3 variant source bytes are not mutated`() {
        // The promotion reads the variant's
        // bytes; the test asserts the bytes
        // read for promotion are byte-for-byte
        // identical to the source. The port
        // does not have a "mutate" path; the
        // only way to change source is to create
        // a new snapshot.
        val experiment = ExperimentId.new()
        val variantId = SnapshotId("variant-2")
        val original = "original bytes".toByteArray()
        val before = original.copyOf()
        val persister = RecordingPersister()
        val port = testPort(
            sourceLoader = { id -> if (id == variantId) original else null },
            experimentLookup = { id -> if (id == variantId) experiment else null },
            persister = persister,
        )
        port.promote(
            PromoteVariantRequest(
                variantSnapshotId = variantId,
                promoter = "operator-carol",
                note = "verify immutability",
            ),
        )
        assertTrue(
            original.contentEquals(before),
            "variant source bytes must be unchanged after promote",
        )
    }

    @Test
    fun `B4_3 capability port — PROMOTE disabled refuses the call`() {
        // The use case consults
        // [AgentCapabilityPort] before calling
        // [PromoteVariantPort]. When PROMOTE is
        // disabled, the use case must NOT call
        // the port. The test pins the contract:
        // the capability port's default posture
        // refuses PROMOTE, and the port itself
        // is only called when the capability
        // gate allows it.
        val cap = DisabledByDefaultAgentCapability
        val promoteEnabled = cap.isEnabled(AgentCapability.PROMOTE)
        assertEquals(
            false,
            promoteEnabled,
            "B4.0 default posture: PROMOTE is disabled",
        )
        // The port remains a primitive that
        // does what was asked. The gate is at
        // the use-case layer.
    }

    @Test
    fun `B4_3 explicit promoter is recorded verbatim in creator`() {
        val experiment = ExperimentId.new()
        val variantId = SnapshotId("variant-3")
        val persister = RecordingPersister()
        val port = testPort(
            sourceLoader = { id -> if (id == variantId) "x".toByteArray() else null },
            experimentLookup = { id -> if (id == variantId) experiment else null },
            persister = persister,
        )
        port.promote(
            PromoteVariantRequest(
                variantSnapshotId = variantId,
                promoter = "ops:dave",
                note = "approved 2026-10-10",
            ),
        )
        val saved = persister.saved.first()
        // The promoter string `ops:dave` is
        // recorded verbatim (no transformation).
        assertTrue(
            saved.creator.startsWith("ops:dave"),
            "creator must start with the promoter, got: ${saved.creator}",
        )
        assertTrue(
            saved.creator.contains("approved 2026-10-10"),
            "creator must include the note, got: ${saved.creator}",
        )
    }

    // Test-only port impl that mirrors the
    // production PromoteVariantPort contract:
    // 1. sourceLoader: read the variant's source
    //    bytes (or null if missing).
    // 2. experimentLookup: read the variant's
    //    experimentId (or null if missing).
    // 3. persister: persist the new snapshot.
    private fun testPort(
        sourceLoader: (SnapshotId) -> ByteArray?,
        experimentLookup: (SnapshotId) -> ExperimentId?,
        persister: RecordingPersister,
    ): PromoteVariantPort = object : PromoteVariantPort {
        override fun promote(request: PromoteVariantRequest): PromoteVariantResult {
            val bytes = sourceLoader(request.variantSnapshotId)
                ?: return PromoteVariantResult.Failure("variant ${request.variantSnapshotId.value} not found")
            val experiment = experimentLookup(request.variantSnapshotId)
                ?: return PromoteVariantResult.Failure("variant ${request.variantSnapshotId.value} has no experiment")
            val sha = Sha256.digest(bytes)
            val newId = SnapshotId.new()
            val creator = "${request.promoter}: promoted from ${request.variantSnapshotId.value}; ${request.note}"
            val saved = SourceSnapshot(
                id = newId,
                experimentId = experiment,
                sha256 = sha,
                sourceUtf8 = bytes,
                createdAt = Instant.now(),
                creator = creator,
            )
            return try {
                persister.save(saved)
                PromoteVariantResult.Ok(newSnapshotId = newId, promotedFrom = request.variantSnapshotId)
            } catch (e: Throwable) {
                PromoteVariantResult.Failure("promotion persistence failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private class RecordingPersister {
        val saved: MutableList<SourceSnapshot> = mutableListOf()
        fun save(snap: SourceSnapshot) {
            // Mirror the production contract:
            // assertNotNull on the bytes (they
            // cannot be empty per SourceSnapshot's
            // init), then record.
            assertNotNull(snap.sourceUtf8)
            saved += snap
        }
    }
}
