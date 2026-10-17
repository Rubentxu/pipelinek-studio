@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.SnapshotId
import io.jenkins.pipelinek.fabric.contracts.model.CommandResult
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P0-04 / S5 falsifiers: [FabricRunnerAdapter.cancel] resolves the
 * **persisted** Fabric identity through the injected [BindingLookup]
 * and cancels through that identity only.
 *
 * The defect these tests pin (audit P0-04, spec S5): the pre-fix
 * adapter cast the Studio [RunId] into Fabric's
 * `io.jenkins.pipelinek.fabric.contracts.model.RunId` and cancelled
 * with it. Fabric run ids are server-assigned and unrelated to the
 * Studio id, so that request either missed the run or — worse —
 * addressed a different one. S5 is explicit: *"Resolver el
 * FabricRunId persistido y cancelar exclusivamente mediante ese
 * identificador."* A missing identity means Unknown/refusal and **no**
 * remote cancel; the Studio id is never promoted into a Fabric id.
 *
 * ## What is asserted, and why each assertion is load-bearing
 *
 * Each test asserts the four observable parts of one cancel call:
 *
 *  - the **call count** (`RunControl.cancel` is called exactly once,
 *    or not at all — a "did it dispatch?" question cannot be answered
 *    by a reason string);
 *  - the **exact identity on the wire** (`CancelRunCommand.runId` is
 *    the persisted value byte for byte, and provably not the Studio
 *    id);
 *  - the **result arm** (compared by identity, so a later
 *    `AlreadyTerminal`/`Refused` synthesised on an accepted cancel
 *    fails the test);
 *  - the **exact reason string** for the non-dispatch arms, so a
 *    storage failure cannot masquerade as "Fabric does not know the
 *    run".
 *
 * The "no persistent mutation" half is asserted through the lookup
 * itself: it is a read-only `fun interface`, and the recording
 * implementation below pins that the adapter reads exactly once and
 * leaves the stored bindings untouched. [FakeRunControl.startCalls]
 * being empty pins that a cancel never reaches for the write path.
 */
@DisplayName("P0-04 cancel identity through the persisted binding (S5)")
class FabricCancelIdentityP0Test {

    private val studioRunId = RunId("studio-run-1")

    /** Deliberately not derived from [studioRunId]: the whole point of the defect. */
    private val storedFabricRunId = FabricRunId("fabric-run-77")

    private val packageRegistry = PackageRegistry { _: SnapshotId -> "sha256:" + "a".repeat(64) }

    /**
     * A [BindingLookup] that records its reads, can throw, and exposes
     * its stored map so a test can prove the cancel path mutated
     * nothing.
     */
    private class RecordingBindingLookup(
        private val stored: MutableMap<RunId, FabricRunId?> = mutableMapOf(),
        private val onLookup: (() -> Unit)? = null,
    ) : BindingLookup {
        var lookups: Int = 0
            private set

        /** Snapshot of the stored bindings, for before/after comparison. */
        fun bindings(): Map<RunId, FabricRunId?> = stored.toMap()

        override fun fabricRunIdFor(studioRunId: RunId): FabricRunId? {
            lookups++
            onLookup?.invoke()
            return stored[studioRunId]
        }
    }

    // ---- the fix's positive case ---------------------------------------------

    @Test
    fun `cancel forwards the stored FabricRunId exactly and never the studioRunId`() = runBlocking {
        val fake = FakeRunControl().apply {
            cancelAnswer = CommandResult.Accepted(storedFabricRunId)
        }
        val lookup = RecordingBindingLookup(mutableMapOf(studioRunId to storedFabricRunId))
        val bindingsBefore = lookup.bindings()
        val adapter = FabricRunnerAdapter(fake, packageRegistry, bindingLookup = lookup)

        val result = adapter.cancel(studioRunId)

        // Intent only: an accepted cancel is CancelRequested, nothing else.
        assertSame(FabricCancellationResult.CancelRequested, result)

        // Exactly one remote call, and it carried the persisted id.
        assertEquals(1, fake.cancelCalls.size)
        val sent = fake.cancelCalls.single()
        assertEquals(storedFabricRunId.value, sent.runId.value)
        assertNotEquals(studioRunId.value, sent.runId.value)
        assertEquals(null, sent.reason)

        // One read, no write path touched, stored bindings unchanged.
        assertEquals(1, lookup.lookups)
        assertEquals(0, fake.startCalls.size)
        assertEquals(bindingsBefore, lookup.bindings())
    }

    @Test
    fun `cancel with an accepted reply never synthesises a terminal state`() = runBlocking {
        val fake = FakeRunControl().apply {
            // Fabric echoes a different id than the persisted one: the
            // adapter must still report "cancel requested", not invent
            // a terminal state from the echoed value.
            cancelAnswer = CommandResult.Accepted(FabricRunId("fabric-run-99"))
        }
        val lookup = RecordingBindingLookup(mutableMapOf(studioRunId to storedFabricRunId))
        val adapter = FabricRunnerAdapter(fake, packageRegistry, bindingLookup = lookup)

        val result = adapter.cancel(studioRunId)

        assertSame(FabricCancellationResult.CancelRequested, result)
        assertEquals(1, fake.cancelCalls.size)
        assertEquals(storedFabricRunId.value, fake.cancelCalls.single().runId.value)
    }

    // ---- missing identity: no remote effect ----------------------------------

    @Test
    fun `cancel without a binding is Unknown and never calls RunControl`() = runBlocking {
        val fake = FakeRunControl()
        val lookup = RecordingBindingLookup()  // empty store
        val adapter = FabricRunnerAdapter(fake, packageRegistry, bindingLookup = lookup)

        val result = adapter.cancel(studioRunId)

        assertEquals(
            FabricCancellationResult.Unknown(
                "no binding for studioRunId studio-run-1 (the run was never submitted, " +
                    "or it was bound to a different Fabric server)",
            ),
            result,
        )
        // The seam: no identity, no remote cancel.
        assertEquals(0, fake.cancelCalls.size)
        assertEquals(0, fake.startCalls.size)
        assertEquals(1, lookup.lookups)
        assertEquals(emptyMap<RunId, FabricRunId?>(), lookup.bindings())
    }

    @Test
    fun `cancel with a blank stored FabricRunId is Unknown and never calls RunControl`() = runBlocking {
        val fake = FakeRunControl()
        val lookup = RecordingBindingLookup(mutableMapOf(studioRunId to FabricRunId("")))
        val bindingsBefore = lookup.bindings()
        val adapter = FabricRunnerAdapter(fake, packageRegistry, bindingLookup = lookup)

        val result = adapter.cancel(studioRunId)

        assertEquals(
            FabricCancellationResult.Unknown(
                "blank fabricRunId bound to studioRunId studio-run-1 " +
                    "(the persisted binding cannot address a Fabric run)",
            ),
            result,
        )
        assertEquals(0, fake.cancelCalls.size)
        assertEquals(0, fake.startCalls.size)
        assertEquals(1, lookup.lookups)
        assertEquals(bindingsBefore, lookup.bindings())
    }

    // ---- storage failure: no dispatch, no false success ----------------------

    @Test
    fun `cancel with a failing binding lookup is Refused and never calls RunControl`() = runBlocking {
        val fake = FakeRunControl()
        val lookup = RecordingBindingLookup(mutableMapOf(studioRunId to storedFabricRunId)) {
            throw IllegalStateException("run_bindings table is locked")
        }
        val bindingsBefore = lookup.bindings()
        val adapter = FabricRunnerAdapter(fake, packageRegistry, bindingLookup = lookup)

        val result = adapter.cancel(studioRunId)

        // A read failure is *not* "Fabric does not know the run": it is a
        // refusal whose reason names the failure, so an operator can tell
        // the two apart.
        assertEquals(
            FabricCancellationResult.Refused(
                "binding lookup failed for studioRunId studio-run-1: " +
                    "IllegalStateException: run_bindings table is locked",
            ),
            result,
        )
        assertEquals(0, fake.cancelCalls.size)
        assertEquals(0, fake.startCalls.size)
        assertEquals(1, lookup.lookups)
        assertEquals(bindingsBefore, lookup.bindings())
    }

    // ---- additive constructor: the old two-argument form is fail-closed ------

    @Test
    fun `the two-argument constructor is fail-closed and never calls RunControl`() = runBlocking {
        val fake = FakeRunControl()
        // No bindingLookup injected: the default must be "I do not know
        // the Fabric identity", never "use the studio id".
        val adapter = FabricRunnerAdapter(fake, packageRegistry)

        val result = adapter.cancel(studioRunId)

        assertEquals(
            FabricCancellationResult.Unknown(
                "no binding for studioRunId studio-run-1 (the run was never submitted, " +
                    "or it was bound to a different Fabric server)",
            ),
            result,
        )
        assertEquals(0, fake.cancelCalls.size)
        assertEquals(0, fake.startCalls.size)
    }
}
