package dev.pipelinek.studio.application.fixtures

import dev.pipelinek.studio.application.ports.ScenarioExecutionRequest
import dev.pipelinek.studio.domain.ScenarioId

/**
 * S4 E4.1 — Scenario generation testkit. A small builder that constructs
 * [ScenarioExecutionRequest] values from typed fields and serializes the
 * input to a minimal JSON object literal. Tests and agent code compose
 * scenarios from parts without hand-rolling JSON strings.
 *
 * The kit is intentionally tiny: a builder for inputs, a spec for the
 * outer wrapper, and a couple of factory methods for the most common
 * shapes (Text, Json). The serializer is hand-rolled because the test
 * source set does not depend on kotlinx.serialization.
 */
class ScenarioBuilder(private val id: ScenarioId, private val stepId: String) {
    private val fields = mutableMapOf<String, String>()

    fun put(key: String, value: String): ScenarioBuilder = apply { fields[key] = value }

    fun put(key: String, value: Number): ScenarioBuilder = apply { fields[key] = value.toString() }

    fun put(key: String, value: Boolean): ScenarioBuilder = apply { fields[key] = value.toString() }

    fun build(): ScenarioExecutionRequest = ScenarioExecutionRequest(
        scenarioId = id,
        stepId = stepId,
        inputJson = toJsonObject(fields),
    )

    companion object {
        fun textScenario(id: String, stepId: String, body: String): ScenarioExecutionRequest =
            ScenarioBuilder(ScenarioId(id), stepId).put("body", body).build()

        fun jsonScenario(id: String, stepId: String, payload: String): ScenarioExecutionRequest =
            ScenarioBuilder(ScenarioId(id), stepId).put("payload", payload).build()

        /**
         * Hand-rolled JSON object literal serializer. Keys are escaped to
         * "key"; string values are escaped; numeric/boolean values are
         * emitted as-is. We only need enough JSON for the SDK to read
         * the input shape; a real SDK would use kotlinx.serialization or
         * Jackson.
         */
        private fun toJsonObject(fields: Map<String, String>): String {
            val sb = StringBuilder("{")
            var first = true
            for ((k, v) in fields) {
                if (!first) sb.append(',')
                sb.append('"').append(escape(k)).append("\":")
                sb.append(jsonValue(v))
                first = false
            }
            sb.append('}')
            return sb.toString()
        }

        private fun jsonValue(v: String): String = when (v) {
            "true", "false" -> v
            else -> {
                val asLong = v.toLongOrNull()
                if (asLong != null) v
                else "\"${escape(v)}\""
            }
        }

        private fun escape(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}

/** Outer wrapper a test or agent can serialize directly. */
data class ScenarioSpec(
    val id: String,
    val stepId: String,
    val input: Map<String, String>,
) {
    fun toRequest(): ScenarioExecutionRequest =
        ScenarioBuilder(ScenarioId(id), stepId).apply { input.forEach { (k, v) -> put(k, v) } }.build()

    companion object {
        /** Best-effort deserialization of the inputJson back to a flat string map. */
        fun from(req: ScenarioExecutionRequest): ScenarioSpec {
            val parsed = parseFlatStringObject(req.inputJson)
            return ScenarioSpec(
                id = req.scenarioId.value,
                stepId = req.stepId,
                input = parsed,
            )
        }

        /**
         * Tiny parser for `{"k":"v","k2":123}` into a Map<String, String>. Sufficient
         * for the round-trip test; a real SDK would use a real JSON parser.
         */
        private fun parseFlatStringObject(json: String): Map<String, String> {
            val trimmed = json.trim().removePrefix("{").removeSuffix("}")
            if (trimmed.isBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            // Walk through the string, splitting on commas at the top level,
            // then splitting each pair on the first ':' to get key + raw value.
            var i = 0
            while (i < trimmed.length) {
                while (i < trimmed.length && trimmed[i] == ',') i++
                while (i < trimmed.length && trimmed[i].isWhitespace()) i++
                if (i >= trimmed.length || trimmed[i] != '"') break
                val keyEnd = findCloseQuote(trimmed, i + 1)
                if (keyEnd < 0) break
                val key = unescape(trimmed.substring(i + 1, keyEnd))
                i = keyEnd + 1
                while (i < trimmed.length && trimmed[i] != ':') i++
                if (i >= trimmed.length) break
                i++ // skip ':'
                while (i < trimmed.length && trimmed[i].isWhitespace()) i++
                val (raw, consumed) = readValue(trimmed, i)
                out[key] = raw
                i = consumed
            }
            return out
        }

        private fun findCloseQuote(s: String, from: Int): Int {
            var j = from
            while (j < s.length) {
                if (s[j] == '\\') { j += 2; continue }
                if (s[j] == '"') return j
                j++
            }
            return -1
        }

        private fun readValue(s: String, from: Int): Pair<String, Int> {
            if (s[from] == '"') {
                val end = findCloseQuote(s, from + 1)
                if (end < 0) return "" to s.length
                return unescape(s.substring(from + 1, end)) to (end + 1)
            }
            var j = from
            while (j < s.length && s[j] != ',' && !s[j].isWhitespace()) j++
            return s.substring(from, j) to j
        }

        private fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(s[i])
                    i++
                }
            }
            return sb.toString()
        }
    }
}
