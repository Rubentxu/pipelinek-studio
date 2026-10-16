package dev.pipelinek.studio.infra.podman

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SFW-H5.3+ 19th follow-up: a circuit
 * breaker. The circuit breaker prevents
 * cascading failures when a downstream
 * service is consistently failing. After
 * a configurable number of consecutive
 * failures, the circuit OPENS and fails
 * fast (no attempt to call the
 * downstream) for a cooldown period.
 * After the cooldown, the circuit
 * HALF-OPENs and allows one test call.
 * If the test call succeeds, the circuit
 * CLOSES (back to normal). If the test
 * call fails, the circuit re-OPENs for
 * another cooldown.
 *
 * ## States
 *
 * - CLOSED: normal operation. Failures
 *   are counted; when the count reaches
 *   [failureThreshold], the circuit
 *   OPENS.
 * - OPEN: the circuit is broken. Calls
 *   fail fast with
 *   [CircuitBreakerOpenException] without
 *   attempting the downstream. After
 *   [resetTimeout], the circuit HALF-OPENs.
 * - HALF_OPEN: a single test call is
 *   allowed. If it succeeds, the circuit
 *   CLOSES. If it fails, the circuit
 *   re-OPENs.
 *
 * ## Why a circuit breaker
 *
 * The H5.3+ 18th follow-up added retries
 * for transient failures. But retries
 * don't help if the downstream is
 * consistently failing (e.g., the
 * registry is down for maintenance).
 * Retrying just adds load to the
 * downstream and latency to the caller.
 * A circuit breaker fails fast: the
 * caller gets an immediate error, and
 * the downstream gets a chance to
 * recover during the cooldown.
 */
interface CircuitBreaker {
    /**
     * Execute [block] under the circuit
     * breaker. If the circuit is OPEN,
     * throws [CircuitBreakerOpenException]
     * without calling [block]. Otherwise,
     * calls [block] and records the
     * success or failure for the state
     * machine.
     *
     * @throws CircuitBreakerOpenException
     *   if the circuit is OPEN
     */
    fun <T> execute(block: () -> T): T
}

class CircuitBreakerOpenException(
    message: String,
) : RuntimeException(message)

/**
 * SFW-H5.3+ 19th follow-up: the no-op
 * circuit breaker. Always CLOSED. The
 * default impl when no breaker is
 * configured (backwards-compatible).
 */
object NoopCircuitBreaker : CircuitBreaker {
    override fun <T> execute(block: () -> T): T = block()
}

/**
 * SFW-H5.3+ 19th follow-up: a counting
 * circuit breaker. Opens after
 * [failureThreshold] consecutive
 * failures; closes after [resetTimeout]
 * of successful cooldown.
 *
 * Thread-safe: uses atomic state. The
 * state transitions are:
 * - CLOSED → OPEN: when
 *   [failureThreshold] consecutive
 *   failures occur.
 * - OPEN → HALF_OPEN: when [resetTimeout]
 *   elapses since the circuit opened.
 * - HALF_OPEN → CLOSED: when a test
 *   call succeeds.
 * - HALF_OPEN → OPEN: when a test
 *   call fails (resets the cooldown).
 *
 * @param failureThreshold the number of
 *   consecutive failures that trigger
 *   the OPEN state. Default 5.
 * @param resetTimeout the cooldown
 *   period before the circuit attempts
 *   a test call. Default 30s.
 * @param clock the time source. Tests
 *   inject a fake clock to verify
 *   the cooldown logic.
 */
class CountingCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeout: Duration = Duration.ofSeconds(30),
    private val clock: () -> Long = System::currentTimeMillis,
) : CircuitBreaker {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicReference(State.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAtRef = AtomicLong(0)

    init {
        require(failureThreshold >= 1) {
            "failureThreshold must be >= 1 (got $failureThreshold)"
        }
        require(!resetTimeout.isNegative) {
            "resetTimeout must be non-negative (got $resetTimeout)"
        }
    }

    override fun <T> execute(block: () -> T): T {
        when (state.get()) {
            State.OPEN -> {
                val elapsed = clock() - openedAtRef.get()
                if (elapsed >= resetTimeout.toMillis()) {
                    // Cooldown elapsed; try to
                    // transition to HALF_OPEN.
                    // The CAS may fail if
                    // another thread already
                    // transitioned; that's OK.
                    state.compareAndSet(State.OPEN, State.HALF_OPEN)
                } else {
                    throw CircuitBreakerOpenException(
                        "circuit breaker is OPEN " +
                            "(elapsed=${elapsed}ms, " +
                            "resetTimeout=${resetTimeout.toMillis()}ms)",
                    )
                }
            }
            State.HALF_OPEN, State.CLOSED -> {
                // proceed
            }
        }
        return try {
            val result = block()
            // Success: reset the failure
            // count and close the circuit.
            consecutiveFailures.set(0)
            state.set(State.CLOSED)
            result
        } catch (e: Exception) {
            val failures = consecutiveFailures.incrementAndGet()
            when {
                state.get() == State.HALF_OPEN -> {
                    // The test call failed.
                    // Re-open the circuit.
                    state.set(State.OPEN)
                    openedAtRef.set(clock())
                }
                failures >= failureThreshold -> {
                    // Threshold reached; open
                    // the circuit.
                    state.set(State.OPEN)
                    openedAtRef.set(clock())
                }
            }
            throw e
        }
    }
}
