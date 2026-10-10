package dev.pipelinek.studio.cert

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.Path

/**
 * B5.4 (2026-10-10): the independent
 * certification harness protocol.
 *
 * The S5 mandate says the studio ships
 * "Certificación independiente de artefacto
 * instalado (sin src checkout): fresh clone,
 * tests de dist, MCP stdio v2, browser offline,
 * runner isolated, metadata SQLite restore,
 * recovery". The protocol is the ordered
 * sequence of steps a fresh-clone run
 * executes. A step that fails short-circuits
 * the run with a non-zero exit + a clear
 * `FAIL <step>: <reason>` line.
 *
 * This file is the protocol ADT. The actual
 * executor lives in `run.sh` (out of scope for
 * v1.0.0-rc.1; the B5.7 release script wires
 * it). The B5.4 test pins:
 *  - the ordered step list
 *  - the verifyChecksums helper against a
 *    known-input vector
 *  - the FAIL short-circuit contract
 *  - the per-step duration reporting shape
 */
@Serializable
data class CertProtocol(
    val steps: List<CertStep>,
) {
    @Serializable
    data class CertStep(
        val name: String,
        val description: String,
    )

    fun toJson(): String = Json { prettyPrint = true }.encodeToString(serializer(), this)

    companion object {
        /**
         * The ordered step list. A change to
         * the order or to the names is a
         * certification change. The B5.4 test
         * pins this list.
         */
        val CURRENT: CertProtocol = CertProtocol(
            steps = listOf(
                CertStep("fresh_clone", "download the published distribution + verify sha256"),
                CertStep("dist_tests", "run `studio doctor` and assert exit 0"),
                CertStep("mcp_v2", "boot the server + run the MCP v2 stdio adapter + send studio.health"),
                CertStep("browser_offline", "open the SPA headless + block network + assert no console errors"),
                CertStep("runner_isolated", "submit a run against a single-Step snapshot + assert SUCCEEDED"),
                CertStep("metadata_sqlite_restore", "backup + restore studio.db + assert bit-perfect SHA-256"),
            ),
        )

        /**
         * Verify every file under [root] has
         * the SHA-256 recorded in [manifest].
         * Returns a list of mismatches (empty
         * when all files match). The harness
         * reports each mismatch as
         * `FAIL fresh_clone: sha256 mismatch: <path>`.
         */
        fun verifyChecksums(root: Path, manifest: Path): List<String> {
            val text = Files.readString(manifest)
            val obj = Json.parseToJsonElement(text)
            val m = when (obj) {
                is kotlinx.serialization.json.JsonObject -> obj
                else -> return listOf("manifest is not a JSON object")
            }
            val files = m["files"] as? kotlinx.serialization.json.JsonArray
                ?: return listOf("manifest has no files[] array")
            val mismatches = mutableListOf<String>()
            for (entry in files) {
                val e = entry as? kotlinx.serialization.json.JsonObject ?: continue
                val rel = (e["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                val expected = (e["sha256"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                val path = root.resolve(rel)
                if (!Files.isRegularFile(path)) {
                    mismatches += "missing: $rel"
                    continue
                }
                val actual = sha256OfFile(path)
                if (actual != expected) {
                    mismatches += "sha256 mismatch: $rel (expected=$expected, actual=$actual)"
                }
            }
            return mismatches
        }

        private fun sha256OfFile(path: Path): String {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * B5.4 (2026-10-10): the per-step result.
 * The harness records the step name, status
 * (PASS / FAIL), duration in milliseconds, and
 * a free-text detail. The shape is
 * JSON-serializable so the harness can write
 * a single `cert-report.json` for the
 * operator.
 */
@Serializable
data class CertStepResult(
    val name: String,
    val status: Status,
    val durationMs: Long,
    val detail: String = "",
) {
    @Serializable
    enum class Status { PASS, FAIL }
}

@Serializable
data class CertReport(
    val steps: List<CertStepResult>,
) {
    val overall: CertStepResult.Status
        get() = when {
            steps.any { it.status == CertStepResult.Status.FAIL } -> CertStepResult.Status.FAIL
            else -> CertStepResult.Status.PASS
        }

    fun toJson(): String = Json { prettyPrint = true }.encodeToString(serializer(), this)
}
