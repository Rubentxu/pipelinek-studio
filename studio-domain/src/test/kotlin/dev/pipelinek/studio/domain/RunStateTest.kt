package dev.pipelinek.studio.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RunStateTest {
    @Test
    fun `terminal states have isTerminal=true`() {
        assertTrue(RunState.SUCCEEDED.isTerminal)
        assertTrue(RunState.FAILED.isTerminal)
        assertTrue(RunState.CANCELLED.isTerminal)
        assertTrue(RunState.REFUSED.isTerminal)
    }

    @Test
    fun `non-terminal states have isTerminal=false`() {
        assertFalse(RunState.CREATED.isTerminal)
        assertFalse(RunState.RUNNING.isTerminal)
        assertFalse(RunState.RECOVERY_REQUIRED.isTerminal)
        assertFalse(RunState.LOST.isTerminal)
        assertFalse(RunState.UNKNOWN.isTerminal)
    }

    @Test
    fun `happy path CREATED to SUCCEEDED is valid`() {
        for (pair in listOf(
            RunState.CREATED to RunState.ADMITTED,
            RunState.ADMITTED to RunState.QUEUED,
            RunState.QUEUED to RunState.STARTING,
            RunState.STARTING to RunState.RUNNING,
            RunState.RUNNING to RunState.SUCCEEDED,
        )) {
            assertTrue(RunStateMachine.canTransition(pair.first, pair.second), "expected $pair")
        }
    }

    @Test
    fun `RUNNING to RECOVERY_REQUIRED allowed`() {
        assertTrue(RunStateMachine.canTransition(RunState.RUNNING, RunState.RECOVERY_REQUIRED))
    }

    @Test
    fun `cannot skip states - STARTING directly to SUCCEEDED forbidden`() {
        assertFalse(RunStateMachine.canTransition(RunState.STARTING, RunState.SUCCEEDED))
    }

    @Test
    fun `terminal states cannot transition further`() {
        assertThrows<IllegalArgumentException> {
            RunStateMachine.requireTransition(RunState.SUCCEEDED, RunState.RUNNING)
        }
    }

    @Test
    fun `LOST can only go to UNKNOWN or terminal FAILED via recovery`() {
        assertTrue(RunStateMachine.canTransition(RunState.LOST, RunState.UNKNOWN))
    }

    @Test
    fun `state machine allows RUNNING to RECOVERY_REQUIRED, enabling honest recovery`() {
        // critical invariant: when a runner goes silent, the state machine permits
        // RUNNING -> RECOVERY_REQUIRED, so Studio can refuse to invent FAILED without
        // upstream evidence (per DATA_MODEL_AND_PERSISTENCE.md).
        assertTrue(
            RunStateMachine.canTransition(RunState.RUNNING, RunState.RECOVERY_REQUIRED),
            "RUNNING -> RECOVERY_REQUIRED must be allowed",
        )
    }
}

class Sha256Test {
    @Test
    fun `digest is deterministic`() {
        val a = Sha256.digest("hello".toByteArray())
        val b = Sha256.digest("hello".toByteArray())
        assertTrue(a.value == b.value)
    }

    @Test
    fun `digest hex is 64 chars`() {
        val s = Sha256.digest("anything".toByteArray())
        assertTrue(s.value.length == 64)
    }

    @Test
    fun `different inputs different digests`() {
        val a = Sha256.digest("hello".toByteArray())
        val b = Sha256.digest("world".toByteArray())
        assertFalse(a.value == b.value)
    }

    @Test
    fun `rejects wrong-length hex`() {
        assertThrows<IllegalArgumentException> { Sha256("not-hex") }
    }
}

class SourceSnapshotTest {
    @Test
    fun `isContentValid returns true when sha256 matches`() {
        val bytes = "println(\"hi\")".toByteArray()
        val snap = SourceSnapshot(
            id = SnapshotId.new(),
            experimentId = ExperimentId.new(),
            sha256 = Sha256.digest(bytes),
            sourceUtf8 = bytes,
            createdAt = java.time.Instant.now(),
            creator = "test",
        )
        assertTrue(snap.isContentValid())
    }

    @Test
    fun `isContentValid returns false when sha256 does not match`() {
        val bytes = "println(\"hi\")".toByteArray()
        val snap = SourceSnapshot(
            id = SnapshotId.new(),
            experimentId = ExperimentId.new(),
            sha256 = Sha256.digest("other".toByteArray()),
            sourceUtf8 = bytes,
            createdAt = java.time.Instant.now(),
            creator = "test",
        )
        assertFalse(snap.isContentValid())
    }
}
