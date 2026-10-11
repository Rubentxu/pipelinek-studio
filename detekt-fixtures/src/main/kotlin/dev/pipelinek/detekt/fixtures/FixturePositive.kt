package dev.pipelinek.detekt.fixtures

/**
 * R3.5 positive fixture — clean code that must NOT trigger any
 * active rule in `detekt.yml`. Used by `scripts/verify-detekt.sh`
 * to assert that the Detekt baseline does not produce false
 * positives on idiomatic Kotlin. Every style choice here is
 * deliberately minimal: short method, named parameters, no
 * magic numbers, conventional naming, no unused imports, no
 * wildcard imports, no `!!` casts, no `lateinit`.
 *
 * If a baseline refactor adds a new active rule and this fixture
 * starts failing, the rule needs justification or the threshold
 * needs tuning — not a fixture fix.
 */
class FixturePositive {

    fun add(left: Int, right: Int): Int = left + right

    fun describe(value: Int): String = when (value) {
        0 -> "zero"
        1 -> "one"
        else -> "many"
    }

    private data class Box(val width: Int, val height: Int)

    fun area(b: Box): Int = b.width * b.height
}