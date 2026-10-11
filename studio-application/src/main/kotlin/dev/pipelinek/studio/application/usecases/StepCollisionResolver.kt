package dev.pipelinek.studio.application.usecases

import dev.pipelinek.studio.application.ports.CollisionReport
import dev.pipelinek.studio.application.ports.ResolvedCatalog
import dev.pipelinek.studio.application.ports.StepContract

/**
 * R2.6 (2026-10-11): resolve a list of [StepContract]s into a
 * [ResolvedCatalog] by detecting collisions on `id` (stepKey) and
 * picking a winner per collision.
 *
 * Decision rule (deterministic):
 *  1. Group by `stepKey`.
 *  2. Within each group, parse `provenance?.pluginJar` (or, for
 *     built-in steps with no JAR, a synthetic sentinel like
 *     `"<builtin>"`) to identify the source.
 *  3. For each group with > 1 entry, sort by semver DESCENDING
 *     (highest version wins). When a step has no version, it
 *     sorts LAST (it can only win via the tiebreak below).
 *  4. Tiebreak (semver equal, or both have no version): sort by
 *     `pluginJar` ASCENDING lexicographically. The first wins.
 *  5. Loser is reported as [CollisionReport].
 *
 * The resolver is a pure function: no I/O, no side effects. Same
 * input → same output. The order of the input list does NOT
 * matter (the resolver re-sorts by semver, then by pluginJar).
 *
 * Note on "no version" steps: built-in SDK steps with `provenance
 * == null` are placed at the END of the sort; they only win when
 * the group has NO other entries. This means a built-in step
 * (e.g. `"compile"`) cannot shadow a plugin step that declares
 * the same key with a higher version. If the operator wants the
 * built-in to win, they must NOT install a plugin with that
 * stepKey.
 */
class StepCollisionResolver {

    /**
     * Resolve the given [contracts] into winners + collision
     * reports. Empty input → empty winners, empty collisions.
     * Order of [winners] is the order of first appearance of each
     * `stepKey` in [contracts] (stable; preserves the input order
     * for non-colliding steps).
     */
    fun resolve(contracts: List<StepContract>): ResolvedCatalog {
        if (contracts.isEmpty()) return ResolvedCatalog(emptyList(), emptyList())
        val byKey: Map<String, List<StepContract>> = contracts.groupBy { it.id }
        val winners = mutableListOf<StepContract>()
        val collisions = mutableListOf<CollisionReport>()
        // Preserve input order for winners (stable).
        val seenKeys = LinkedHashSet<String>()
        for (c in contracts) if (seenKeys.add(c.id)) winners.add(pickWinner(byKey.getValue(c.id), collisions))
        return ResolvedCatalog(winners = winners.toList(), collisions = collisions)
    }

    /**
     * Pick the winner from a same-key group. Mutates [collisions]
     * with one [CollisionReport] per loser. Returns the winner
     * unchanged.
     */
    private fun pickWinner(group: List<StepContract>, collisions: MutableList<CollisionReport>): StepContract {
        if (group.size == 1) return group.single()
        val sorted = group.sortedWith(
            compareByDescending<StepContract> { parseSemverOrNull(it.versionOrNull()) != null }
                .thenByDescending { parseSemverOrNull(it.versionOrNull()) ?: Semver(0, 0, 0) }
                .thenBy { it.pluginJarOrSentinel() },
        )
        val winner = sorted.first()
        for (loser in sorted.drop(1)) {
            collisions += CollisionReport(
                stepKey = winner.id,
                winnerPluginJar = winner.pluginJarOrSentinel(),
                winnerVersion = winner.versionOrNull(),
                loserPluginJar = loser.pluginJarOrSentinel(),
                loserVersion = loser.versionOrNull(),
                reason = reasonFor(winner, loser),
            )
        }
        return winner
    }

    private fun reasonFor(winner: StepContract, loser: StepContract): String {
        val wv = parseSemverOrNull(winner.versionOrNull())
        val lv = parseSemverOrNull(loser.versionOrNull())
        return when {
            wv != null && lv != null && wv > lv -> "lower_version"
            wv != null && lv != null && wv == lv -> "same_version_tie"
            wv == null && lv == null -> "no_version"
            else -> "no_version"  // mixed (one has version, the other doesn't) — the with-version one wins, this is the same surface
        }
    }
}

/**
 * R2.6 (2026-10-11): the version surface for collision
 * comparison. The catalog has no dedicated `version` field on
 * [StepContract]; the version comes from the `pluginJar`
 * filename (e.g. `alpha-1.2.3.jar` → 1.2.3) or the SDK's
 * internal `pluginSdkVersion` (for built-in steps with no JAR).
 *
 * For now, we extract from the JAR filename with a regex; a
 * future iteration can plumb a dedicated `version` field into
 * [StepContract] if the catalog adapter wants to.
 */
private fun StepContract.versionOrNull(): String? {
    val jar = pluginJar ?: return null
    val match = VERSION_IN_JAR.find(jar) ?: return null
    return match.value
}

private fun StepContract.pluginJarOrSentinel(): String = pluginJar ?: "<builtin>"

private val VERSION_IN_JAR = Regex("""(\d+)\.(\d+)\.(\d+)""")

/**
 * R2.6 (2026-10-11): minimal semver value. Compares via [compareTo].
 * Pre-release / build metadata is intentionally NOT supported —
 * a future iteration can extend with [Semver.preRelease] etc.
 */
internal data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}

internal fun parseSemverOrNull(s: String?): Semver? {
    if (s == null) return null
    val match = VERSION_IN_JAR.matchEntire(s) ?: return null
    val (maj, min, pat) = match.destructured
    return Semver(maj.toIntOrNull() ?: return null, min.toIntOrNull() ?: return null, pat.toIntOrNull() ?: return null)
}
