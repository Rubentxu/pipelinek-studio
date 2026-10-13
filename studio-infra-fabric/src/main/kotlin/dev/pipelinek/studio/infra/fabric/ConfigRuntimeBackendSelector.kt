@file:Suppress("TooGenericExceptionCaught")

package dev.pipelinek.studio.infra.fabric

import dev.pipelinek.studio.application.ports.RuntimeBackend
import dev.pipelinek.studio.application.ports.RuntimeBackendSelectionResult
import dev.pipelinek.studio.application.ports.RuntimeBackendSelector
import dev.pipelinek.studio.application.ports.RunRequest
import org.slf4j.LoggerFactory

/**
 * SFW-H2.4: a [RuntimeBackendSelector] that reads the
 * `runtime-backend` value from a `Map<String, String>`
 * config.
 *
 * ## Why a Map
 *
 * Production wires a `ConfigMap` env var; the test
 * passes a `Map.of("runtime-backend", "local")`. A
 * `Map` is the smallest surface that lets the test
 * drive the selector without booting a config loader.
 * A future work-unit may add an
 * `EnvVarRuntimeBackendSelector` that reads
 * `RUNTIME_BACKEND` directly; the port is unchanged.
 *
 * ## The discipline: typos are refused
 *
 * The H2.4 mandate is "no silent fallback". A typo
 * (`fabric-server`, `LOCAL`, an empty string, a
 * missing value) is **not** a default. The impl
 * returns [RuntimeBackendSelectionResult.Refused] with
 * a `reason` that names the unknown value + the
 * allowed values, so the operator sees the answer in
 * the 4xx.
 *
 * The empty config is the **one** default: a Studio
 * that does not set the flag keeps the pre-H2
 * [RuntimeBackend.Local] path. This is the only
 * silent default, and it is **explicit** in the
 * contract: a Studio's default config is the empty
 * map, and an empty map is mapped to Local.
 */
class ConfigRuntimeBackendSelector(
    private val config: Map<String, String>,
) : RuntimeBackendSelector {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun select(req: RunRequest): RuntimeBackendSelectionResult {
        val raw = config["runtime-backend"]?.trim()
        val result: RuntimeBackendSelectionResult = when {
            raw == null || raw.isEmpty() -> {
                // Empty config: the pre-H2 default. A
                // Studio that wants Fabric must opt in
                // explicitly. This is the only silent
                // default and it is documented in the
                // contract.
                log.info(
                    "runtime-backend selector: empty config -> Local (default; pre-H2 path) for runId={}",
                    req.runId.value,
                )
                RuntimeBackendSelectionResult.Allowed(RuntimeBackend.Local)
            }
            raw == "local" -> {
                log.info(
                    "runtime-backend selector: explicit local for runId={}",
                    req.runId.value,
                )
                RuntimeBackendSelectionResult.Allowed(RuntimeBackend.Local)
            }
            raw == "fabric" -> {
                log.info(
                    "runtime-backend selector: explicit fabric for runId={}",
                    req.runId.value,
                )
                RuntimeBackendSelectionResult.Allowed(RuntimeBackend.Fabric)
            }
            // SFW-H5.3+ (2026-10-13): the Podman
            // arm on the config-driven selector.
            // The H5.3 chain-start ships the
            // abstract surface + the stub; the
            // actual `podman run` invocation is
            // a follow-up H5.3+ unit. The
            // selector route to Podman is in
            // place; a production boot that
            // sets `runtime-backend: podman` will
            // see every request route to the
            // Podman arm (and surface as a
            // refusal at the dispatch sites
            // until the actual `podman run` is
            // wired).
            raw == "podman" -> {
                log.info(
                    "runtime-backend selector: explicit podman for runId={}",
                    req.runId.value,
                )
                RuntimeBackendSelectionResult.Allowed(RuntimeBackend.Podman)
            }
            else -> {
                // Typo / wrong case / unknown value. The
                // discipline: refuse, do NOT silently
                // route to Local. The reason names the
                // allowed values so the operator sees
                // the answer.
                val reason = "unknown runtime-backend value: '$raw' " +
                    "(allowed: 'local', 'fabric', 'podman'; the values are case-sensitive)"
                log.warn(
                    "runtime-backend selector: refused for runId={} reason={}",
                    req.runId.value, reason,
                )
                RuntimeBackendSelectionResult.Refused(reason)
            }
        }
        return result
    }
}
