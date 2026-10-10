package dev.pipelinek.studio.infra.sdk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B2.3 (2026-10-10): the runner only writes under its own
 * [controlRootBase]. The PathConfinement helper is the
 * single source of truth for that check; these tests lock
 * the contract so a future change to the runner's path
 * shape has to re-prove confinement.
 */
class PathConfinementTest {

    @Test
    fun `a direct child is Inside`(@TempDir tmp: Path) {
        val base = tmp.resolve("control")
        val child = base.resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val v = PathConfinement.check(base, child, "controlRoot")
        assertIs<PathConfinement.Verdict.Inside>(v)
    }

    @Test
    fun `a grandchild is Inside`(@TempDir tmp: Path) {
        val base = tmp.resolve("control")
        val grand = base.resolve("run-id").resolve("snapshot.pipeline.kts")
        val v = PathConfinement.check(base, grand, "snapshotPath")
        assertIs<PathConfinement.Verdict.Inside>(v)
    }

    @Test
    fun `the base itself is Inside`(@TempDir tmp: Path) {
        val v = PathConfinement.check(tmp, tmp, "self")
        assertIs<PathConfinement.Verdict.Inside>(v)
    }

    @Test
    fun `a sibling whose name starts with the base name is Escaped`(@TempDir tmp: Path) {
        // The classic prefix attack: `base = /x/control` and
        // `candidate = /x/control-other/file`. A naive
        // `candidate.startsWith(base)` would accept it; the
        // runner must not.
        val base = tmp.resolve("control")
        val sibling = tmp.resolve("control-other").resolve("file")
        val v = PathConfinement.check(base, sibling, "controlRoot")
        assertIs<PathConfinement.Verdict.Escaped>(v)
        assertTrue(v.reason.contains("controlRoot"))
        assertTrue(v.reason.contains("control-other"))
    }

    @Test
    fun `a candidate outside the base is Escaped`(@TempDir tmp: Path) {
        val base = tmp.resolve("control")
        val outside = tmp.resolve("elsewhere").resolve("file")
        val v = PathConfinement.check(base, outside, "snapshotPath")
        assertIs<PathConfinement.Verdict.Escaped>(v)
    }

    @Test
    fun `path traversal segments are Escaped when resolved`(@TempDir tmp: Path) {
        // Even if a hostile caller hands the runner a RunId
        // like "../foo", the runner's
        // controlRootBase.resolve(req.runId.value) would
        // produce a path under controlRootBase (the `..` is
        // collapsed by resolve). So this is the "hostile
        // RunId that resolve() keeps inside" case — it is
        // Inside by construction, which is the correct
        // answer: the runner never trusts the RunId to
        // escape, it constructs the path itself. The
        // Escaped verdict only fires for genuine escape
        // attempts in the runner's path logic. The test
        // here documents that the helper trusts Path.resolve
        // semantics.
        val base = tmp.resolve("control")
        val resolved = base.resolve("../control-elsewhere/run.db")
        val v = PathConfinement.check(base, resolved, "controlRoot")
        // resolve collapses the `..`; the result lives
        // outside the base. The helper catches it.
        assertIs<PathConfinement.Verdict.Escaped>(v)
    }
}
