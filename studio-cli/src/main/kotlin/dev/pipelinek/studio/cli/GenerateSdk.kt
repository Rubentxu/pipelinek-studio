package dev.pipelinek.studio.cli

import dev.pipelinek.studio.http.OpenApiDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * S5 E5.3 — SDK TypeScript client generator. Reads the curated OpenAPI 3.1 document
 * served at `/openapi.json` and emits a single `sdk.ts` file with typed fetch
 * wrappers. The output is hand-readable, framework-free, and depends only on the
 * `fetch` API available in Node 18+ and modern browsers.
 *
 * Coverage today: every documented GET + POST that takes a JSON body and returns
 * a JSON body. Path params (`{experimentId}`, `{runId}`, …) become required
 * function arguments. Headers with a known semantic (e.g. `Idempotency-Key`)
 * are not auto-emitted yet — the SDK is read/write-thin and assumes the
 * transport layer handles auth and idempotency.
 */
object GenerateSdk {
    fun generate(): String {
        val doc = Json.parseToJsonElement(OpenApiDoc.current).jsonObject
        // B5.2 (2026-10-10): pin the studio
        // API version in the SDK header so a
        // build that consumes the SDK can
        // detect a backend version mismatch.
        val apiVersion = (doc["x-studio-api-version"] as? JsonPrimitive)
            ?.contentOrNullSafe() ?: "unknown"
        val sb = StringBuilder()
        sb.appendLine("// Auto-generated from /openapi.json — DO NOT EDIT BY HAND.")
        sb.appendLine("// Regenerate with: studio-cli generate-sdk-ts > sdk.ts")
        sb.appendLine("//")
        sb.appendLine("// PipelineK Studio TS SDK — typed fetch wrappers.")
        sb.appendLine("// x-studio-api-version: $apiVersion")
        sb.appendLine("//")
        sb.appendLine("// Audit F-P1-VSCODE-2 (2026-10-10): every request now goes")
        sb.appendLine("// through `fetchWithTimeout` so a hung backend surfaces as a")
        sb.appendLine("// clear error within the deadline (default 5s) instead of")
        sb.appendLine("// hanging the editor forever. The caller can pass `fetchImpl`")
        sb.appendLine("// to override for tests.")
        sb.appendLine()
        sb.appendLine("import { fetchWithTimeout, DEFAULT_TIMEOUT_MS } from \"./config\";")
        sb.appendLine()
        sb.appendLine("export const API_VERSION = \"$apiVersion\";")
        sb.appendLine()
        sb.appendLine("export interface StudioClientOptions {")
        sb.appendLine("  baseUrl: string;")
        sb.appendLine("  fetchImpl?: typeof fetch;")
        sb.appendLine("  defaultHeaders?: Record<string, string>;")
        sb.appendLine("  /**")
        sb.appendLine("   * Per-request timeout in milliseconds. Defaults to")
        sb.appendLine("   * DEFAULT_TIMEOUT_MS (5s) which matches the MCP and web")
        sb.appendLine("   * defaults. A healthy local backend answers in well under 1s.")
        sb.appendLine("   */")
        sb.appendLine("  timeoutMs?: number;")
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("export class StudioClient {")
        sb.appendLine("  constructor(private readonly opts: StudioClientOptions) {}")
        sb.appendLine()
        sb.appendLine("  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {")
        sb.appendLine("    const headers: Record<string, string> = {")
        sb.appendLine("      'content-type': 'application/json',")
        sb.appendLine("      ...(this.opts.defaultHeaders ?? {}),")
        sb.appendLine("    };")
        sb.appendLine("    const res = await fetchWithTimeout(")
        sb.appendLine("      this.opts.baseUrl + path,")
        sb.appendLine("      {")
        sb.appendLine("        method,")
        sb.appendLine("        headers,")
        sb.appendLine("        body: body !== undefined ? JSON.stringify(body) : undefined,")
        sb.appendLine("      },")
        sb.appendLine("      this.opts.timeoutMs ?? DEFAULT_TIMEOUT_MS,")
        sb.appendLine("    );")
        sb.appendLine("    if (!res.ok && res.status !== 202) {")
        sb.appendLine("      const text = await res.text().catch(() => '');")
        sb.appendLine("      throw new Error(`HTTP \${res.status}: \${text}`);")
        sb.appendLine("    }")
        sb.appendLine("    return res.json() as Promise<T>;")
        sb.appendLine("  }")
        sb.appendLine()

        val paths = (doc["paths"] as? JsonObject) ?: JsonObject(emptyMap())
        for ((path, methods) in paths.toSortedMap().entries) {
            val methodsObj = methods.jsonObject
            for ((method, op) in methodsObj.toSortedMap().entries) {
                if (method !in setOf("get", "post")) continue
                val opObj = op.jsonObject
                val summary = (opObj["summary"] as? JsonPrimitive)?.contentOrNullSafe() ?: "$method $path"
                val fnName = deriveFunctionName(method, path)
                val params = extractPathParams(path)
                sb.appendLine("  /** $summary */")
                if (method == "get" || method == "post") {
                    val sig = buildSignature(fnName, params, method, opObj)
                    sb.appendLine("  $sig {")
                    val tsPath = Regex("\\{(\\w+)\\}").replace(path) { "\${${it.groupValues[1]}}" }
                    val bodyArg = if (method == "post") ", body" else ""
                    sb.appendLine("    return this.request<unknown>(\"${method.uppercase()}\", `${tsPath}`$bodyArg);")
                    sb.appendLine("  }")
                    sb.appendLine()
                }
            }
        }
        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("export const VERSION = \"${(doc["info"] as? JsonObject)?.get("version")?.jsonPrimitive?.contentOrNullSafe() ?: "0.0.0"}\";")
        return sb.toString()
    }

    private fun buildSignature(name: String, pathParams: List<String>, method: String, op: JsonObject): String {
        val params = pathParams.joinToString(", ") { "$it: string" }
        val body = if (method == "post") (if (params.isEmpty()) "body?: unknown" else ", body?: unknown") else ""
        return "async $name($params$body): Promise<unknown>"
    }

    private fun extractPathParams(path: String): List<String> =
        Regex("\\{(\\w+)\\}").findAll(path).map { it.groupValues[1] }.toList()

    private fun deriveFunctionName(method: String, path: String): String {
        // /runs/{runId}/events -> "getRunsRunIdEvents"; /openapi.json -> "getOpenapiJson";
        // /api/static-analyze-capability -> "getApiStaticAnalyzeCapability"; / -> "getRoot".
        val segments = path.split("/").filter { it.isNotEmpty() }
            .map { it.replace("{", "").replace("}", "") }
        val cleaned = segments.joinToString("_")
        val camel = segments
            .flatMap { it.split(Regex("[._-]")) }
            .filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
        val base = when {
            // Root path: the only segment is empty, so a literal `get`/`post` would
            // shadow JS built-ins and read like a no-op. Use a meaningful noun.
            segments.isEmpty() -> "Root"
            else -> camel
        }
        return when (method) {
            "get" -> "get$base"
            "post" -> "post$base"
            else -> "${method}_$cleaned"
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()
}
