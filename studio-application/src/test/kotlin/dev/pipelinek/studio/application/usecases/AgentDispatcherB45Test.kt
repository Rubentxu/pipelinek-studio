package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.AgentCapability
import dev.pipelinek.studio.application.ports.AgentCapabilityPort
import dev.pipelinek.studio.application.ports.DisabledByDefaultAgentCapability
import dev.pipelinek.studio.application.ports.LineRangeReconstructingRunSelection
import dev.pipelinek.studio.application.ports.PromoteVariantPort
import dev.pipelinek.studio.application.ports.PromoteVariantRequest
import dev.pipelinek.studio.application.ports.PromoteVariantResult
import dev.pipelinek.studio.application.ports.RunComparison
import dev.pipelinek.studio.application.ports.RunComparisonPort
import dev.pipelinek.studio.application.ports.RunComparisonResult
import dev.pipelinek.studio.application.ports.SnapshotDiff
import dev.pipelinek.studio.application.ports.SnapshotDiffPort
import dev.pipelinek.studio.application.ports.SnapshotDiffResult
import dev.pipelinek.studio.application.ports.StepRecord
import dev.pipelinek.studio.domain.ExperimentId
import dev.pipelinek.studio.domain.OperationId
import dev.pipelinek.studio.domain.RunId
import dev.pipelinek.studio.domain.Sha256
import dev.pipelinek.studio.domain.SnapshotId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B4.5 (2026-10-10): the capability-driven
 * `studio.agent` dispatcher tests.
 *
 * The S4 mandate is "capability-driven
 * (no tool-per-step)". The single entry
 * point is [AgentDispatcher.dispatch]. There
 * is NO `dispatchCompare`, `dispatchPromote`,
 * etc. — every action goes through `dispatch`.
 * The test pins:
 *  1. The dispatch table: each action
 *     variant maps to a required
 *     capability and to a port.
 *  2. The capability gate: an action whose
 *     capability is disabled returns
 *     [AgentActionResult.Refused] without
 *     invoking the port.
 *  3. The "no tool-per-step" property: the
 *     dispatcher's PUBLIC surface is exactly
 *     one method (dispatch). Per-step tools
 *     are NOT methods on the class.
 *  4. The refusal format names the disabled
 *     capability so the operator knows which
 *     env var to set.
 *  5. The default capability posture
 *     (all-disabled) refuses every action.
 */
class AgentDispatcherB45Test {

    @Test
    fun `B4_5 dispatch table — every action has a required capability`() {
        // The mapping is the security contract.
        // A change to the mapping is a security
        // change. The test pins the mapping by
        // asserting each action's required cap
        // against the expected value.
        val compare = AgentAction.CompareRuns(RunId.new(), RunId.new())
        assertEquals(AgentCapability.COMPARE, compare.requiredCapability())
        val diff = AgentAction.DiffSnapshots(SnapshotId.new(), SnapshotId.new())
        assertEquals(AgentCapability.DIFF, diff.requiredCapability())
        val promote = AgentAction.PromoteVariant(SnapshotId.new(), "ops", "note")
        assertEquals(AgentCapability.PROMOTE, promote.requiredCapability())
        val select = AgentAction.SelectStep(RunId.new(), OperationId.new())
        assertEquals(AgentCapability.SELECT, select.requiredCapability())
        val mutate = AgentAction.MutateSnapshot(SnapshotId.new(), "m-1", "note")
        assertEquals(AgentCapability.MUTATE, mutate.requiredCapability())
    }

    @Test
    fun `B4_5 capability gate — disabled capability returns Refused without invoking the port`() {
        val recorder = RecordingRunComparison()
        val dispatcher = AgentDispatcher(
            capability = CapabilityStub(mapOf(AgentCapability.COMPARE to false)),
            runComparison = recorder,
            snapshotDiff = NeverCalledSnapshotDiff(),
            promoteVariant = NeverCalledPromoteVariant(),
            runSelection = LineRangeReconstructingRunSelection(
                sourceLoader = { _ -> null },
                stepReader = { _, _ -> null },
            ),
            mutateSnapshot = { _ -> error("mutateSnapshot must not be called") },
        )
        val result = dispatcher.dispatch(AgentAction.CompareRuns(RunId.new(), RunId.new()))
        require(result is AgentActionResult.Refused) { "expected Refused, got $result" }
        assertTrue(
            result.reason.contains("COMPARE"),
            "refusal must name the disabled capability, got: ${result.reason}",
        )
        assertTrue(
            result.reason.contains("STUDIO_AGENT_CAPS"),
            "refusal must mention the env var, got: ${result.reason}",
        )
        assertEquals(0, recorder.calls, "the port must NOT be invoked when capability is disabled")
    }

    @Test
    fun `B4_5 capability gate — enabled capability invokes the port and returns Ok`() {
        val base = RunId.new()
        val other = RunId.new()
        val dispatcher = AgentDispatcher(
            capability = CapabilityStub(mapOf(AgentCapability.COMPARE to true)),
            runComparison = StaticRunComparison(
                base, other,
                RunComparison(
                    baseRunId = base, otherRunId = other,
                    baseTerminal = "SUCCEEDED", otherTerminal = "SUCCEEDED",
                    identicalEvents = 0, onlyInBase = 0, onlyInOther = 0,
                    equal = true,
                ),
            ),
            snapshotDiff = NeverCalledSnapshotDiff(),
            promoteVariant = NeverCalledPromoteVariant(),
            runSelection = LineRangeReconstructingRunSelection(
                sourceLoader = { _ -> null },
                stepReader = { _, _ -> null },
            ),
            mutateSnapshot = { _ -> error("mutateSnapshot must not be called") },
        )
        val result = dispatcher.dispatch(AgentAction.CompareRuns(base, other))
        require(result is AgentActionResult.Ok) { "expected Ok, got $result" }
        assertEquals("compare-runs", result.actionName)
        val payload = result.payload as RunComparison
        assertEquals(base, payload.baseRunId)
        assertEquals(other, payload.otherRunId)
    }

    @Test
    fun `B4_5 capability gate — Refused for every action under the all-disabled default posture`() {
        val dispatcher = AgentDispatcher(
            capability = DisabledByDefaultAgentCapability,
            runComparison = NeverCalledRunComparison(),
            snapshotDiff = NeverCalledSnapshotDiff(),
            promoteVariant = NeverCalledPromoteVariant(),
            runSelection = LineRangeReconstructingRunSelection(
                sourceLoader = { _ -> null },
                stepReader = { _, _ -> null },
            ),
            mutateSnapshot = { _ -> error("mutateSnapshot must not be called") },
        )
        // Every action must be Refused under
        // the all-disabled default posture.
        val actions = listOf(
            AgentAction.CompareRuns(RunId.new(), RunId.new()),
            AgentAction.DiffSnapshots(SnapshotId.new(), SnapshotId.new()),
            AgentAction.PromoteVariant(SnapshotId.new(), "ops", "note"),
            AgentAction.SelectStep(RunId.new(), OperationId.new()),
            AgentAction.MutateSnapshot(SnapshotId.new(), "m-1", "note"),
        )
        for (action in actions) {
            val result = dispatcher.dispatch(action)
            assertTrue(
                result is AgentActionResult.Refused,
                "all-disabled posture must refuse ${action.name}, got $result",
            )
        }
    }

    @Test
    fun `B4_5 no tool-per-step — the dispatcher's public surface is one method`() {
        // The "no tool-per-step" property is
        // structural: the dispatcher's PUBLIC
        // surface is exactly [dispatch]. There
        // is NO `dispatchCompare`, `dispatchPromote`,
        // etc. — every action is a value that
        // flows through `dispatch(action)`.
        //
        // The test inspects the class's public
        // methods (via reflection) and asserts
        // the only callable method is `dispatch`.
        val publicMethods = AgentDispatcher::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("dispatch"),
            publicMethods,
            "AgentDispatcher must expose exactly one public method (dispatch), got: $publicMethods",
        )
    }

    @Test
    fun `B4_5 diff-snapshots action routes to SnapshotDiffPort`() {
        val a = SnapshotId("a")
        val b = SnapshotId("b")
        val dispatcher = AgentDispatcher(
            capability = CapabilityStub(mapOf(AgentCapability.DIFF to true)),
            runComparison = NeverCalledRunComparison(),
            snapshotDiff = StaticSnapshotDiff(
                SnapshotDiff(a, b, linesAdded = 0, linesRemoved = 0, linesChanged = 0, summary = "equal"),
            ),
            promoteVariant = NeverCalledPromoteVariant(),
            runSelection = LineRangeReconstructingRunSelection(
                sourceLoader = { _ -> null },
                stepReader = { _, _ -> null },
            ),
            mutateSnapshot = { _ -> error("mutateSnapshot must not be called") },
        )
        val result = dispatcher.dispatch(AgentAction.DiffSnapshots(a, b))
        require(result is AgentActionResult.Ok) { "expected Ok, got $result" }
        assertEquals("diff-snapshots", result.actionName)
        val payload = result.payload as SnapshotDiff
        assertEquals(a, payload.snapshotA)
        assertEquals(b, payload.snapshotB)
    }

    @Test
    fun `B4_5 promote-variant action routes to PromoteVariantPort`() {
        val variantId = SnapshotId("variant")
        val newId = SnapshotId("new")
        val dispatcher = AgentDispatcher(
            capability = CapabilityStub(mapOf(AgentCapability.PROMOTE to true)),
            runComparison = NeverCalledRunComparison(),
            snapshotDiff = NeverCalledSnapshotDiff(),
            promoteVariant = StaticPromoteVariant(newId, variantId),
            runSelection = LineRangeReconstructingRunSelection(
                sourceLoader = { _ -> null },
                stepReader = { _, _ -> null },
            ),
            mutateSnapshot = { _ -> error("mutateSnapshot must not be called") },
        )
        val result = dispatcher.dispatch(
            AgentAction.PromoteVariant(variantId, "ops:alice", "approved"),
        )
        require(result is AgentActionResult.Ok) { "expected Ok, got $result" }
        assertEquals("promote-variant", result.actionName)
        val payload = result.payload as PromoteVariantOk
        assertEquals(newId, payload.newSnapshotId)
        assertEquals(variantId, payload.promotedFrom)
    }

    // ----- R4.2 item 7: 'Exigir autorización para promover una
    // variante al original'. The pre-R4.2 test only asserted the
    // capability-port's default posture; it did NOT prove the
    // dispatcher short-circuits the port when PROMOTE is disabled.
    // This is the contract that makes 'authorization' real: a
    // misconfigured deployment that forgot to opt in can never reach
    // the promotion port. The test uses a RecordingPromoteVariant
    // that counts calls; if the dispatcher invokes it when
    // capability is disabled, the test fails.
    @Test
    fun `R4_2 promote-variant requires explicit authorization — disabled gate short-circuits the port`() {
        val recorder = RecordingPromoteVariant()
        val dispatcher = AgentDispatcher(
            // Only PROMOTE is disabled; all other caps
            // enabled. We want to pin that the
            // PROMOTE gate alone is sufficient to
            // refuse promotion.
            capability = CapabilityStub(
                mapOf(
                    AgentCapability.COMPARE to true,
                    AgentCapability.DIFF to true,
                    AgentCapability.PROMOTE to false,
                    AgentCapability.SELECT to true,
                    AgentCapability.MUTATE to true,
                ),
            ),
            runComparison = NeverCalledRunComparison(),
            snapshotDiff = NeverCalledSnapshotDiff(),
            promoteVariant = recorder,
            runSelection = LineRangeReconstructingRunSelection(
                sourceLoader = { _ -> null },
                stepReader = { _, _ -> null },
            ),
            mutateSnapshot = { _ -> error("mutateSnapshot must not be called") },
        )
        val result = dispatcher.dispatch(
            AgentAction.PromoteVariant(SnapshotId("variant-x"), "ops:bob", "should be refused"),
        )
        require(result is AgentActionResult.Refused) { "expected Refused, got $result" }
        assertTrue(
            result.reason.contains("PROMOTE"),
            "refusal must name the disabled capability, got: ${result.reason}",
        )
        assertEquals(0, recorder.calls, "promote port must NOT be invoked when PROMOTE is disabled")
    }

    @Test
    fun `B4_5 select-step action routes to RunSelectionPort and surfaces Unknown`() {
        // When the underlying RunSelectionPort
        // returns Unknown, the dispatcher
        // surfaces it as Unknown (NOT as
        // Failure or Refused). The agent
        // can then decide what to do.
        val runId = RunId.new()
        val opId = OperationId("op-runtime")
        val source = """
            |fun step1() { prevResult.value }
        """.trimMargin()
        val dispatcher = AgentDispatcher(
            capability = CapabilityStub(mapOf(AgentCapability.SELECT to true)),
            runComparison = NeverCalledRunComparison(),
            snapshotDiff = NeverCalledSnapshotDiff(),
            promoteVariant = NeverCalledPromoteVariant(),
            runSelection = LineRangeReconstructingRunSelection(
                sourceLoader = { _ -> source.toByteArray() },
                stepReader = { _, _ -> StepRecord(
                    opId = opId,
                    fingerprint = "studio.steps.Step1",
                    input = "Unit",
                    lineRange = 0..0,
                ) },
            ),
            mutateSnapshot = { _ -> error("mutateSnapshot must not be called") },
        )
        val result = dispatcher.dispatch(AgentAction.SelectStep(runId, opId))
        require(result is AgentActionResult.Unknown) { "expected Unknown, got $result" }
        assertEquals("select-step", result.actionName)
        assertTrue(
            result.reason.contains("prevResult") || result.reason.contains("runtime"),
            "Unknown must name the obstacle, got: ${result.reason}",
        )
    }

    // ---- Test fixtures (stubs) ----

    private class CapabilityStub(private val map: Map<AgentCapability, Boolean>) : AgentCapabilityPort {
        override fun isEnabled(capability: AgentCapability): Boolean = map[capability] ?: false
    }

    private class RecordingRunComparison : RunComparisonPort {
        var calls: Int = 0
        override fun compare(base: RunId, other: RunId): RunComparisonResult {
            calls++
            return RunComparisonResult.Failure("recording stub: not invoked in this test")
        }
    }

    private class NeverCalledRunComparison : RunComparisonPort {
        override fun compare(base: RunId, other: RunId): RunComparisonResult {
            error("RunComparisonPort must not be called in this test")
        }
    }

    private class StaticRunComparison(
        private val expectedBase: RunId,
        private val expectedOther: RunId,
        private val value: RunComparison,
    ) : RunComparisonPort {
        override fun compare(base: RunId, other: RunId): RunComparisonResult {
            assertEquals(expectedBase, base)
            assertEquals(expectedOther, other)
            return RunComparisonResult.Ok(value)
        }
    }

    private class NeverCalledSnapshotDiff : SnapshotDiffPort {
        override fun diff(a: SnapshotId, b: SnapshotId): SnapshotDiffResult {
            error("SnapshotDiffPort must not be called in this test")
        }
    }

    private class StaticSnapshotDiff(private val value: SnapshotDiff) : SnapshotDiffPort {
        override fun diff(a: SnapshotId, b: SnapshotId): SnapshotDiffResult {
            return SnapshotDiffResult.Ok(value)
        }
    }

    private class NeverCalledPromoteVariant : PromoteVariantPort {
        override fun promote(request: PromoteVariantRequest): PromoteVariantResult {
            error("PromoteVariantPort must not be called in this test")
        }
    }

    // R4.2 item 7: counts port invocations so the
    // authorization test can assert that the port
    // is short-circuited when the capability gate
    // is closed. A call counter that surfaces a
    // clear failure if it ever reaches 1 in that
    // test (which would mean promotion ran
    // without authorization).
    private class RecordingPromoteVariant : PromoteVariantPort {
        var calls: Int = 0
        override fun promote(request: PromoteVariantRequest): PromoteVariantResult {
            calls++
            error("RecordingPromoteVariant must not be invoked in the gate-closed test (calls=$calls)")
        }
    }

    private class StaticPromoteVariant(
        private val newId: SnapshotId,
        private val promotedFrom: SnapshotId,
    ) : PromoteVariantPort {
        override fun promote(request: PromoteVariantRequest): PromoteVariantResult {
            return PromoteVariantResult.Ok(
                newSnapshotId = newId,
                promotedFrom = promotedFrom,
            )
        }
    }
}
