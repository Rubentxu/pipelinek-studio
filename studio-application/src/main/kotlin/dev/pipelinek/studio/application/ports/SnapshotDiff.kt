package dev.pipelinek.studio.application.ports

import dev.pipelinek.studio.domain.SnapshotId

/**
 * B4.2 (2026-10-10): `studio.snapshots.diff` typed
 * snapshot diff per ROADMAP §"S4 API/MCP para
 * variantes de snapshot y compare de runs...".
 *
 * The diff is line-based. Three counters:
 *  - [linesAdded]: lines that exist in B but not in
 *    A (set difference B \ A).
 *  - [linesRemoved]: lines that exist in A but not
 *    in B (set difference A \ B).
 *  - [linesChanged]: lines that exist in both A
 *    and B but appear in a different position
 *    (i.e., they were MOVED, not merely KEPT).
 *    A line that is in both at the same index
 *    is a "kept" line and does NOT count as
 *    changed.
 *
 * The diff is intentionally line-based (not
 * AST-based) so it is deterministic and bounded
 * in size. A future S7 pass can add a structural
 * diff. The B4.2 test pins the determinism
 * property: same input → same diff.
 *
 * The summary is a human-readable string that
 * names the counter values so the agent can
 * verify the diff without parsing the
 * structure.
 */
data class SnapshotDiff(
    val snapshotA: SnapshotId,
    val snapshotB: SnapshotId,
    val linesAdded: Int,
    val linesRemoved: Int,
    val linesChanged: Int,
    val summary: String = "",
) {
    /** True when the two snapshots are byte-identical
     *  on the line dimension. */
    val equal: Boolean
        get() = linesAdded == 0 && linesRemoved == 0 && linesChanged == 0
}

sealed interface SnapshotDiffResult {
    data class Ok(val diff: SnapshotDiff) : SnapshotDiffResult
    data class Failure(val reason: String) : SnapshotDiffResult
}

interface SnapshotDiffPort {
    fun diff(a: SnapshotId, b: SnapshotId): SnapshotDiffResult
}

/**
 * B4.2 (2026-10-10): the default line-based LCS
 * diff impl. The algorithm:
 *  1. Split A and B into lines.
 *  2. linesAdded = |set(B) \ set(A)| (lines
 *     exclusive to B).
 *  3. linesRemoved = |set(A) \ set(B)| (lines
 *     exclusive to A).
 *  4. Compute the LCS (longest common subsequence)
 *     over the line sequences. A line that is in
 *     both A and B but NOT in the LCS is a "moved"
 *     line — it was kept by content but displaced
 *     by other changes.
 *  5. linesChanged = |A| - |set(A) \ set(B)| - |LCS lines at same index in A and B|.
 *
 * For a pair of identical inputs the LCS equals
 * the full sequence, so linesAdded = linesRemoved
 * = linesChanged = 0. For two completely disjoint
 * inputs the LCS is empty, so linesAdded = |B| and
 * linesRemoved = |A| and linesChanged = 0.
 *
 * The implementation is O(n*m) on line count
 * (LCS is dynamic-programmed). It is deterministic
 * and uses no external libraries.
 */
class LineBasedSnapshotDiff(
    private val sourceLoader: (SnapshotId) -> ByteArray?,
) : SnapshotDiffPort {

    override fun diff(a: SnapshotId, b: SnapshotId): SnapshotDiffResult {
        val bytesA = sourceLoader(a)
        val bytesB = sourceLoader(b)
        if (bytesA == null) return SnapshotDiffResult.Failure("snapshot $a not found")
        if (bytesB == null) return SnapshotDiffResult.Failure("snapshot $b not found")
        val textA = bytesA.toString(Charsets.UTF_8)
        val textB = bytesB.toString(Charsets.UTF_8)
        val linesA = textA.split('\n')
        val linesB = textB.split('\n')
        val diff = computeDiff(a, b, linesA, linesB)
        return SnapshotDiffResult.Ok(diff)
    }

    private fun computeDiff(
        a: SnapshotId,
        b: SnapshotId,
        linesA: List<String>,
        linesB: List<String>,
    ): SnapshotDiff {
        val setA = linesA.toHashSet()
        val setB = linesB.toHashSet()
        val linesAdded = linesB.count { it !in setA }
        val linesRemoved = linesA.count { it !in setB }

        // LCS over the line sequences.
        val lcs = lcsLines(linesA, linesB)
        // A "moved" line: present in A, present in
        // B (so not added/removed), but NOT in the
        // LCS (so it was displaced by other changes).
        // We count it as linesChanged.
        val lcsSet = lcs.toHashSet()
        val linesChanged = linesA.count { it in setB && it !in lcsSet }

        val summary = buildString {
            append("snapshot diff: ")
            append("+${linesAdded} added, ")
            append("-${linesRemoved} removed, ")
            append("~${linesChanged} changed")
        }
        return SnapshotDiff(a, b, linesAdded, linesRemoved, linesChanged, summary)
    }

    private fun lcsLines(a: List<String>, b: List<String>): List<String> {
        // Standard O(n*m) LCS via dynamic
        // programming. For our use case (snapshot
        // source files) n and m are bounded by the
        // script size, which is well under 10k
        // lines, so the cubic-on-table memory cost
        // is fine.
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        // Backtrack to recover the LCS sequence.
        val out = ArrayList<String>(dp[n][m])
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] -> {
                    out.add(a[i - 1])
                    i--
                    j--
                }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        out.reverse()
        return out
    }
}
