@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import io.jenkins.pipelinek.fabric.contracts.model.CommandResult
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.runcontrol.CancelRunCommand
import io.jenkins.pipelinek.fabric.contracts.runcontrol.RunControl
import io.jenkins.pipelinek.fabric.contracts.runcontrol.StartRunRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * P0-03 / S4 falsifiers: [FabricRunnerAdapter.submit] reserves the
 * command identity **before** it sends, and then sends the reserved
 * value.
 *
 * ## The defect these tests pin
 *
 * The audited adapter minted `newStartRunId()` inside `submit`, so
 * every send carried a fresh identity. S4 is explicit: *"Persistir
 * StartRunId antes de enviar y reutilizarlo para la misma petición."*
 * A fresh-per-send id means a retry after a timeout is a **second run**
 * to Fabric (which de-duplicates on `commandId` and nothing else), and
 * a crash loses the identity outright.
 *
 * ## What each test asserts, and why all four parts are load-bearing
 *
 *  - the **wire identity** (`FakeRunControl.startCalls[*].commandId`) —
 *    the only thing Fabric de-duplicates on, so "same request, same id"
 *    has to be observed there and not inferred from a result arm;
 *  - the **call count** — a refusal must produce zero `RunControl.start`
 *    calls; a "did it dispatch?" question cannot be answered by a
 *    reason string;
 *  - the **journal state** (through the public [FabricStartIntentStore]
 *    port, never a private field) — a rejection must leave the stored
 *    record byte-for-byte what it was, and a reservation must be
 *    visible to the store *before* Fabric is contacted;
 *  - the **result arm and reason**, so a storage failure can never
 *    masquerade as a contradiction or as "no binding".
 *
 * The journal these tests inject is [FakeStartIntentStoreP0], a
 * test-only double. The durable semantics (atomic insert-if-absent,
 * the `run_bindings` precondition, a real file that survives a
 * recreated store) are proven separately against SQLite in
 * [RunBindingsH2StoreIntegrationTest] — a fake journal on its own would
 * prove nothing about durability.
 */
@DisplayName("P0-03 durable start-intent reservation on submit (S4)")
class FabricSubmitIdentityP0Test {

    private val runId = RunId("studio-run-1")
    private val otherRunId = RunId("studio-run-2")
    private val snapshotId = SnapshotId("snap-1")
    private val otherSnapshotId = SnapshotId("snap-2")
    private val scenarioId = ScenarioId("scen-1")
    private val otherScenarioId = ScenarioId("scen-2")
    private val digest = "sha256:" + "a".repeat(64)
    private val otherDigest = "sha256:" + "c".repeat(64)

    private fun req(
        runId: RunId = this.runId,
        snapshotId: SnapshotId = this.snapshotId,
        scenarioId: ScenarioId = this.scenarioId,
        profile: ExecutionProfile = ExecutionProfile.TrustedLocal,
    ) = RunRequest(
        runId = runId,
        snapshotId = snapshotId,
        scenarioId = scenarioId,
        profile = profile,
    )

    /** A registry that answers every snapshot it was built with. */
    private fun registry(vararg pairs: Pair<SnapshotId, String>) =
        PackageRegistry { id -> pairs.toMap()[id] }

    private fun defaultRegistry() = registry(snapshotId to digest, otherSnapshotId to digest)

    // ---- the invariant: reserve BEFORE send -----------------------------------

    /**
     * A [RunControl] that, at the moment `start` is called, reads the
     * journal through its public port and records what it saw. This is
     * how "reserved before dispatch" is proven: not by ordering two
     * statements in the test, but by observing the journal from *inside*
     * the remote call.
     */
    private class ProbeRunControl(
        private val store: FakeStartIntentStoreP0,
        private val probeRunId: RunId,
    ) : RunControl {
        val calls = mutableListOf<StartRunRequest>()

        /** What the journal held for [probeRunId] at the moment of the call. */
        val observedBeforeStart = mutableListOf<Pair<String, String>?>()

        override suspend fun start(request: StartRunRequest): CommandResult<FabricRunId> {
            calls.add(request)
            observedBeforeStart.add(
                store.lookup(probeRunId)?.let { it.commandId.value to it.requestFingerprint },
            )
            return CommandResult.Accepted(FabricRunId("fabric-run-77"))
        }

        override suspend fun cancel(command: CancelRunCommand): CommandResult<FabricRunId> =
            CommandResult.Accepted(FabricRunId("fabric-run-77"))
    }

    @Test
    fun `the reservation is already persisted when RunControl start is called`() = runBlocking {
        val store = FakeStartIntentStoreP0()
        val control = ProbeRunControl(store, runId)
        val adapter = FabricRunnerAdapter(
            control,
            defaultRegistry(),
            startIntentStore = store,
        )

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, result)
        val observed = control.observedBeforeStart.single()
        assertTrue(observed != null, "the journal was empty when Fabric was contacted")
        val sent = control.calls.single().commandId.value
        assertEquals(sent, observed!!.first, "the wire id must be the persisted id")
        assertEquals(1, store.reserveCalls.size, "exactly one reservation per submit")
    }

    // ---- the same request keeps its identity ---------------------------------

    @Test
    fun `a retry after a transport timeout reuses the persisted commandId`() = runBlocking {
        val store = FakeStartIntentStoreP0()
        val control = FakeRunControl().apply {
            startThrows = FabricTransportFailure(
                callName = "RunControl.start",
                message = "deadline exceeded",
            )
        }
        val adapter = FabricRunnerAdapter(control, defaultRegistry(), startIntentStore = store)

        val first = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, first)
        val refused = first as FabricSubmissionResult.Refused
        assertTrue(refused.reason.contains("transport failure"), "got: ${refused.reason}")
        // No automatic retry loop: one submit, one attempt. (Repeated
        // actual dispatch is the Fabric producer's P0-05 defect and is
        // NOT made safe by a stable commandId.)
        assertEquals(1, control.startCalls.size, "a single submit must not retry on its own")
        val persisted = store.lookup(runId)
        assertTrue(persisted != null, "the identity must survive the transport failure")
        assertEquals(1, store.size, "a failed send must not mint a second identity")

        // The retry (a new caller decision) reuses the stored identity.
        control.startThrows = null
        val second = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, second)
        assertEquals(2, control.startCalls.size)
        val firstId = control.startCalls[0].commandId.value
        val secondId = control.startCalls[1].commandId.value
        assertEquals(firstId, secondId, "the same request must keep the same commandId")
        assertEquals(persisted!!.commandId.value, firstId)
        assertEquals(1, store.size)
    }

    @Test
    fun `a recreated adapter over the same journal reuses the commandId`() = runBlocking {
        val store = FakeStartIntentStoreP0()
        val first = FakeRunControl()
        FabricRunnerAdapter(first, defaultRegistry(), startIntentStore = store).submit(req())

        // A new process would build a new adapter over the same durable
        // journal; the fake store instance stands in for that file.
        val second = FakeRunControl()
        val result = FabricRunnerAdapter(second, defaultRegistry(), startIntentStore = store)
            .submit(req())

        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, result)
        assertEquals(
            first.startCalls.single().commandId.value,
            second.startCalls.single().commandId.value,
        )
    }

    @Test
    fun `distinct explicit Studio runs keep distinct commandIds`() = runBlocking {
        val store = FakeStartIntentStoreP0()
        val control = FakeRunControl()
        val adapter = FabricRunnerAdapter(control, defaultRegistry(), startIntentStore = store)

        adapter.submit(req(runId = runId))
        adapter.submit(req(runId = otherRunId))

        assertEquals(2, control.startCalls.size)
        assertNotEquals(
            control.startCalls[0].commandId.value,
            control.startCalls[1].commandId.value,
            "two deliberate Studio runs are two runs, even with identical content",
        )
        assertEquals(2, store.size)
    }

    // ---- contradictions are refused before dispatch --------------------------

    @Test
    fun `a changed snapshot for the same run is refused and the stored intent is kept`() = runBlocking {
        assertContradictionRefused(
            contradictory = req(snapshotId = otherSnapshotId),
        )
    }

    @Test
    fun `a changed scenario for the same run is refused and the stored intent is kept`() = runBlocking {
        assertContradictionRefused(
            contradictory = req(scenarioId = otherScenarioId),
        )
    }

    @Test
    fun `a changed package digest for the same run is refused and the stored intent is kept`() = runBlocking {
        assertContradictionRefused(
            registryAfter = registry(snapshotId to otherDigest),
        )
    }

    @Test
    fun `a changed execution profile for the same run is refused and the stored intent is kept`() = runBlocking {
        assertContradictionRefused(
            contradictory = req(profile = ExecutionProfile.Untrusted),
        )
    }

    /**
     * The shared body of the contradiction falsifiers: reserve the
     * original request, then submit the contradictory one and assert
     * that (a) it is refused with the conflict reason, (b) Fabric was
     * called exactly once — for the original — and (c) the journal
     * still holds exactly the original identity and fingerprint.
     */
    private suspend fun assertContradictionRefused(
        contradictory: RunRequest? = null,
        registryAfter: PackageRegistry = defaultRegistry(),
    ) {
        val store = FakeStartIntentStoreP0()
        val control = FakeRunControl()
        var registry: PackageRegistry = defaultRegistry()
        val adapter = FabricRunnerAdapter(
            control,
            PackageRegistry { id -> registry.packageDigestFor(id) },
            startIntentStore = store,
        )

        val admitted = adapter.submit(req())
        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, admitted)
        val original = store.lookup(runId)
        assertTrue(original != null)
        val sentId = control.startCalls.single().commandId.value

        registry = registryAfter
        val result = adapter.submit(contradictory ?: req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.startsWith("start intent conflict: "),
            "got: ${refused.reason}",
        )
        assertEquals(1, control.startCalls.size, "a contradiction must not reach Fabric")
        assertEquals(original, store.lookup(runId), "the stored intent must be untouched")
        assertEquals(sentId, store.lookup(runId)!!.commandId.value)
        assertEquals(1, store.size)
    }

    // ---- the refusals that make a remote effect impossible -------------------

    @Test
    fun `a run without a persisted binding is refused without calling Fabric`() = runBlocking {
        val store = FakeStartIntentStoreP0(boundStudioRunIds = emptySet())
        val control = FakeRunControl()
        val adapter = FabricRunnerAdapter(control, defaultRegistry(), startIntentStore = store)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.contains("no persisted run binding for studio run ${runId.value}"),
            "got: ${refused.reason}",
        )
        assertEquals(0, control.startCalls.size, "an unbound run must not dispatch")
        assertNull(store.lookup(runId), "an unbound run must not mint an identity")
        assertEquals(0, store.size)
    }

    @Test
    fun `a failing reservation is refused without calling Fabric`() = runBlocking {
        val store = FakeStartIntentStoreP0()
        store.reserveThrows = SQLException("disk I/O error")
        val control = FakeRunControl()
        val adapter = FabricRunnerAdapter(control, defaultRegistry(), startIntentStore = store)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.startsWith("start intent reservation failed: "),
            "got: ${refused.reason}",
        )
        assertTrue(refused.reason.contains("SQLException"), "got: ${refused.reason}")
        assertEquals(0, control.startCalls.size, "a storage failure must not dispatch")
        assertNull(store.lookup(runId))
    }

    @Test
    fun `a missing start-intent store is refused without calling Fabric`() = runBlocking {
        // The default constructor argument is fail-closed, on purpose:
        // an adapter with no durable journal cannot dispatch, because an
        // unpersisted command identity is exactly the S4 defect.
        val control = FakeRunControl()
        val adapter = FabricRunnerAdapter(control, defaultRegistry())

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.contains("no durable start-intent store is wired"),
            "got: ${refused.reason}",
        )
        assertEquals(0, control.startCalls.size)
    }

    @Test
    fun `an unregistered snapshot is refused before the journal is touched`() = runBlocking {
        val store = FakeStartIntentStoreP0()
        val control = FakeRunControl()
        val adapter = FabricRunnerAdapter(
            control,
            registry(snapshotId to digest), // otherSnapshotId is unknown
            startIntentStore = store,
        )

        val result = adapter.submit(req(snapshotId = otherSnapshotId))

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        assertEquals(
            "no package registered for snapshot ${otherSnapshotId.value}",
            (result as FabricSubmissionResult.Refused).reason,
        )
        assertEquals(0, control.startCalls.size)
        assertEquals(0, store.reserveCalls.size, "the snapshot resolves before any identity work")
    }

    // ---- the fingerprint is unambiguous --------------------------------------

    @Test
    fun `field values that collide under a delimiter join are different fingerprints`() = runBlocking {
        // Under a `|`-join, ("a|b", "c") and ("a", "b|c") are the same
        // string; the requests are not the same request. The second one
        // must be a contradiction, not a silent retry.
        val store = FakeStartIntentStoreP0()
        val control = FakeRunControl()
        val collidingFirst = SnapshotId("a|b")
        val collidingSecond = SnapshotId("a")
        val adapter = FabricRunnerAdapter(
            control,
            registry(
                collidingFirst to digest,
                collidingSecond to digest,
            ),
            startIntentStore = store,
        )

        adapter.submit(req(snapshotId = collidingFirst, scenarioId = ScenarioId("c")))
        val result = adapter.submit(req(snapshotId = collidingSecond, scenarioId = ScenarioId("b|c")))

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        assertTrue(
            (result as FabricSubmissionResult.Refused).reason.startsWith("start intent conflict: "),
            "delimiter collision collapsed two distinct requests onto one identity: ${result.reason}",
        )
        assertEquals(1, control.startCalls.size)
    }

    @Test
    fun `values that contain the encoding punctuation still match themselves`() = runBlocking {
        // The mirror of the previous test: the encoding must not be so
        // strict that an ordinary id containing punctuation looks like a
        // contradiction on the second submit.
        val store = FakeStartIntentStoreP0()
        val control = FakeRunControl()
        val punctuated = SnapshotId("snap:1|x=2")
        val adapter = FabricRunnerAdapter(
            control,
            registry(punctuated to digest),
            startIntentStore = store,
        )

        adapter.submit(req(snapshotId = punctuated, scenarioId = ScenarioId("scen|1")))
        val result = adapter.submit(req(snapshotId = punctuated, scenarioId = ScenarioId("scen|1")))

        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, result)
        assertEquals(
            control.startCalls[0].commandId.value,
            control.startCalls[1].commandId.value,
        )
    }

    // ---- P0-04 is unaffected by the new constructor argument -----------------

    @Test
    fun `cancel still uses the persisted Fabric id when a journal is supplied`() = runBlocking {
        val store = FakeStartIntentStoreP0()
        val control = FakeRunControl()
        val adapter = FabricRunnerAdapter(
            control,
            defaultRegistry(),
            bindingLookup = BindingLookup { FabricRunId("fabric-run-77") },
            startIntentStore = store,
        )

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.CancelRequested::class.java, result)
        assertEquals("fabric-run-77", control.cancelCalls.single().runId.value)
        assertEquals(0, control.startCalls.size)
        assertEquals(0, store.reserveCalls.size, "a cancel must never reserve a start identity")
    }

    @Test
    fun `cancel still uses the persisted Fabric id when no journal is supplied`() = runBlocking {
        val control = FakeRunControl()
        val adapter = FabricRunnerAdapter(
            control,
            defaultRegistry(),
            bindingLookup = BindingLookup { FabricRunId("fabric-run-77") },
        )

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.CancelRequested::class.java, result)
        assertEquals("fabric-run-77", control.cancelCalls.single().runId.value)
    }
}
