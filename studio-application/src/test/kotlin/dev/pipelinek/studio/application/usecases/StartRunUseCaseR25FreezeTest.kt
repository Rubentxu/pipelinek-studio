package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.AdmittedPlugin
import dev.pipelinek.studio.application.ports.IdempotencyLookupResult
import dev.pipelinek.studio.application.ports.IdempotencyPort
import dev.pipelinek.studio.application.ports.IdempotencyRecordResult
import dev.pipelinek.studio.application.ports.PipelineRunnerPort
import dev.pipelinek.studio.application.ports.PluginAdmissionLockPort
import dev.pipelinek.studio.application.ports.RuntimeDescriptor
import dev.pipelinek.studio.application.ports.RuntimeDescriptorResult
import dev.pipelinek.studio.application.ports.PipelineKRuntimePort
import dev.pipelinek.studio.application.ports.ScenarioRunnerPort
import dev.pipelinek.studio.application.ports.SnapshotSnapshot
import dev.pipelinek.studio.application.ports.StudioMetadataPort
import dev.pipelinek.studio.application.ports.ScenarioSnapshot
import dev.pipelinek.studio.application.ports.RunBindingSnapshot
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.application.ports.SubmissionResult
import dev.pipelinek.studio.domain.Experiment
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.FrozenPluginDigest
import dev.pipelinek.studio.domain.IdempotencyKey
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunBinding
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.RunState
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SnapshotId
import dev.pipelinek.studio.domain.SourceScenario
import dev.pipelinek.studio.domain.SourceSnapshot
import dev.pipelinek.studio.domain.ReservationOutcome
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R2.5 (2026-10-11): tests for the per-run plugin freeze captured
 * by [StartRunUseCase] from [PluginAdmissionLockPort] at run start.
 *
 *  1.  Freeze is captured at run start, sorted by `jarFilename`.
 *  2.  `pluginSetDigest` is sha256 of the freeze when non-empty,
 *      and falls back to the SDK pluginSdkVersion when empty.
 *  3.  Null lock → empty freeze (no admission wired).
 *  4.  Empty lock → empty freeze.
 *  5.  Replay (same idempotency key + digest) does not re-capture
 *      the freeze; the persisted row is the one from the first call.
 *  6.  Revoke mid-run (after startRun) does not change the freeze
 *      of the in-progress binding.
 *  7.  Mid-run new admit does not appear in the freeze of an
 *      already-running binding.
 */
class StartRunUseCaseR25FreezeTest {

    private val fixedNow: Instant = Instant.parse("2026-10-11T20:30:00Z")
    private val earlier: Instant = Instant.parse("2026-10-01T00:00:00Z")
    private val sdkVersion = "1.0.0-rc.11"
    private val sdkPluginSdkVersion = "0.50.0"

    private val snap = SnapshotId.new()
    private val sc = ScenarioId.new()
    private val fid = ExperimentId.new()

    private fun runtimeAvailable(): PipelineKRuntimePort = object : PipelineKRuntimePort {
        override fun describe(): RuntimeDescriptorResult =
            RuntimeDescriptorResult.Available(
                RuntimeDescriptor(
                    version = sdkVersion,
                    artifactDigest = "sha-pin",
                    pluginSdkVersion = sdkPluginSdkVersion,
                    capabilities = setOf("compile", "eval", "durable"),
                ),
            )
    }

    private class RecordingMetadata(
        val snapshot: SnapshotId,
        val experimentId: ExperimentId,
    ) : StudioMetadataPort {
        val persistedBindings = mutableListOf<RunBinding>()
        override fun findExperiment(id: ExperimentId) = null
        override fun findSnapshot(id: SnapshotId): SnapshotSnapshot? =
            if (id == snapshot) SnapshotSnapshot(id, experimentId, "sha", Instant.now()) else null
        override fun findScenario(id: ScenarioId): ScenarioSnapshot? = null
        override fun findRun(id: RunId) = null
        override fun listRuns(state: RunState?, limit: Int, offset: Int) = emptyList<RunBindingSnapshot>()
        override fun listScenarios(limit: Int, offset: Int) = emptyList<ScenarioSnapshot>()
        override fun saveScenario(scenario: SourceScenario) { /* noop */ }
        override fun saveExperiment(experiment: Experiment) { /* noop */ }
        override fun saveSnapshot(snap: SourceSnapshot) { /* noop */ }
        override fun loadSnapshotSource(id: SnapshotId): ByteArray? = null
        override fun persistRunBinding(binding: RunBinding) { persistedBindings += binding }
    }

    private class StubRunner : PipelineRunnerPort {
        var submits: Int = 0
        override fun submit(req: RunRequest): SubmissionResult {
            submits++
            return SubmissionResult.Admitted("upstream-1", java.nio.file.Path.of("/tmp/ctrl"))
        }
        override fun cancel(runId: RunId) = dev.pipelinek.studio.application.ports.CancellationResult.Unknown("n/a")
        override fun status(runId: RunId) = dev.pipelinek.studio.application.ports.RunHandleStatus.Unknown("n/a")
    }

    private class RecordingIdempotency : IdempotencyPort {
        private data class Row(val opId: OperationId, val responseRef: String?, val storedDigest: String)
        private val rows = mutableMapOf<String, Row>()
        private fun k(scope: String, key: String) = "$scope|$key"
        override fun lookup(scope: String, key: String, requestDigestSha256: String): IdempotencyLookupResult {
            val recorded = rows[k(scope, key)] ?: return IdempotencyLookupResult.NotFound
            return if (recorded.storedDigest == requestDigestSha256) {
                IdempotencyLookupResult.Match(recorded.opId, recorded.responseRef)
            } else {
                IdempotencyLookupResult.Conflict("mismatch")
            }
        }
        override fun record(
            scope: String,
            key: String,
            operationId: OperationId,
            requestDigestSha256: String,
            responseRef: String?,
        ): IdempotencyRecordResult {
            val k2 = k(scope, key)
            return if (rows.containsKey(k2)) {
                IdempotencyRecordResult.AlreadyExists(rows[k2]!!.opId)
            } else {
                rows[k2] = Row(operationId, responseRef, requestDigestSha256)
                IdempotencyRecordResult.Recorded
            }
        }
    }

    /** In-memory lock for the freeze tests. Mutations after the run start
     *  are visible to the test thread (the run start captured a SNAPSHOT,
     *  so a subsequent mutation must NOT affect the binding). */
    private class InMemoryLock(initial: List<AdmittedPlugin> = emptyList()) : PluginAdmissionLockPort {
        private val state: MutableList<AdmittedPlugin> = initial.toMutableList()
        override fun load(): List<AdmittedPlugin> = state.toList()
        override fun save(admitted: List<AdmittedPlugin>) { state.clear(); state.addAll(admitted) }
    }

    private fun admitted(jar: String, sha: String, by: String, at: Instant = earlier) =
        AdmittedPlugin(pluginJar = jar, sha256 = sha, admittedAt = at, admittedBy = by)

    @Test
    fun `R2_5 freeze captures the lock sorted by jarFilename`() {
        // Three admitted plugins inserted in NON-sorted order.
        val lock = InMemoryLock(
            listOf(
                admitted("zeta-0.1.0.jar", "zzz", "ops:alice"),
                admitted("alpha-0.1.0.jar", "aaa", "ops:bob"),
                admitted("mid-0.2.0.jar", "mmm", "ops:carol"),
            ),
        )
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), meta, lock) { fixedNow }
        val out = uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        // The runner stub returns Admitted, so the outcome is Admitted
        // (both Reserved and Admitted prove the binding was persisted).
        assertTrue(out is ReservationOutcome.Reserved || out is ReservationOutcome.Admitted, "expected Reserved/Admitted, got $out")
        val binding = meta.persistedBindings.single()
        // Sorted: alpha, mid, zeta
        assertEquals(listOf("alpha-0.1.0.jar", "mid-0.2.0.jar", "zeta-0.1.0.jar"), binding.frozenPluginDigests.map { it.jarFilename })
        // frozenAt is the clock (fixedNow)
        assertTrue(binding.frozenPluginDigests.all { it.frozenAt == fixedNow }, "frozenAt must come from the clock")
        // admittedAt is preserved from the lock
        assertEquals(earlier, binding.frozenPluginDigests[0].admittedAt)
        // admittedBy is preserved
        assertEquals("ops:bob", binding.frozenPluginDigests[0].admittedBy)
    }

    @Test
    fun `R2_5 pluginSetDigest is sha256 of the freeze when non-empty`() {
        val lock = InMemoryLock(
            listOf(
                admitted("alpha.jar", "aaa", "ops:alice"),
                admitted("beta.jar", "bbb", "ops:alice"),
            ),
        )
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), meta, lock) { fixedNow }
        uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        val binding = meta.persistedBindings.single()
        // sha256 of ("aaa\0bbb\0") (sorted) — 64 hex chars
        assertEquals(64, binding.pluginSetDigest.length, "non-empty freeze must produce a sha256 hex digest")
        // Determinism: same lock → same digest
        val meta2 = RecordingMetadata(snap, fid)
        val uc2 = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), meta2, lock) { fixedNow }
        uc2.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("y".toByteArray()))
        assertEquals(binding.pluginSetDigest, meta2.persistedBindings.single().pluginSetDigest)
        // Distinct from the SDK fallback
        assertNotEquals(sdkPluginSdkVersion, binding.pluginSetDigest)
    }

    @Test
    fun `R2_5 pluginSetDigest falls back to SDK version when lock is empty`() {
        val lock = InMemoryLock(emptyList())
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), meta, lock) { fixedNow }
        uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        val binding = meta.persistedBindings.single()
        assertEquals(sdkPluginSdkVersion, binding.pluginSetDigest, "empty freeze must fall back to SDK version")
        assertEquals(emptyList(), binding.frozenPluginDigests)
    }

    @Test
    fun `R2_5 pluginSetDigest falls back to SDK version when lock is null`() {
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), meta, null) { fixedNow }
        uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        val binding = meta.persistedBindings.single()
        assertEquals(sdkPluginSdkVersion, binding.pluginSetDigest)
        assertEquals(emptyList(), binding.frozenPluginDigests)
    }

    @Test
    fun `R2_5 idempotency replay does not re-capture the freeze`() {
        val lock = InMemoryLock(
            listOf(admitted("alpha.jar", "aaa", "ops:alice")),
        )
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), meta, lock) { fixedNow }
        val key = IdempotencyKey.new()
        val digest = Sha256.digest("x".toByteArray())
        // First call
        uc.execute(snap, sc, key, digest)
        val firstBinding = meta.persistedBindings.single()
        assertEquals(1, firstBinding.frozenPluginDigests.size)
        // Mutate the lock AFTER the first call
        lock.save(listOf(admitted("alpha.jar", "different-sha", "ops:mallory")))
        // Replay (same key + digest)
        uc.execute(snap, sc, key, digest)
        // The second call did NOT re-persist (idempotency hit)
        assertEquals(1, meta.persistedBindings.size, "replay must not re-persist")
        // The first binding still has the original freeze
        assertEquals("aaa", meta.persistedBindings[0].frozenPluginDigests.single().sha256)
    }

    @Test
    fun `R2_5 revoke mid-run does not mutate the in-progress binding`() {
        val lock = InMemoryLock(
            listOf(
                admitted("alpha.jar", "aaa", "ops:alice"),
                admitted("beta.jar", "bbb", "ops:alice"),
            ),
        )
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), meta, lock) { fixedNow }
        uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        val bindingBeforeRevoke = meta.persistedBindings.single()
        assertEquals(2, bindingBeforeRevoke.frozenPluginDigests.size)
        // Operator revokes `alpha` (lock mutation)
        lock.save(listOf(admitted("beta.jar", "bbb", "ops:alice")))
        // The binding is unchanged: `INSERT ... ON CONFLICT DO NOTHING`
        // means the freeze is bound to the row, not to the lock.
        val bindingAfterRevoke = meta.persistedBindings.single()
        assertEquals(2, bindingAfterRevoke.frozenPluginDigests.size, "revoke must not retroactively shrink the freeze")
        assertEquals(setOf("alpha.jar", "beta.jar"), bindingAfterRevoke.frozenPluginDigests.map { it.jarFilename }.toSet())
    }

    @Test
    fun `R2_5 new admit mid-run does not appear in the in-progress freeze`() {
        val lock = InMemoryLock(
            listOf(admitted("alpha.jar", "aaa", "ops:alice")),
        )
        val meta = RecordingMetadata(snap, fid)
        val uc = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), meta, lock) { fixedNow }
        uc.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        assertEquals(1, meta.persistedBindings.single().frozenPluginDigests.size)
        // Operator admits a new plugin mid-run
        lock.save(
            listOf(
                admitted("alpha.jar", "aaa", "ops:alice"),
                admitted("newly-admitted.jar", "nnn", "ops:bob"),
            ),
        )
        // The persisted binding was already written; the freeze is
        // the snapshot from run start, not the current lock state.
        assertEquals(1, meta.persistedBindings.single().frozenPluginDigests.size, "mid-run admit must not appear")
        assertEquals("alpha.jar", meta.persistedBindings.single().frozenPluginDigests.single().jarFilename)
    }

    @Test
    fun `R2_5 freeze encoding is deterministic across two runs with the same lock`() {
        val lockA = InMemoryLock(listOf(admitted("alpha.jar", "aaa", "ops:alice"), admitted("beta.jar", "bbb", "ops:bob")))
        val lockB = InMemoryLock(listOf(admitted("alpha.jar", "aaa", "ops:alice"), admitted("beta.jar", "bbb", "ops:bob")))
        val metaA = RecordingMetadata(snap, fid)
        val metaB = RecordingMetadata(snap, fid)
        val ucA = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), metaA, lockA) { fixedNow }
        val ucB = StartRunUseCase(StubRunner(), RecordingIdempotency(), runtimeAvailable(), metaB, lockB) { fixedNow }
        ucA.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("x".toByteArray()))
        ucB.execute(snap, sc, IdempotencyKey.new(), Sha256.digest("y".toByteArray()))
        val ba = metaA.persistedBindings.single()
        val bb = metaB.persistedBindings.single()
        // Same lock contents → same pluginSetDigest (the digest of the freeze).
        assertEquals(ba.pluginSetDigest, bb.pluginSetDigest, "digest must be deterministic for the same freeze")
        // The freeze list itself is the same shape (same jars, same shas).
        assertEquals(ba.frozenPluginDigests.map { it.sha256 }, bb.frozenPluginDigests.map { it.sha256 })
    }
}
