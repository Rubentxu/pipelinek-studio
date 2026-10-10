package dev.pipelinek.studio.application.ports

/**
 * B4.0 (2026-10-10): the agent capability model.
 *
 * The S4 mandate says "MCP recursos paginados,
 * discoverability de actions y su procedencia;
 * capability-driven (no tool-per-step)". The capability
 * is the spine: every agent-facing action is gated
 * by an [AgentCapability] the operator must opt in
 * to. The default posture is "all disabled" — the
 * studio refuses every agent action until the operator
 * explicitly enables the relevant capability via
 * the env var `STUDIO_AGENT_CAPS=COMPARE,DIFF,...`.
 *
 * The five capabilities are the S4 action surface:
 *  - COMPARE: `studio.runs.compare(A, B)` — typed
 *    diff of two runs.
 *  - DIFF: `studio.snapshots.diff(A, B)` — typed
 *    diff of two snapshots.
 *  - PROMOTE: `studio.snapshots.promote(variantId)`
 *    — explicit user action to promote a variant to
 *    a canonical snapshot.
 *  - SELECT: `studio.runs.select(runId, stepOpId)` —
 *    contextualized single-step execution.
 *  - MUTATE: `studio.snapshots.mutate(snapshotId,
 *    mutationId)` — apply a controlled mutation to
 *    a snapshot (the B4.6 prompt-injection guard
 *    inspects the mutationId's metadata).
 *
 * The default impl [DisabledByDefaultAgentCapability]
 * returns false for every capability. A production
 * wiring overrides the default with
 * [EnvBackedAgentCapability] which reads
 * `STUDIO_AGENT_CAPS` (comma-separated cap names).
 * The test in B4.0AgentCapabilityTest pins the
 * default posture; the env override is a separate
 * test.
 */
enum class AgentCapability {
    COMPARE,
    DIFF,
    PROMOTE,
    SELECT,
    MUTATE,
}

/**
 * B4.0 (2026-10-10): the agent capability port.
 * Every agent-facing action consults this port
 * before doing anything. The fun interface lets a
 * test construct an inline port; a production
 * wiring uses [EnvBackedAgentCapability].
 */
fun interface AgentCapabilityPort {
    fun isEnabled(capability: AgentCapability): Boolean
}

/**
 * B4.0 (2026-10-10): the default impl, which
 * refuses every capability. The default posture is
 * the honesty layer: the studio will not run an
 * agent action until the operator explicitly
 * enables the relevant capability. A production
 * wiring overrides this with
 * [EnvBackedAgentCapability].
 */
object DisabledByDefaultAgentCapability : AgentCapabilityPort {
    override fun isEnabled(capability: AgentCapability): Boolean = false
}

/**
 * B4.0 (2026-10-10): the env-backed impl. Reads
 * the env var `STUDIO_AGENT_CAPS` (comma-separated
 * cap names, case-insensitive). When the env var
 * is unset or empty, the impl is "all disabled"
 * (same as [DisabledByDefaultAgentCapability]).
 * When the env var is set to "all", every cap is
 * enabled. The implementation is intentionally
 * strict: an unknown cap name in the env var is
 * logged and ignored, NOT enabled by default (a
 * typo in the env var would NOT silently grant a
 * cap).
 */
object EnvBackedAgentCapability : AgentCapabilityPort {
    private const val ALL = "all"

    override fun isEnabled(capability: AgentCapability): Boolean {
        val raw = System.getenv("STUDIO_AGENT_CAPS") ?: return false
        val parts = raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        if (ALL.uppercase() in parts) return true
        return capability.name.uppercase() in parts
    }
}
