@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import io.jenkins.pipelinek.fabric.contracts.model.CommandResult
import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.runcontrol.CancelRunCommand
import io.jenkins.pipelinek.fabric.contracts.runcontrol.RunControl
import io.jenkins.pipelinek.fabric.contracts.runcontrol.StartRunRequest

/**
 * A test-only [RunControl] that records every call and returns
 * caller-configured answers.
 *
 * The H2.2c adapter takes a Fabric [RunControl] in its constructor
 * (so a unit test can substitute a fake without bringing up a gRPC
 * socket). This is the fake.
 *
 * Each method has two halves: a *caller-configured* return value
 * (`startAnswer`, `cancelAnswer`, `startThrows`, ...) and a *recorded*
 * side (`startCalls`, `cancelCalls`, ...) so a falsifier can assert
 * on both halves — that the adapter made the call it should have
 * AND that the call it made was translated correctly.
 */
class FakeRunControl : RunControl {

    // ---- start ----------------------------------------------------------------

    /** The next answer `start` will return. Mutated between tests. */
    var startAnswer: CommandResult<FabricRunId> =
        CommandResult.Accepted(FabricRunId("fabric-run-1"))

    /**
     * If non-null, `start` throws this instead of returning
     * [startAnswer]. Used to exercise the
     * [FabricTransportFailure] path without a real socket.
     */
    var startThrows: Throwable? = null

    /** Every `StartRunRequest` `start` was called with, in order. */
    val startCalls: MutableList<StartRunRequest> = mutableListOf()

    override suspend fun start(request: StartRunRequest): CommandResult<FabricRunId> {
        startCalls.add(request)
        startThrows?.let { throw it }
        return startAnswer
    }

    // ---- cancel ---------------------------------------------------------------

    /** The next answer `cancel` will return. Mutated between tests. */
    var cancelAnswer: CommandResult<FabricRunId> =
        CommandResult.Accepted(FabricRunId("fabric-run-1"))

    /** If non-null, `cancel` throws this instead of returning [cancelAnswer]. */
    var cancelThrows: Throwable? = null

    /** Every `CancelRunCommand` `cancel` was called with, in order. */
    val cancelCalls: MutableList<CancelRunCommand> = mutableListOf()

    override suspend fun cancel(command: CancelRunCommand): CommandResult<FabricRunId> {
        cancelCalls.add(command)
        cancelThrows?.let { throw it }
        return cancelAnswer
    }
}
