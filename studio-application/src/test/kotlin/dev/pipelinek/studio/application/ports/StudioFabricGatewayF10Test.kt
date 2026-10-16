package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.RunId
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Falsifier for SFW F4 Post-Bump Part 10 — the ADR-014 canonical
 * [StudioFabricGateway] port.
 *
 * The fix is a layered-correctness check: the port lives in
 * `studio-application.ports` (NOT in `studio-infra-fabric`). These tests pin
 * four properties:
 *
 *  1. The interface [StudioFabricGateway] is in the `studio-application.ports`
 *     package (not in studio-infra-fabric).
 *  2. The result ADTs [StudioFabricSubmissionResult] /
 *     [StudioFabricCancellationResult] carry the expected sealed arms:
 *     - Submission: Admitted / Refused / AlreadyRunning (3 arms)
 *     - Cancellation: CancelRequested / AlreadyTerminal / Unknown / Refused (4 arms)
 *  3. Pure-Kotlin types: `fabricRunId` is `String` (not a Fabric-side type).
 *     We assert via type signature inspection of concrete arms.
 *  4. `Admitted` carries both `studioRunId: RunId` and `fabricRunId: String`.
 *
 * Migration of `StartRunUseCase` / `RetryRunUseCase` /
 * `RunStateInspectionUseCase` to consume this port is a follow-up F4-resto
 * unit; out of scope for this falsifier.
 */
@DisplayName("StudioFabricGateway (SFW F4 Part 10 — ADR-014 canonical port)")
class StudioFabricGatewayF10Test {

    @Test
    fun `StudioFabricGateway interface is in studio-application ports (not infra-fabric)`() {
        val pkg = StudioFabricGateway::class.java.packageName
        assertEquals(
            "dev.pipelinek.studio.application.ports",
            pkg,
            "StudioFabricGateway must live in studio-application.ports per ADR-014; found in $pkg"
        )
    }

    @Test
    fun `StudioFabricSubmissionResult has 3 sealed arms (Admitted, Refused, AlreadyRunning)`() {
        val kClass = StudioFabricSubmissionResult::class.java
        val sealedPermittedSubclasses = kClass.permittedSubclasses?.toList().orEmpty()
            .map { it.simpleName }
            .toSet()
        assertEquals(
            setOf("Admitted", "Refused", "AlreadyRunning"),
            sealedPermittedSubclasses,
            "StudioFabricSubmissionResult must have 3 arms: Admitted, Refused, AlreadyRunning; got $sealedPermittedSubclasses"
        )
    }

    @Test
    fun `StudioFabricCancellationResult has 4 sealed arms (CancelRequested, AlreadyTerminal, Unknown, Refused)`() {
        val kClass = StudioFabricCancellationResult::class.java
        val sealedPermittedSubclasses = kClass.permittedSubclasses?.toList().orEmpty()
            .map { it.simpleName }
            .toSet()
        assertEquals(
            setOf("CancelRequested", "AlreadyTerminal", "Unknown", "Refused"),
            sealedPermittedSubclasses,
            "StudioFabricCancellationResult must have 4 arms: CancelRequested, AlreadyTerminal, Unknown, Refused; got $sealedPermittedSubclasses"
        )
    }

    @Test
    fun `Admitted carries both studioRunId and fabricRunId fields`() {
        // Inspect declared fields directly. Note: for value classes, the JVM
        // exposes the underlying String; the kotlin metadata carries the
        // proper RunId reference. We assert by field name (the actual type is
        // checked at compile time by the impl-test below).
        val fieldNames = StudioFabricSubmissionResult.Admitted::class.java.declaredFields
            .map { it.name }
            .toSet()
        assertTrue(
            "studioRunId" in fieldNames && "fabricRunId" in fieldNames,
            "Admitted must declare studioRunId + fabricRunId; got $fieldNames"
        )
    }

    @Test
    fun `gateway result ADTs carry no Fabric gRPC types anywhere`() {
        // Walk all permitted subclasses of both result types; assert no
        // declared method returnType references io.jenkins.pipelinek.fabric.
        fun assertNoFabricLeak(kClass: Class<*>) {
            val offending = kClass.declaredMethods
                .map { it.returnType.name }
                .filter { it.contains("pipelinek.fabric") }
            assertTrue(
                offending.isEmpty(),
                "${kClass.simpleName} must not carry Fabric gRPC types in fields; found $offending"
            )
        }
        assertNoFabricLeak(StudioFabricSubmissionResult.Admitted::class.java)
        assertNoFabricLeak(StudioFabricSubmissionResult.Refused::class.java)
        assertNoFabricLeak(StudioFabricSubmissionResult.AlreadyRunning::class.java)
        assertNoFabricLeak(StudioFabricCancellationResult.AlreadyTerminal::class.java)
        assertNoFabricLeak(StudioFabricCancellationResult.Unknown::class.java)
        assertNoFabricLeak(StudioFabricCancellationResult.Refused::class.java)
    }

    @Test
    fun `StudioFabricGateway can be implemented by a stub with the expected shape`() {
        // Construct a minimal fake. The point is: if the port interface
        // exists with the right shape, this compiles and the body type-checks.
        val fake = object : StudioFabricGateway {
            override suspend fun submit(req: RunRequest): StudioFabricSubmissionResult =
                StudioFabricSubmissionResult.Admitted(
                    studioRunId = req.runId,
                    fabricRunId = "fake-fabric-id",
                )
            override suspend fun cancel(studioRunId: RunId): StudioFabricCancellationResult =
                StudioFabricCancellationResult.CancelRequested
        }
        val req = RunRequest(
            runId = RunId("studio-1"),
            snapshotId = dev.pipelinek.studio.domain.SnapshotId("snap-1"),
            scenarioId = dev.pipelinek.studio.domain.ScenarioId("sc-1"),
            profile = dev.pipelinek.studio.application.ports.ExecutionProfile.TrustedLocal,
        )
        assertNotNull(fake as StudioFabricGateway)
        // Compile-time proof of the port's implementability.
    }
}
