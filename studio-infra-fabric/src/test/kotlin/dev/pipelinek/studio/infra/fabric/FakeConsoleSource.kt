@file:Suppress("TooManyFunctions")

package dev.pipelinek.studio.infra.fabric

import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadRequest
import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleReadResult
import io.jenkins.pipelinek.fabric.contracts.observation.ConsoleSource

/**
 * A test-only [ConsoleSource] that records every call and
 * returns caller-configured answers.
 */
class FakeConsoleSource : ConsoleSource {

    var readAnswer: ConsoleReadResult = ConsoleReadResult.Page(
        io.jenkins.pipelinek.fabric.contracts.observation.ConsolePage(
            console = io.jenkins.pipelinek.fabric.contracts.model.ConsoleRef("console-1"),
            fromByteOffset = 0L,
            nextByteOffset = 0L,
            text = "",
            atEndOfStream = true,
        ),
    )
    var readThrows: Throwable? = null
    val readCalls: MutableList<ConsoleReadRequest> = mutableListOf()

    override suspend fun read(request: ConsoleReadRequest): ConsoleReadResult {
        readCalls.add(request)
        readThrows?.let { throw it }
        return readAnswer
    }
}
