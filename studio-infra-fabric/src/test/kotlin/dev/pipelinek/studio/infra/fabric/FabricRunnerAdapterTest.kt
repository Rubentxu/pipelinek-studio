@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.ExecutionProfile
import dev.pipelinek.studio.application.ports.RunRequest
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.ScenarioId
import dev.pipelinek.studio.domain.SnapshotId
import io.jenkins.pipelinek.fabric.contracts.model.CommandResult
import io.jenkins.pipelinek.fabric.contracts.model.Rejection
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Falsifiers for [FabricRunnerAdapter] — the first port of the
 * H2.2c contract.
 *
 * The seam under test is a [RunControl] (Fabric's port) + a
 * [PackageRegistry] (a `(SnapshotId) -> String?` function) + the
 * durable [dev.pipelinek.studio.application.ports.FabricStartIntentStore]
 * that P0-03 made a precondition of every submit. The tests inject a
 * [FakeRunControl] + a `Map`-backed [PackageRegistry] + a
 * [FakeStartIntentStoreP0] so a unit test does not bring up a gRPC
 * socket or a database file. The real SQLite journal is exercised in
 * [RunBindingsH2StoreIntegrationTest] and [FabricSubmitIdentityP0Test].
 *
 * What these tests pin:
 *  - the **translation** (Studio RunRequest → Fabric StartRunRequest
 *    and back), including the H2 fields (`studioRunId`,
 *    `packageDigest`) and the P0-03 identity rule (same request → same
 *    persisted `commandId`, distinct Studio run → distinct id);
 *  - the **refusal paths** (the five Rejection arms the H2 contract
 *    admits, plus the adapter's own "no package" refusal);
 *  - the **transport-failure carve-out** (a FabricTransportFailure is
 *    a Refused, not Unknown — see FabricTransportFailure KDoc);
 *  - the **cancel path** (Fabric's Accepted maps to CancelRequested,
 *    the four Rejection arms map to the four FabricCancellationResult
 *    shapes).
 */
@DisplayName("FabricRunnerAdapter (SFW-H2.2c first port)")
class FabricRunnerAdapterTest {

    // ---- fixtures -------------------------------------------------------------

    private val runId = RunId("studio-run-1")
    private val snapshotId = SnapshotId("snap-1")
    private val scenarioId = ScenarioId("scen-1")
    private val digest = "sha256:" + "a".repeat(64)

    private fun req() = RunRequest(
        runId = runId,
        snapshotId = snapshotId,
        scenarioId = scenarioId,
        profile = ExecutionProfile.TrustedLocal,
    )

    private fun registryWith(snapshots: Map<SnapshotId, String> = mapOf(snapshotId to digest)) =
        PackageRegistry { id -> snapshots[id] }

    /**
     * The durable start-intent journal the adapter now requires
     * (P0-03). A fresh double per test body, so no test can inherit a
     * reservation from another; the double is test-only and is never a
     * production default.
     */
    private val intentStore = FakeStartIntentStoreP0()

    /**
     * A [BindingLookup] for the cancel tests: P0-04 made cancel resolve
     * the persisted Fabric identity, so a cancel falsifier must inject a
     * binding. The mapped value is deliberately unrelated to the Studio
     * id (Fabric assigns its own run ids).
     */
    private fun bindingTo(fabricRunId: FabricRunId = FabricRunId("fabric-run-77")) =
        BindingLookup { _ -> fabricRunId }

    // ---- submit: happy path ---------------------------------------------------

    @Test
    fun `submit happy path is admitted with fabricRunId`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Accepted(FabricRunId("fabric-run-77"))
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Admitted::class.java, result)
        val admitted = result as FabricSubmissionResult.Admitted
        assertEquals(runId, admitted.studioRunId)
        assertEquals(FabricRunId("fabric-run-77"), admitted.fabricRunId)
        // Translation correctness: the H2 fields were set.
        assertEquals(1, fake.startCalls.size)
        val sent = fake.startCalls.single()
        assertEquals(runId.value, sent.studioRunId)
        assertEquals(digest, sent.packageDigest)
        // displayName is derived so a Fabric-side log can name the run.
        assertEquals("studio:${runId.value}", sent.displayName)
        // pipelineKey is "package:<digest>" so the server's CRIC path
        // (catalog lookup) cannot accidentally fire on an H2 run.
        assertEquals("package:$digest", sent.pipelineKey)
    }

    // ---- submit: package-registry miss ----------------------------------------

    @Test
    fun `submit with unknown snapshot is refused and never calls RunControl`() = runBlocking {
        val fake = FakeRunControl()
        val adapter = FabricRunnerAdapter(
            fake,
            registryWith(mapOf()),  // empty
            startIntentStore = intentStore,
        )

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.contains("no package registered for snapshot"),
            "got: ${refused.reason}",
        )
        // The seam: the adapter refuses BEFORE calling RunControl.
        // A missing registration is a Studio-side bug; calling Fabric
        // would let the server produce a "package not found" answer
        // that the adapter has to translate — the contract is to
        // refuse at the seam, not at the server.
        assertEquals(0, fake.startCalls.size)
    }

    // ---- submit: Rejection arms ------------------------------------------------

    @Test
    fun `submit with Rejection NotFound is refused with not found reason`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(Rejection.NotFound(kind = "package", id = digest))
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertEquals("not found: package $digest", refused.reason)
    }

    @Test
    fun `submit with Rejection NotPermitted is refused with not permitted reason`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(
                Rejection.NotPermitted("worker profile 'gpu' is not in the cluster"),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertEquals(
            "not permitted: worker profile 'gpu' is not in the cluster",
            refused.reason,
        )
    }

    @Test
    fun `submit with Rejection Conflict is refused with conflict reason`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(
                Rejection.Conflict(kind = "start", id = "x", detail = "snapshot already in flight"),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertEquals("conflict: snapshot already in flight", refused.reason)
    }

    @Test
    fun `submit with Rejection Conflict-already-running is mapped to AlreadyRunning`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(
                Rejection.Conflict(
                    kind = "start",
                    id = "x",
                    detail = "a run for this studio run id is already running",
                ),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.AlreadyRunning::class.java, result)
    }

    @Test
    fun `submit with Rejection UnsupportedVersion is refused with version reason`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(Rejection.UnsupportedVersion(found = 1, supported = 2))
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertEquals("unsupported version: client=1, server=2", refused.reason)
    }

    // ---- submit: H2.2 package-side rejections ----------------------------------
    //
    // The H2.2 expansion of the Fabric `Rejection` sealed interface added
    // three package-side arms. Studio's `FabricRunnerAdapter.mapRejection`
    // already handles them (refused with a human-readable reason), but
    // there were no falsifier tests for the three arms. These three close
    // the gap on the Studio side; the proto/codec side is operator-driven
    // (the proto intentionally keeps the 4-arm `Rejection` and routes
    // package refusals through a separate `PackageRejection` family).

    @Test
    fun `submit with Rejection PackageTooLarge is refused with size detail`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(
                Rejection.PackageTooLarge(sizeBytes = 2_000_000_000L, maxBytes = 1_073_741_824L),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertEquals(
            "package too large: 2000000000 bytes > 1073741824 max",
            refused.reason,
        )
    }

    @Test
    fun `submit with Rejection PackageDigestMismatch is refused with both digests`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(
                Rejection.PackageDigestMismatch(
                    declared = "sha256:aaaa",
                    actual = "sha256:bbbb",
                ),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertEquals(
            "package digest mismatch: declared=sha256:aaaa, actual=sha256:bbbb",
            refused.reason,
        )
    }

    @Test
    fun `submit with Rejection AlreadyExistsWithDifferentBytes is refused with both digests`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(
                Rejection.AlreadyExistsWithDifferentBytes(
                    packageDigest = "sha256:cccc",
                    existingDigestOfBytes = "sha256:dddd",
                ),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertEquals(
            "package exists with different bytes: requested=sha256:cccc, " +
                "stored=sha256:dddd",
            refused.reason,
        )
    }

    // ---- submit: transport-failure carve-out ----------------------------------

    @Test
    fun `submit with FabricTransportFailure is refused, not Unknown`() = runBlocking {
        val fake = FakeRunControl().apply {
            startThrows = FabricTransportFailure(
                callName = "RunControl.start",
                message = "connection refused: no server answered",
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        // The seam: a transport failure is a caller-visible Refused,
        // not Unknown. See FabricTransportFailure KDoc.
        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.contains("transport failure"),
            "got: ${refused.reason}",
        )
        assertTrue(
            refused.reason.contains("RunControl.start"),
            "got: ${refused.reason}",
        )
    }

    // ---- submit: command identity ----------------------------------------------

    /**
     * P0-03 replaced the old expectation here. The pre-fix test asserted
     * that two submits of the *same* request minted **distinct**
     * `commandId`s — which was exactly the audited defect: Fabric
     * de-duplicates retries on `commandId` and nothing else, so a second
     * id for the same request is a second run after any timeout.
     *
     * The requirement now has two halves, and both are asserted:
     *  - the SAME request (same Studio run, same content) keeps the SAME
     *    persisted `commandId`, so a retry is recognisable;
     *  - a distinct Studio `RunId` still gets a distinct `commandId`, so
     *    two deliberate runs are two runs.
     */
    @Test
    fun `same request keeps its commandId and a distinct run mints a distinct one`() = runBlocking {
        val fake = FakeRunControl()
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        adapter.submit(req())
        adapter.submit(req())

        assertEquals(2, fake.startCalls.size)
        val first = fake.startCalls[0].commandId
        val second = fake.startCalls[1].commandId
        assertEquals(first, second, "the same request must reuse its persisted commandId")

        adapter.submit(
            RunRequest(
                runId = RunId("studio-run-2"),
                snapshotId = snapshotId,
                scenarioId = scenarioId,
                profile = ExecutionProfile.TrustedLocal,
            ),
        )
        val third = fake.startCalls[2].commandId
        assertNotEquals(first, third, "a distinct Studio run must not reuse another run's commandId")
    }

    // ---- submit: unknown exception is refused (defensive) ---------------------

    @Test
    fun `submit with unknown exception is refused with the exception class name`() = runBlocking {
        val fake = FakeRunControl().apply {
            startThrows = IllegalStateException("the universe broke")
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), startIntentStore = intentStore)

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertTrue(
            refused.reason.contains("IllegalStateException"),
            "got: ${refused.reason}",
        )
    }

    // ---- cancel: happy path ----------------------------------------------------

    @Test
    fun `cancel happy path is CancelRequested`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Accepted(FabricRunId("fabric-run-77"))
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), bindingTo())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.CancelRequested::class.java, result)
        // P0-04: the wire's runId is the *persisted Fabric* run id, never
        // the Studio id (see FabricCancelIdentityP0Test for the full
        // falsifier set).
        assertEquals(1, fake.cancelCalls.size)
        assertEquals("fabric-run-77", fake.cancelCalls.single().runId.value)
        assertNotEquals(runId.value, fake.cancelCalls.single().runId.value)
    }

    @Test
    fun `cancel with FabricTransportFailure is refused`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelThrows = FabricTransportFailure(
                callName = "RunControl.cancel",
                message = "deadline exceeded",
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), bindingTo())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.Refused::class.java, result)
        val refused = result as FabricCancellationResult.Refused
        assertTrue(refused.reason.contains("transport failure"), "got: ${refused.reason}")
    }

    @Test
    fun `cancel with Rejection NotFound is Unknown (Fabric does not know the run)`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Rejected(Rejection.NotFound(kind = "run", id = "fabric-run-77"))
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), bindingTo())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.Unknown::class.java, result)
        val unknown = result as FabricCancellationResult.Unknown
        assertTrue(unknown.reason.contains("not found: run"), "got: ${unknown.reason}")
    }

    @Test
    fun `cancel with Rejection Conflict terminal is AlreadyTerminal`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Rejected(
                Rejection.Conflict(kind = "cancel", id = "fabric-run-77", detail = "run is already in terminal state CANCELLED"),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), bindingTo())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.AlreadyTerminal::class.java, result)
        val terminal = result as FabricCancellationResult.AlreadyTerminal
        assertTrue(terminal.finalState.contains("CANCELLED"), "got: ${terminal.finalState}")
    }

    // ---- cancel: H2.2 package-side rejections -------------------------------
    //
    // The 3 H2.2 Rejection arms (PackageTooLarge, PackageDigestMismatch,
    // AlreadyExistsWithDifferentBytes) are package-side; a cancel call
    // should NEVER receive them. The adapter maps them to a `Refused`
    // with an "unexpected" message so an operator reading the logs sees
    // the anomaly. These 3 tests pin the behavior: if a future refactor
    // changes the mapping, the test fails. If B1 (operator-driven)
    // chooses option A and these arms become reachable on the cancel
    // wire, the tests document the current "unexpected" stance and
    // can be updated.

    @Test
    fun `cancel with Rejection PackageTooLarge is refused (unexpected package-side on cancel)`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Rejected(
                Rejection.PackageTooLarge(sizeBytes = 2_000_000_000L, maxBytes = 1_073_741_824L),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), bindingTo())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.Refused::class.java, result)
        val refused = result as FabricCancellationResult.Refused
        assertTrue(
            refused.reason.contains("package too large") && refused.reason.contains("unexpected"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `cancel with Rejection PackageDigestMismatch is refused (unexpected package-side on cancel)`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Rejected(
                Rejection.PackageDigestMismatch(declared = "sha256:aaaa", actual = "sha256:bbbb"),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), bindingTo())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.Refused::class.java, result)
        val refused = result as FabricCancellationResult.Refused
        assertTrue(
            refused.reason.contains("package digest mismatch") && refused.reason.contains("unexpected"),
            "got: ${refused.reason}",
        )
    }

    @Test
    fun `cancel with Rejection AlreadyExistsWithDifferentBytes is refused (unexpected package-side on cancel)`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Rejected(
                Rejection.AlreadyExistsWithDifferentBytes(
                    packageDigest = "sha256:cccc",
                    existingDigestOfBytes = "sha256:dddd",
                ),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith(), bindingTo())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.Refused::class.java, result)
        val refused = result as FabricCancellationResult.Refused
        assertTrue(
            refused.reason.contains("package exists with different bytes") && refused.reason.contains("unexpected"),
            "got: ${refused.reason}",
        )
    }
}
