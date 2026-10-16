package dev.pipelinek.studio.infra.podman

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Falsifiers for the SFW-H5.3+ nineteenth
 * follow-up: circuit breaker.
 *
 * The 19th follow-up introduces a
 * [CircuitBreaker] interface and a
 * [CountingCircuitBreaker] implementation.
 * After `failureThreshold` consecutive
 * failures, the circuit OPENS and fails
 * fast for `resetTimeout`. After the
 * cooldown, the circuit HALF-OPENs and
 * allows one test call.
 *
 * 6/6 falsifier tests verify the state
 * machine.
 */
@DisplayName("CircuitBreaker (SFW-H5.3+)")
class CircuitBreakerH53Plus19Test {

    // ------------------------------------------------------------------
    // Falsifier 1: noop circuit breaker is backwards-compatible
    // ------------------------------------------------------------------
    @Test
    fun `noop circuit breaker is backwards-compatible`() {
        val result = NoopCircuitBreaker.execute { "ok" }
        assertEquals("ok", result)
    }

    // ------------------------------------------------------------------
    // Falsifier 2: counting circuit breaker starts CLOSED
    // ------------------------------------------------------------------
    @Test
    fun `counting circuit breaker starts CLOSED`() {
        val cb = CountingCircuitBreaker(failureThreshold = 3)
        val result = cb.execute { "ok" }
        assertEquals("ok", result)
    }

    // ------------------------------------------------------------------
    // Falsifier 3: circuit OPENS after failureThreshold consecutive failures
    // ------------------------------------------------------------------
    @Test
    fun `circuit OPENS after failureThreshold consecutive failures`() {
        val cb = CountingCircuitBreaker(failureThreshold = 3)
        repeat(3) {
            assertThrows(RuntimeException::class.java) {
                @Suppress("RedundantUnitExpression")
                cb.execute<Unit> { throw RuntimeException("fail") }
            }
        }
        // The circuit should now be OPEN.
        // The next call must throw
        // CircuitBreakerOpenException
        // (not RuntimeException).
        val ex = assertThrows(CircuitBreakerOpenException::class.java) {
            @Suppress("RedundantUnitExpression")
            cb.execute<Unit> { "should not run" }
        }
        assertTrue(
            ex.message!!.contains("OPEN"),
            "message must mention OPEN state; got '${ex.message}'",
        )
    }

    // ------------------------------------------------------------------
    // Falsifier 4: OPEN circuit fails fast (no block call)
    // ------------------------------------------------------------------
    @Test
    fun `OPEN circuit fails fast without calling block`() {
        val cb = CountingCircuitBreaker(failureThreshold = 2)
        // Trigger OPEN.
        repeat(2) {
            assertThrows(RuntimeException::class.java) {
                @Suppress("RedundantUnitExpression")
                cb.execute<Unit> { throw RuntimeException("fail") }
            }
        }
        // The block must not be called
        // when the circuit is OPEN.
        var blockCalled = false
        assertThrows(CircuitBreakerOpenException::class.java) {
            cb.execute<Unit> {
                blockCalled = true
                "should not run"
            }
        }
        assertTrue(!blockCalled, "block must not be called when circuit is OPEN")
    }

    // ------------------------------------------------------------------
    // Falsifier 5: circuit HALF-OPENs after resetTimeout and CLOSEs on success
    // ------------------------------------------------------------------
    @Test
    fun `circuit HALF-OPENs after resetTimeout and CLOSEs on success`() {
        val now = AtomicLong(0)
        val cb = CountingCircuitBreaker(
            failureThreshold = 2,
            resetTimeout = Duration.ofMillis(100),
            clock = { now.get() },
        )
        // Trigger OPEN.
        repeat(2) {
            assertThrows(RuntimeException::class.java) {
                cb.execute { throw RuntimeException("fail") }
            }
        }
        // Circuit is OPEN. Advance the
        // clock past the resetTimeout.
        now.set(150)
        // The next call should HALF_OPEN
        // and succeed (the block returns
        // a value without throwing).
        val result = cb.execute { "recovered" }
        assertEquals("recovered", result)
        // The circuit should now be CLOSED.
        // Subsequent calls must work
        // normally.
        val result2 = cb.execute { "still ok" }
        assertEquals("still ok", result2)
    }

    // ------------------------------------------------------------------
    // Falsifier 6: HALF_OPEN failure re-OPENs the circuit
    // ------------------------------------------------------------------
    @Test
    fun `HALF_OPEN failure re-OPENs the circuit`() {
        val now = AtomicLong(0)
        val cb = CountingCircuitBreaker(
            failureThreshold = 2,
            resetTimeout = Duration.ofMillis(100),
            clock = { now.get() },
        )
        repeat(2) {
            assertThrows(RuntimeException::class.java) {
                @Suppress("RedundantUnitExpression")
                cb.execute<Unit> { throw RuntimeException("fail") }
            }
        }
        // Advance the clock past the
        // resetTimeout.
        now.set(150)
        // The HALF_OPEN test call fails.
        assertThrows(RuntimeException::class.java) {
            @Suppress("RedundantUnitExpression")
            cb.execute<Unit> { throw RuntimeException("still down") }
        }
        // The circuit should re-OPEN.
        // The next call (before the
        // next resetTimeout) must
        // throw CircuitBreakerOpenException.
        val ex = assertThrows(CircuitBreakerOpenException::class.java) {
            @Suppress("RedundantUnitExpression")
            cb.execute<Unit> { "should not run" }
        }
        assertTrue(
            ex.message!!.contains("OPEN"),
            "circuit must re-OPEN after HALF_OPEN failure; got '${ex.message}'",
        )
    }
}
