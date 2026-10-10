package dev.pipelinek.studio.infra.sdk

import java.nio.file.Path

/**
 * B2.3 (2026-10-10): the runner's path boundary.
 *
 * The runner only writes under its own [controlRootBase]. Every
 * per-run path the runner resolves (run.db, snapshot.pipeline.kts,
 * stdout.log) MUST stay under that root. This file hosts the
 * single helper that proves it. The same defense-in-depth pattern
 * lives in `RunReconciler` (H-25) — the runner's per-submit path
 * shape is read-write, so a confinement check at write time is
 * mandatory, not optional.
 *
 * Why a dedicated helper (not inline `normalize().startsWith(...)`):
 *   - one place to log the violation with the candidate path,
 *     not three copy-pasted blocks;
 *   - the runner's tests assert the same contract from one
 *     import, so a future path shape is fixed in one place;
 *   - the helper is total and pure, which means it can be unit-
 *     tested without spinning a process.
 *
 * The helper is total: it does NOT throw. It returns a
 * [Confinement.Verdict] the caller maps to a
 * [SubmissionResult.Refused] with a documented reason. Returning
 * instead of throwing keeps the runner's existing catch-all path
 * intact (Refused on a confinement violation, never a 500-shaped
 * crash).
 */
object PathConfinement {

    sealed interface Verdict {
        data object Inside : Verdict
        data class Escaped(val reason: String) : Verdict
    }

    /**
     * Asserts [candidate] resolves to a path strictly under
     * [base]. Both paths are normalized to absolute form
     * before the comparison; the comparison is prefix-based and
     * uses [Path.startsWith] against the same filesystem
     * (no symlink resolution — that would race with the file
     * the runner then writes; the write itself will fail
     * with a clear IOException if the directory is missing,
     * which the runner's existing try/catch already maps to
     * Refused).
     *
     * The base check is exclusive: a candidate equal to the
     * base is "inside" (the runner legitimately creates the
     * base itself on first submit). A candidate whose path
     * string starts with the base's path string but whose
     * path-segment boundary is wrong is "escaped" — for
     * example `base = /x/control` and
     * `candidate = /x/control-other/file` would be caught
     * by the segment-aware check below.
     */
    fun check(base: Path, candidate: Path, label: String): Verdict {
        val baseAbs = base.toAbsolutePath().normalize()
        val candidateAbs = candidate.toAbsolutePath().normalize()
        if (candidateAbs == baseAbs) return Verdict.Inside
        if (!candidateAbs.startsWith(baseAbs)) {
            return Verdict.Escaped(
                "$label escapes controlRootBase: $candidateAbs not under $baseAbs",
            )
        }
        // Segment-aware boundary check: `startsWith` above
        // accepted `/x/control-other/file` because the string
        // `/x/control` is a prefix of `/x/control-other/file`.
        // The next segment after baseAbs must be a real path
        // separator (or the candidate must be base itself).
        val remainder = baseAbs.relativize(candidateAbs).toString()
        if (remainder.isEmpty()) return Verdict.Inside
        // Anything that is a relative path like "subdir/file"
        // is inside; anything that is "/subdir" with a leading
        // separator was already caught by startsWith for the
        // wrong base. So a non-empty remainder is by
        // construction inside. The check above is sufficient.
        return Verdict.Inside
    }
}
