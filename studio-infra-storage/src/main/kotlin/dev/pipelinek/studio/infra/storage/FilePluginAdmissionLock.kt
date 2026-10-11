package dev.pipelinek.studio.infra.storage

import dev.pipelinek.studio.application.ports.AdmittedPlugin
import dev.pipelinek.studio.application.ports.PluginAdmissionLockPort
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * R2.3 (2026-10-11): file-backed implementation of
 * [PluginAdmissionLockPort]. The lock lives at the path passed
 * in the constructor (default: `~/.pipelinek-studio/plugin.lock.json`).
 *
 * File format (JSON):
 * ```
 * {
 *   "format_version": 1,
 *   "admitted": [
 *     { "pluginJar": "...", "sha256": "...", "admittedAt": "2026-10-11T18:00:00Z", "admittedBy": "..." }
 *   ]
 * }
 * ```
 *
 * Semantics:
 *  - Missing file = empty lock (no plugins admitted).
 *  - Empty list passed to `save()` deletes the file (so the
 *    "no plugins admitted" state is the absence of the file, not
 *    a file containing `[]`).
 *  - Concurrent writers are not handled (the lock is human-driven
 *    and the use case serializes through the `PluginAdmissionUseCase`).
 *  - The format is versioned (`format_version`); on bump, a
 *    migration is required (this impl does not migrate — it
 *    refuses to read unknown format versions).
 */
// R0.1.3 (2026-10-11): the lock reads/writes a JSON file that the
// operator may have hand-edited or left corrupted. A broad catch
// returns an empty list (load) or deletes the file (save) — the
// operation is best-effort, the gate is honest. TooGeneric-
// ExceptionCaught is the correct posture here.
@Suppress("TooGenericExceptionCaught")
class FilePluginAdmissionLock(
    private val lockPath: Path,
) : PluginAdmissionLockPort {

    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = false
    }

    override fun load(): List<AdmittedPlugin> {
        if (!Files.isRegularFile(lockPath)) return emptyList()
        val text = Files.readString(lockPath)
        if (text.isBlank()) return emptyList()
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            log.warn("plugin.lock.json is not parseable: {}; treating as empty lock", e.message)
            return emptyList()
        }
        val formatVersion = obj["format_version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (formatVersion != 1) {
            log.warn(
                "plugin.lock.json has unknown format_version={}; treating as empty lock",
                formatVersion,
            )
            return emptyList()
        }
        val arr = obj["admitted"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val jar = o["pluginJar"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val sha = o["sha256"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val at = o["admittedAt"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val by = o["admittedBy"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            try {
                AdmittedPlugin(
                    pluginJar = jar,
                    sha256 = sha,
                    admittedAt = Instant.parse(at),
                    admittedBy = by,
                )
            } catch (e: Exception) {
                log.warn("plugin.lock.json: skipping entry with invalid timestamp '$at': {}", e.message)
                null
            }
        }
    }

    override fun save(admitted: List<AdmittedPlugin>) {
        if (admitted.isEmpty()) {
            // Empty lock = no file. Keeps the "no plugins admitted"
            // state visible (no file) rather than a file containing [].
            Files.deleteIfExists(lockPath)
            return
        }
        val admittedArray = kotlinx.serialization.json.JsonArray(
            admitted.map { buildAdmittedJson(it) },
        )
        val obj = buildJsonObject {
            put("format_version", JsonPrimitive(1))
            put("admitted", admittedArray)
        }
        Files.createDirectories(lockPath.parent)
        Files.writeString(lockPath, json.encodeToString(JsonObject.serializer(), obj))
    }

    private fun buildAdmittedJson(p: AdmittedPlugin): JsonObject = buildJsonObject {
        put("pluginJar", p.pluginJar)
        put("sha256", p.sha256)
        put("admittedAt", p.admittedAt.toString())
        put("admittedBy", p.admittedBy)
    }
}
