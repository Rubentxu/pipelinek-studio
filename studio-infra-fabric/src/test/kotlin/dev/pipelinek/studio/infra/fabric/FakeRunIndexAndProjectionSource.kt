@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import io.jenkins.pipelinek.fabric.contracts.model.RunId as FabricRunId
import io.jenkins.pipelinek.fabric.contracts.model.RunSummary
import io.jenkins.pipelinek.fabric.contracts.observation.FactCursor
import io.jenkins.pipelinek.fabric.contracts.observation.FactPage
import io.jenkins.pipelinek.fabric.contracts.observation.FactReadRequest
import io.jenkins.pipelinek.fabric.contracts.observation.FactReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.FactReadResult
import io.jenkins.pipelinek.fabric.contracts.observation.ProjectionSource
import io.jenkins.pipelinek.fabric.contracts.observation.RunIndex
import io.jenkins.pipelinek.fabric.contracts.observation.RunListRequest
import io.jenkins.pipelinek.fabric.contracts.observation.RunListResult
import io.jenkins.pipelinek.fabric.contracts.observation.RunReadRefusal
import io.jenkins.pipelinek.fabric.contracts.observation.RunSummaryResult

/**
 * A test-only [RunIndex] that records every call and returns
 * caller-configured answers.
 *
 * Only [summaryOf] is exercised by the H2.2c+1 tests (the
 * adapter does not call [list] yet — that lands in H2.2c+3
 * or H2.3). The list method has a no-op default that throws
 * if a future regression calls it, so the seam is honest.
 */
class FakeRunIndex : RunIndex {

    var summaryAnswer: RunSummaryResult =
        RunSummaryResult.Found(
            RunSummary(
                runId = FabricRunId("fabric-run-1"),
                displayName = "test",
                state = io.jenkins.pipelinek.fabric.contracts.model.RunState.Running,
                startedAt = java.time.Instant.parse("2026-10-13T10:00:00Z"),
                finishedAt = null,
            ),
        )
    var summaryThrows: Throwable? = null
    val summaryCalls: MutableList<FabricRunId> = mutableListOf()

    override suspend fun summaryOf(runId: FabricRunId): RunSummaryResult {
        summaryCalls.add(runId)
        summaryThrows?.let { throw it }
        return summaryAnswer
    }

    // Not exercised by H2.2c+1; the seam is honest about that
    // and would throw if a future regression reached it.
    override suspend fun list(request: RunListRequest): RunListResult =
        error("RunIndex.list not exercised by H2.2c+1 tests; request=$request")
}

/**
 * A test-only [ProjectionSource] that records every call and
 * returns caller-configured answers.
 */
class FakeProjectionSource : ProjectionSource {

    var readAfterAnswer: FactReadResult = FactReadResult.Page(
        FactPage(
            runId = FabricRunId("fabric-run-1"),
            facts = emptyList(),
            next = FactCursor.NONE,
            complete = true,
            version = 1,
        ),
    )
    var readAfterThrows: Throwable? = null
    val readAfterCalls: MutableList<FactReadRequest> = mutableListOf()

    override suspend fun readAfter(request: FactReadRequest): FactReadResult {
        readAfterCalls.add(request)
        readAfterThrows?.let { throw it }
        return readAfterAnswer
    }
}
