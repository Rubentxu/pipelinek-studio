package dev.pipelinek.studio.build.logic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Falsifiers for [FabricPublisher] (SFW H1 Phase A.2 Part 2).
 *
 * The publisher invokes `./gradlew publishToMavenLocal` on the 6
 * contract sub-modules of the Fabric repo. The 3-arm result is
 * Published / Failed / Refused. 5/5 falsifier tests cover the seam.
 */
@DisplayName("FabricPublisher (SFW H1 Phase A.2 Part 2)")
class FabricPublisherTest {

    // ------------------------------------------------------------------
    // Falsifier 1: Published when all 6 modules succeed
    // ------------------------------------------------------------------
    @Test
    fun `Published when all 6 modules succeed`(@TempDir tmp: Path) {
        val fabricDir = tmp.toFile()
        File(fabricDir, "gradlew").writeText("#!/bin/sh\nexit 0\n")
        File(fabricDir, "gradlew").setExecutable(true)
        val captured = mutableListOf<List<String>>()
        val executor = Executor { command, _ ->
            captured.add(command)
            CommandExecution(
                command = command,
                workingDir = fabricDir,
                exitCode = 0,
                stdout = "",
                stderr = "",
            )
        }
        val publisher = FabricPublisher(fabricDir = fabricDir)
        val result = publisher.publish(executor)
        assertTrue(result is PublishResult.Published, "expected Published; got $result")
        val published = result as PublishResult.Published
        assertEquals(6, published.executions.size)
        assertEquals(6, captured.size)
        // Each invocation must target the right module.
        captured.forEachIndexed { idx, command ->
            val expectedModule = FABRIC_CONTRACT_MODULES[idx]
            assertTrue(
                command.contains(":contracts:$expectedModule:publishToMavenLocal"),
                "module $idx must target $expectedModule; got $command"
            )
        }
    }

    // ------------------------------------------------------------------
    // Falsifier 2: Failed when a module returns non-zero
    // ------------------------------------------------------------------
    @Test
    fun `Failed when a module returns non-zero`(@TempDir tmp: Path) {
        val fabricDir = tmp.toFile()
        File(fabricDir, "gradlew").writeText("#!/bin/sh\nexit 0\n")
        File(fabricDir, "gradlew").setExecutable(true)
        // First 3 succeed; 4th (transport) fails; 5th and 6th are NOT attempted.
        var callCount = 0
        val executor = Executor { command, _ ->
            callCount++
            val exit = if (command.any { it.contains(":contracts:transport:") }) 1 else 0
            CommandExecution(
                command = command,
                workingDir = fabricDir,
                exitCode = exit,
                stdout = "",
                stderr = "build failed",
            )
        }
        val publisher = FabricPublisher(fabricDir = fabricDir)
        val result = publisher.publish(executor)
        assertTrue(result is PublishResult.Failed, "expected Failed; got $result")
        val failed = result as PublishResult.Failed
        assertEquals("transport", failed.failedModule)
        assertEquals(4, callCount, "must stop after the first failure (4 invocations: model, observation, run-control, transport)")
    }

    // ------------------------------------------------------------------
    // Falsifier 3: Refused when the Fabric dir is missing
    // ------------------------------------------------------------------
    @Test
    fun `Refused when the Fabric dir is missing`(@TempDir tmp: Path) {
        val missingDir = File(tmp.toFile(), "does-not-exist")
        val publisher = FabricPublisher(fabricDir = missingDir)
        val executor = Executor { _, _ -> error("executor must NOT be invoked when dir is missing") }
        val result = publisher.publish(executor)
        assertTrue(result is PublishResult.Refused, "expected Refused; got $result")
        val refused = result as PublishResult.Refused
        assertTrue(
            refused.reason.contains("does-not-exist"),
            "reason must mention the missing path; got '${refused.reason}'"
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: Refused when the gradle wrapper is missing
    // ------------------------------------------------------------------
    @Test
    fun `Refused when the gradle wrapper is missing`(@TempDir tmp: Path) {
        val fabricDir = tmp.toFile()  // exists, but no gradlew inside
        val publisher = FabricPublisher(fabricDir = fabricDir)
        val executor = Executor { _, _ -> error("executor must NOT be invoked when gradlew is missing") }
        val result = publisher.publish(executor)
        assertTrue(result is PublishResult.Refused, "expected Refused; got $result")
        val refused = result as PublishResult.Refused
        assertTrue(
            refused.reason.contains("gradlew"),
            "reason must mention gradlew; got '${refused.reason}'"
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 5: publisher uses --no-configuration-cache + --no-daemon
    // ------------------------------------------------------------------
    @Test
    fun `publisher uses no-configuration-cache and no-daemon flags`(@TempDir tmp: Path) {
        val fabricDir = tmp.toFile()
        File(fabricDir, "gradlew").writeText("#!/bin/sh\nexit 0\n")
        File(fabricDir, "gradlew").setExecutable(true)
        val captured = mutableListOf<List<String>>()
        val executor = Executor { command, _ ->
            captured.add(command)
            CommandExecution(command, fabricDir, 0, "", "")
        }
        val publisher = FabricPublisher(fabricDir = fabricDir)
        publisher.publish(executor)
        captured.forEach { command ->
            assertTrue(
                "--no-configuration-cache" in command,
                "must pass --no-configuration-cache (Fabric is config-cache-incompatible); got $command"
            )
            assertTrue(
                "--no-daemon" in command,
                "must pass --no-daemon; got $command"
            )
        }
    }
}
