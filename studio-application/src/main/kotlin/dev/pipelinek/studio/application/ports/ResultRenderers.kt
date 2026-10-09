package dev.pipelinek.studio.application.ports

/**
 * S2 E2.5 — Concrete renderers for [ResultDescriptor]. The descriptor is the contract
 * the SDK uses to describe what a step produced; the studio renders it for the SPA
 * and for the CLI. The renderers are pure functions over the descriptor; the studio
 * never interprets the content semantically (e.g. doesn't try to render "Policy" as
 * something special — per the original D-006 / E2.5 trade-off).
 *
 * Two output formats are provided:
 *   - toMarkdown: stable, diff-friendly, suitable for the CLI `inspect` subcommand
 *     and for copy-paste into issues.
 *   - toHtml: a small static HTML fragment suitable for direct injection in the SPA.
 *     The SPA is expected to apply its own CSS — we emit a stable structural skeleton
 *     (table, code, ul) and never inline style.
 */
object ResultRenderers {

    fun toMarkdown(d: ResultDescriptor): String = when (d) {
        is ResultDescriptor.Text -> "```${d.mime.substringAfter('/')}\n${d.body.trim()}\n```"
        is ResultDescriptor.Json -> "```json\n${prettyJson(d.body)}\n```"
        is ResultDescriptor.Table -> renderTableMarkdown(d.headers, d.rows)
        is ResultDescriptor.ArtifactRefs -> {
            if (d.refs.isEmpty()) "_no artifacts_"
            else d.refs.joinToString("\n") { "- `$it`" }
        }
        is ResultDescriptor.NotAvailable -> "_not available: ${d.reason}_"
    }

    fun toHtml(d: ResultDescriptor): String = when (d) {
        is ResultDescriptor.Text -> "<pre class=\"studio-result-text\" data-mime=\"${escape(d.mime)}\">${escape(d.body)}</pre>"
        is ResultDescriptor.Json -> "<pre class=\"studio-result-json\">${escape(prettyJson(d.body))}</pre>"
        is ResultDescriptor.Table -> renderTableHtml(d.headers, d.rows)
        is ResultDescriptor.ArtifactRefs -> {
            if (d.refs.isEmpty()) "<p class=\"studio-result-empty\">no artifacts</p>"
            else "<ul class=\"studio-result-artifacts\">" +
                d.refs.joinToString("") { "<li><code>${escape(it)}</code></li>" } +
                "</ul>"
        }
        is ResultDescriptor.NotAvailable -> "<p class=\"studio-result-na\">not available: ${escape(d.reason)}</p>"
    }

    private fun renderTableMarkdown(headers: List<String>, rows: List<List<String>>): String {
        if (headers.isEmpty()) return "_empty table_"
        val sb = StringBuilder()
        sb.append("| ").append(headers.joinToString(" | ")).append(" |\n")
        sb.append("|").append(List(headers.size) { "---" }.joinToString("|")).append("|\n")
        for (row in rows) {
            val padded = headers.indices.map { i -> row.getOrElse(i) { "" } }
            sb.append("| ").append(padded.joinToString(" | ")).append(" |\n")
        }
        return sb.toString().trimEnd()
    }

    private fun renderTableHtml(headers: List<String>, rows: List<List<String>>): String {
        if (headers.isEmpty()) return "<p class=\"studio-result-empty\">empty table</p>"
        val sb = StringBuilder()
        sb.append("<table class=\"studio-result-table\"><thead><tr>")
        for (h in headers) sb.append("<th>").append(escape(h)).append("</th>")
        sb.append("</tr></thead><tbody>")
        for (row in rows) {
            sb.append("<tr>")
            for (i in headers.indices) sb.append("<td>").append(escape(row.getOrElse(i) { "" })).append("</td>")
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        return sb.toString()
    }

    private fun prettyJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // Best-effort pretty print without taking a JSON dep; if parsing fails, return raw.
            return runCatching {
                // Minimal pretty: insert newline+indent after {, [, , and before }, ]
                var depth = 0
                val sb = StringBuilder()
                for (c in trimmed) {
                    when (c) {
                        '{', '[' -> { sb.append(c).append('\n'); depth++; sb.append("  ".repeat(depth)) }
                        '}', ']' -> { depth--; sb.append('\n').append("  ".repeat(depth)).append(c) }
                        ',' -> sb.append(c).append('\n').append("  ".repeat(depth))
                        ':' -> sb.append(c).append(' ')
                        else -> sb.append(c)
                    }
                }
                sb.toString()
            }.getOrDefault(raw)
        }
        return raw
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
