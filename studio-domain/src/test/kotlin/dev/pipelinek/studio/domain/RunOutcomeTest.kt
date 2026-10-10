package dev.pipelinek.studio.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * B2.6 (2026-10-10): the central outcome classifier
 * has one job — return a typed [RunOutcome] for any
 * payload the SDK might have written. The tests below
 * lock the contract that every consumer of the
 * outcome (the reconciler, future retention, any HTTP
 * surface) can rely on.
 */
class RunOutcomeTest {

    @Test
    fun `success payload is classified as Success`() {
        val out = RunOutcome.fromPayload("""{"outcome":"success"}""")
        assertEquals(RunOutcome.Success, out)
    }

    @Test
    fun `failed payload is classified as Failure with rawOutcome`() {
        val out = RunOutcome.fromPayload("""{"outcome":"failed","error":"boom"}""")
        assertIs<RunOutcome.Failure>(out)
        assertEquals("failed", out.rawOutcome)
    }

    @Test
    fun `error payload is classified as Failure with rawOutcome`() {
        val out = RunOutcome.fromPayload("""{"outcome":"error","error":"boom"}""")
        assertIs<RunOutcome.Failure>(out)
        assertEquals("error", out.rawOutcome)
    }

    @Test
    fun `null or blank payload is Unknown`() {
        assertEquals(RunOutcome.Unknown, RunOutcome.fromPayload(null))
        assertEquals(RunOutcome.Unknown, RunOutcome.fromPayload(""))
        assertEquals(RunOutcome.Unknown, RunOutcome.fromPayload("   "))
    }

    @Test
    fun `payload without an outcome key is Unknown`() {
        // A RunFinished event without an "outcome" field is
        // not a success — the classifier returns Unknown,
        // which the reconciler treats as "not a success".
        assertEquals(RunOutcome.Unknown, RunOutcome.fromPayload("""{"foo":"bar"}"""))
    }

    @Test
    fun `payload with extra whitespace around the colon still parses`() {
        val out = RunOutcome.fromPayload("""{"outcome"   :   "success"}""")
        assertEquals(RunOutcome.Success, out)
    }

    @Test
    fun `payload that nests outcome under a parent object is Unknown`() {
        // The walker is intentionally a top-level-only reader;
        // a future SDK that nests the outcome will return
        // Unknown here, which is the safe default.
        assertEquals(RunOutcome.Unknown, RunOutcome.fromPayload("""{"result":{"outcome":"success"}}"""))
    }

    @Test
    fun `oversized payload is refused and returns Unknown`() {
        // The walker caps payload length at 4096 chars; a
        // hostile SDK writing a 10MB payload must not be
        // able to make the reconciler pay for the scan.
        val huge = "x".repeat(8192) + """ "outcome":"success" """
        assertEquals(RunOutcome.Unknown, RunOutcome.fromPayload(huge))
    }

    @Test
    fun `multiple outcome-like keys — the first wins`() {
        // The SDK only emits one outcome per RunFinished;
        // a defensive test for an unexpected shape.
        val out = RunOutcome.fromPayload("""{"outcome":"success","outcome":"failed"}""")
        // The walker finds the first match; this is a
        // pre-condition the classifier documents (the SDK
        // contract is one outcome per event).
        assertEquals(RunOutcome.Success, out)
    }
}
