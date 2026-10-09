package dev.pipelinek.studio.application.ports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S2 E2.5 — ResultRenderers unit tests. Pure functions, no SDK or HTTP required.
 */
class ResultRenderersTest {

    @Test
    fun `text renderer wraps in a fenced block with the right mime`() {
        val md = ResultRenderers.toMarkdown(ResultDescriptor.Text("hello", "text/markdown"))
        assertEquals("```markdown\nhello\n```", md)
    }

    @Test
    fun `text renderer defaults to text-plain when no mime supplied`() {
        val md = ResultRenderers.toMarkdown(ResultDescriptor.Text("hi"))
        // substringAfter('/') turns "text/plain" into "plain" — that's the documented behaviour.
        assertTrue(md.startsWith("```plain"), "got: $md")
    }

    @Test
    fun `json renderer pretty-prints simple objects`() {
        val md = ResultRenderers.toMarkdown(ResultDescriptor.Json("""{"a":1,"b":[1,2]}"""))
        // pretty-printer is best-effort; we assert the structural properties.
        assertTrue(md.contains("\"a\""), "got: $md")
        assertTrue(md.contains("\"b\""), "got: $md")
        assertTrue(md.startsWith("```json"), "got: $md")
    }

    @Test
    fun `table renderer emits markdown with header rule and rows`() {
        val md = ResultRenderers.toMarkdown(
            ResultDescriptor.Table(
                headers = listOf("id", "name"),
                rows = listOf(listOf("1", "alpha"), listOf("2", "beta")),
            ),
        )
        assertTrue(md.contains("| id | name |"), "got: $md")
        assertTrue(md.contains("|---|---|"), "got: $md")
        assertTrue(md.contains("| 1 | alpha |"), "got: $md")
        assertTrue(md.contains("| 2 | beta |"), "got: $md")
    }

    @Test
    fun `table renderer pads short rows so markdown stays well-formed`() {
        val md = ResultRenderers.toMarkdown(
            ResultDescriptor.Table(
                headers = listOf("a", "b", "c"),
                rows = listOf(listOf("1")),
            ),
        )
        // row should have three cells separated by " | "
        assertTrue(md.contains("| 1 |  |  |"), "got: $md")
    }

    @Test
    fun `artifact refs renderer lists one item per line as markdown bullet`() {
        val md = ResultRenderers.toMarkdown(
            ResultDescriptor.ArtifactRefs(listOf("s3://b/a.txt", "s3://b/b.txt")),
        )
        assertTrue(md.contains("- `s3://b/a.txt`"), "got: $md")
        assertTrue(md.contains("- `s3://b/b.txt`"), "got: $md")
    }

    @Test
    fun `not-available renderer shows reason in italic`() {
        val md = ResultRenderers.toMarkdown(ResultDescriptor.NotAvailable("BLOCKED-BY-OBS"))
        assertEquals("_not available: BLOCKED-BY-OBS_", md)
    }

    @Test
    fun `html renderers escape user-provided content`() {
        val html = ResultRenderers.toHtml(ResultDescriptor.Text("<script>alert(1)</script>"))
        assertTrue(html.contains("&lt;script&gt;"), "expected escaped script tag, got: $html")
        assertTrue(!html.contains("<script>alert"), "raw script tag survived escaping")
    }

    @Test
    fun `html table renderer emits thead and tbody`() {
        val html = ResultRenderers.toHtml(
            ResultDescriptor.Table(listOf("a"), listOf(listOf("1"))),
        )
        assertTrue(html.contains("<thead>"), "got: $html")
        assertTrue(html.contains("<tbody>"), "got: $html")
        assertTrue(html.contains("<th>a</th>"), "got: $html")
        assertTrue(html.contains("<td>1</td>"), "got: $html")
    }

    @Test
    fun `html artifact refs renderer emits ul with code elements`() {
        val html = ResultRenderers.toHtml(ResultDescriptor.ArtifactRefs(listOf("file:///x")))
        assertTrue(html.contains("<ul"), "got: $html")
        assertTrue(html.contains("<code>file:///x</code>"), "got: $html")
    }

    @Test
    fun `empty table is handled gracefully in both renderers`() {
        assertEquals("_empty table_", ResultRenderers.toMarkdown(ResultDescriptor.Table(emptyList(), emptyList())))
        assertTrue(ResultRenderers.toHtml(ResultDescriptor.Table(emptyList(), emptyList())).contains("empty table"))
    }
}
