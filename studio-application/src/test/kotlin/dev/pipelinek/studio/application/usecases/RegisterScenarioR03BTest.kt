package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.RunBindingSnapshot
import dev.pipelinek.studio.application.ports.ScenarioSnapshot
import dev.pipelinek.studio.application.ports.StudionMetadataNoop
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SourceScenario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * R0.3.1 (2026-10-11) — adversarial tests for the RegisterScenarioUseCase
 * and the noop metadata port's saveScenario contract.
 *
 * The mandate R0.3.1 is "persistir escenarios auténticos; eliminar
 * `experiment_id` como sustituto de `scenario_id`." These tests pin
 * the invariants the use case enforces:
 *
 *  1. **A fresh id** mints a scenario at version 1 by default.
 *  2. **Re-registering the same id at the same version** is a no-op
 *     (idempotent: the same SourceScenario survives).
 *  3. **Re-registering with version+1** succeeds.
 *  4. **Re-registering with a non-monotonic version** returns
 *     `VersionConflict` (no implicit jump; the operator must ask
 *     for the next version explicitly or auto-bump by omitting it).
 *  5. **The noop metadata port** drops writes silently (CLI smoke
 *     contract — the port never invents persistence it cannot honour).
 *  6. **The list scenarios** path returns the registered rows
 *     (noop variant returns an empty list).
 *
 * No SDK subprocess required. Assertable in this environment.
 */
class RegisterScenarioR03BTest {

    @Test
    fun `register at version 1 when no prior scenario exists with this id`() {
        val store = HashMap<String, SourceScenario>()
        val port = RecordingMetadataNoop(store)
        val uc = RegisterScenarioUseCase(port)
        val id = ScenarioId.new()
        when (val result = uc.execute(explicitId = id)) {
            is RegisterScenarioUseCase.Result.Registered -> {
                assertEquals(id, result.scenario.id)
                assertEquals(1, result.scenario.version)
                assertEquals(1, store.size)
            }
            is RegisterScenarioUseCase.Result.VersionConflict ->
                error("unexpected VersionConflict: ${result.existingVersion} vs ${result.attemptedVersion}")
        }
    }

    @Test
    fun `re-registering the same id at version 1 is a no-op (idempotent R031)`() {
        val store = HashMap<String, SourceScenario>()
        val port = RecordingMetadataNoop(store)
        val uc = RegisterScenarioUseCase(port)
        val id = ScenarioId.new()
        uc.execute(explicitId = id)
        // Second register without explicit version: the use case
        // interprets that as "find existing, do nothing", which we
        // surfaced as a return-Registered with the existing row.
        val result = uc.execute(explicitId = id)
        check(result is RegisterScenarioUseCase.Result.Registered) {
            "expected Registered, got $result"
        }
        assertEquals(1, result.scenario.version)
        // Idempotent: still a single row in the store.
        assertEquals(1, store.size)
    }

    @Test
    fun `registering with version+1 succeeds monotonically`() {
        val store = HashMap<String, SourceScenario>()
        val port = RecordingMetadataNoop(store)
        val uc = RegisterScenarioUseCase(port)
        val id = ScenarioId.new()
        uc.execute(explicitId = id, requestedVersion = 2)
        // v1 was skipped by the operator's explicit version=2; the
        // use case does NOT auto-create v1, it just persists v2.
        val result = uc.execute(explicitId = id, requestedVersion = 3)
        check(result is RegisterScenarioUseCase.Result.Registered) {
            "expected Registered v3, got $result"
        }
        assertEquals(3, result.scenario.version)
    }

    @Test
    fun `registering with a non-monotonic version returns VersionConflict`() {
        val store = HashMap<String, SourceScenario>()
        val port = RecordingMetadataNoop(store)
        val uc = RegisterScenarioUseCase(port)
        val id = ScenarioId.new()
        uc.execute(explicitId = id, requestedVersion = 5)
        val result = uc.execute(explicitId = id, requestedVersion = 3)
        check(result is RegisterScenarioUseCase.Result.VersionConflict) {
            "expected VersionConflict, got $result"
        }
        assertEquals(5, result.existingVersion)
        assertEquals(3, result.attemptedVersion)
        // Existing row untouched.
        assertEquals(5, store[id.value]!!.version)
    }

    @Test
    fun `noop metadata port drops saveScenario silently`() {
        val uc = RegisterScenarioUseCase(StudionMetadataNoop)
        // Without a backing store, the use case still produces a
        // Registered outcome — the port is the one that decides
        // whether persistence is honoured. The noop drop is silent.
        val result = uc.execute(explicitId = ScenarioId.new(), pluginLockDigest = null)
        check(result is RegisterScenarioUseCase.Result.Registered) {
            "expected Registered, got $result"
        }
        assertNotNull(result.scenario.id)
    }

    @Test
    fun `list scenarios returns registered rows in newest-first order`() {
        // LinkedHashMap preserves insertion order so the recording port
        // returns rows in the order they were registered — matching the
        // production StudioMetadataRepository's `ORDER BY created_at DESC`
        // contract on a single-clock test scenario.
        val store = LinkedHashMap<String, SourceScenario>()
        val port = RecordingMetadataNoop(store)
        val uc = RegisterScenarioUseCase(port)
        val list = ListScenariosUseCase(port)
        // Three different scenarios.
        val idA = ScenarioId.new().also { uc.execute(explicitId = it) }
        val idB = ScenarioId.new().also { uc.execute(explicitId = it) }
        val idC = ScenarioId.new().also { uc.execute(explicitId = it) }
        // The RecordingMetadataNoop returns store.values in insertion
        // order; the production StudioMetadataRepository impl orders
        // by created_at DESC. The port contract is "Newest first",
        // which both implementations honour.
        val all = list.execute()
        assertEquals(3, all.size)
        // Newest first means idC (registered last) is at the front.
        assertEquals(idC, all[0].id)
    }
}

/**
 * In-memory Recording metadata port that doubles as a Spy. Captures
 * writes (asserted in tests) and replays reads from the same map.
 * Mirrors the SQLite-backed StudioMetadataRepository contract.
 */
private class RecordingMetadataNoop(
    private val store: MutableMap<String, SourceScenario>,
) : dev.pipelinek.studio.application.ports.StudioMetadataPort {
    private val autoId = AtomicReference<Long>(0)

    override fun findExperiment(id: dev.pipelinek.studio.domain.ExperimentId) = null
    override fun findSnapshot(id: dev.pipelinek.studio.domain.SnapshotId) = null
    override fun findRun(id: RunId) = null
    override fun listRuns(
        state: dev.pipelinek.studio.domain.RunState?,
        limit: Int,
        offset: Int,
    ): List<RunBindingSnapshot> = emptyList()

    override fun listScenarios(limit: Int, offset: Int): List<ScenarioSnapshot> =
        // Mirror the production StudioMetadataRepository's
        // `ORDER BY created_at DESC` contract: newest first. The
        // LinkedHashMap preserves insertion order, so reverse the
        // values iterator to honour the wire-shape promise that the
        // list endpoint makes ("newest first").
        store.values.reversed().let { reversed ->
            if (offset >= reversed.size) emptyList()
            else reversed.drop(offset).take(limit)
                .map { ScenarioSnapshot(id = it.id, version = it.version, pluginLockDigest = it.pluginLockDigest) }
        }

    override fun saveExperiment(experiment: dev.pipelinek.studio.domain.Experiment) { /* drop */ }
    override fun saveSnapshot(snap: dev.pipelinek.studio.domain.SourceSnapshot) { /* drop */ }
    override fun loadSnapshotSource(id: dev.pipelinek.studio.domain.SnapshotId): ByteArray? = null
    override fun persistRunBinding(binding: dev.pipelinek.studio.domain.RunBinding) { /* drop */ }

    override fun saveScenario(scenario: SourceScenario) {
        // Idempotent semantics: a re-register with the same id
        // overwrites — the production impl uses ON CONFLICT DO
        // NOTHING. The use case's monotonicity invariants already
        // prevent a regression scenario; this recording variant
        // is for assertions, not for the SQL contract.
        store[scenario.id.value] = scenario
    }

    override fun findScenario(id: ScenarioId): ScenarioSnapshot? =
        store[id.value]?.let { SourceScenarioToSnapshot.toSnapshot(it) }
}

/**
 * Tiny helper to project a write-side [SourceScenario] to the
 * read-side [ScenarioSnapshot].
 */
private object SourceScenarioToSnapshot {
    fun toSnapshot(s: SourceScenario): ScenarioSnapshot = ScenarioSnapshot(
        id = s.id,
        version = s.version,
        pluginLockDigest = s.pluginLockDigest,
    )
}
