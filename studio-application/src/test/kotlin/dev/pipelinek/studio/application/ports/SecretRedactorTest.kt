package dev.pipelinek.studio.application.ports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * S5 secret-canary tests. The pattern redactor is the *defence*, the
 * tests are the *evidence*: every match case must produce a redacted
 * string that does NOT contain the original secret bytes, and a
 * benign control string must pass through unchanged.
 */
class SecretRedactorTest {

    private val redactor = PatternSecretRedactor()
    private val noop: SecretRedactor = NoopSecretRedactor

    @Test
    fun `redacts AWS access key in markdown body`() {
        val secret = "AKIAIOSFODNN7EXAMPLE"
        val rendered = ResultRenderers.toMarkdown(
            ResultDescriptor.Text("key=$secret", "text/plain"),
            redactor,
        )
        assertThat(rendered).doesNotContain(secret)
        assertThat(rendered).contains("[aws-key:[REDACTED]]")
    }

    @Test
    fun `redacts GitHub PAT in HTML table cell`() {
        val secret = "ghp_abcDEFghijKLMnopQRSTuvwxYZ1234567890"
        val rendered = ResultRenderers.toHtml(
            ResultDescriptor.Table(
                headers = listOf("token"),
                rows = listOf(listOf("Bearer $secret")),
            ),
            redactor,
        )
        assertThat(rendered).doesNotContain(secret)
        assertThat(rendered).contains("github-token")
    }

    @Test
    fun `preserves env-var name but redacts value`() {
        val rendered = ResultRenderers.toMarkdown(
            ResultDescriptor.Text("STUDIO_TOKEN=hunter2 STAGE=prod", "text/plain"),
            redactor,
        )
        assertThat(rendered).doesNotContain("hunter2")
        assertThat(rendered).contains("STUDIO_TOKEN=[REDACTED]")
        assertThat(rendered).contains("STAGE=prod")
    }

    @Test
    fun `redacts high-entropy blob in JSON body`() {
        val secret = "abcdefghijklmnopqrstuvwxyz0123456789ABCD"
        val rendered = ResultRenderers.toHtml(
            ResultDescriptor.Json("""{"sig":"$secret"}"""),
            redactor,
        )
        assertThat(rendered).doesNotContain(secret)
    }

    @Test
    fun `noop redactor leaves everything visible`() {
        val secret = "AKIAIOSFODNN7EXAMPLE"
        val rendered = ResultRenderers.toMarkdown(
            ResultDescriptor.Text("key=$secret", "text/plain"),
            noop,
        )
        assertThat(rendered).contains(secret)
    }

    @Test
    fun `benign short strings are NOT redacted`() {
        val benign = "echo hello world; the result is 42."
        val rendered = ResultRenderers.toMarkdown(
            ResultDescriptor.Text(benign, "text/plain"),
            redactor,
        )
        assertThat(rendered).contains("hello")
    }
}
