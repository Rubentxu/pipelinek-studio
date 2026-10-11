package dev.pipelinek.studio.application.ports

import java.time.Instant

/**
 * R2.3 (2026-10-11): the admission lock — a durable, content-addressed
 * record of every plugin JAR the operator has explicitly admitted.
 *
 * The lock is the single source of truth for the `ADMITTED` state
 * in the [PluginAdmissionState] lifecycle. A JAR is `ADMITTED` iff
 * its on-disk sha256 matches an entry in the lock; otherwise it
 * falls back to `COMPATIBLE` (vendor + version OK, not yet accepted).
 *
 * Per the canonical R2 mandate: "Congelar el conjunto de plugins y
 * su digest por run" (R2.5) and the spec note that "Accepted/Refused
 * de admisión con causas; accepted no certifica seguridad ni calidad"
 * (STEP_PLUGIN_LAB.md §Flujo item 3).
 *
 * Persistence rule: the lock file is JSON, lives at
 * `~/.pipelinek-studio/plugin.lock.json`, and is rewritten on every
 * mutation (no append-only journal — the file is small and the
 * mutation rate is human-driven, not pipeline-driven).
 *
 * Security: the lock carries sha256 (a content hash) and an actor
 * identifier (a free-text string the operator supplies). No secrets.
 */
data class AdmittedPlugin(
    val pluginJar: String,
    val sha256: String,
    val admittedAt: Instant,
    val admittedBy: String,
)

/**
 * R2.3 (2026-10-11): read + write port for the admission lock.
 *
 * The lock is a flat list (no nesting). The producer implementation
 * (`FilePluginAdmissionLock` in `studio-infra-storage`) writes the
 * whole file on every save — the lock has no incremental update
 * semantics. A missing file is NOT an error: `load()` returns
 * `emptyList()` and `save(emptyList())` deletes the file. This
 * keeps the "no plugins admitted yet" state cheap and honest.
 */
interface PluginAdmissionLockPort {
    fun load(): List<AdmittedPlugin>
    fun save(admitted: List<AdmittedPlugin>)
}
