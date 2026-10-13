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
 * [PackageRegistry] (a `(SnapshotId) -> String?` function). The
 * tests inject a [FakeRunControl] + a `Map`-backed
 * [PackageRegistry] so a unit test does not bring up a gRPC socket.
 *
 * What these tests pin:
 *  - the **translation** (Studio RunRequest → Fabric StartRunRequest
 *    and back), including the H2 fields (`studioRunId`,
 *    `packageDigest`) and the distinct-`commandId` invariant;
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

    // ---- submit: happy path ---------------------------------------------------

    @Test
    fun `submit happy path is admitted with fabricRunId`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Accepted(FabricRunId("fabric-run-77"))
        }
        val adapter = FabricRunnerAdapter(fake, registryWith())

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
        val adapter = FabricRunnerAdapter(fake, registryWith(mapOf()))  // empty

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
        val adapter = FabricRunnerAdapter(fake, registryWith())

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
        val adapter = FabricRunnerAdapter(fake, registryWith())

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
        val adapter = FabricRunnerAdapter(fake, registryWith())

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
        val adapter = FabricRunnerAdapter(fake, registryWith())

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.AlreadyRunning::class.java, result)
    }

    @Test
    fun `submit with Rejection UnsupportedVersion is refused with version reason`() = runBlocking {
        val fake = FakeRunControl().apply {
            startAnswer = CommandResult.Rejected(Rejection.UnsupportedVersion(found = 1, supported = 2))
        }
        val adapter = FabricRunnerAdapter(fake, registryWith())

        val result = adapter.submit(req())

        assertInstanceOf(FabricSubmissionResult.Refused::class.java, result)
        val refused = result as FabricSubmissionResult.Refused
        assertEquals("unsupported version: client=1, server=2", refused.reason)
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
        val adapter = FabricRunnerAdapter(fake, registryWith())

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

    // ---- submit: distinct commandIds ------------------------------------------

    @Test
    fun `two submits mint distinct commandIds`() = runBlocking {
        val fake = FakeRunControl()
        val adapter = FabricRunnerAdapter(fake, registryWith())

        adapter.submit(req())
        adapter.submit(req())

        assertEquals(2, fake.startCalls.size)
        val first = fake.startCalls[0].commandId
        val second = fake.startCalls[1].commandId
        assertNotEquals(first, second, "commandIds must be distinct across submits")
    }

    // ---- submit: unknown exception is refused (defensive) ---------------------

    @Test
    fun `submit with unknown exception is refused with the exception class name`() = runBlocking {
        val fake = FakeRunControl().apply {
            startThrows = IllegalStateException("the universe broke")
        }
        val adapter = FabricRunnerAdapter(fake, registryWith())

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
        val adapter = FabricRunnerAdapter(fake, registryWith())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.CancelRequested::class.java, result)
        // The wire-side runId is the studio runId (H2 contract: Fabric
        // echoes studioRunId). H2.3 will replace this with a binding
        // lookup; for H2.2c we send the studio id as-is.
        assertEquals(1, fake.cancelCalls.size)
        assertEquals(runId.value, fake.cancelCalls.single().runId.value)
    }

    @Test
    fun `cancel with FabricTransportFailure is refused`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelThrows = FabricTransportFailure(
                callName = "RunControl.cancel",
                message = "deadline exceeded",
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.Refused::class.java, result)
        val refused = result as FabricCancellationResult.Refused
        assertTrue(refused.reason.contains("transport failure"), "got: ${refused.reason}")
    }

    @Test
    fun `cancel with Rejection NotFound is Unknown (Fabric does not know the run)`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Rejected(Rejection.NotFound(kind = "run", id = runId.value))
        }
        val adapter = FabricRunnerAdapter(fake, registryWith())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.Unknown::class.java, result)
        val unknown = result as FabricCancellationResult.Unknown
        assertTrue(unknown.reason.contains("not found: run"), "got: ${unknown.reason}")
    }

    @Test
    fun `cancel with Rejection Conflict terminal is AlreadyTerminal`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Rejected(
                Rejection.Conflict(kind = "cancel", id = runId.value, detail = "run is already in terminal state CANCELLED"),
            )
        }
        val adapter = FabricRunnerAdapter(fake, registryWith())

        val result = adapter.cancel(runId)

        assertInstanceOf(FabricCancellationResult.AlreadyTerminal::class.java, result)
        val terminal = result as FabricCancellationResult.AlreadyTerminal
        assertTrue(terminal.finalState.contains("CANCELLED"), "got: ${terminal.finalState}")
    }
}
